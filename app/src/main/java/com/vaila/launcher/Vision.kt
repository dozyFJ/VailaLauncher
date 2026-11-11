package com.vaila.launcher

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Eyes" for models that cannot see images: asks any available vision model
 * (Gemini, GLM-4.6V, a free OpenRouter vision model) to describe a screenshot.
 */
object Vision {
    private const val PROMPT = "Это скриншот страницы интернет-магазина на телефоне. Опиши его для веб-разработчика: " +
        "шапка, логотип, меню, цвета (примерные HEX), шрифты, кнопки, баннеры, расположение блоков, " +
        "что выглядит сломанным или некрасивым. Кратко, по пунктам, по-русски."

    fun describe(ctx: Context, jpg: ByteArray): String? {
        val p = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
        val b64 = Base64.encodeToString(jpg, Base64.NO_WRAP)
        val gkey = p.getString("gkey", "").orEmpty()
        if (gkey.isNotBlank()) runCatching { gemini(gkey, p.getString("gmodel", GeminiAgent.DEFAULT_MODEL)!!, b64) }
            .getOrNull()?.let { return "[Gemini] $it" }
        val zkey = p.getString("zkey", "").orEmpty()
        if (zkey.isNotBlank()) runCatching { openAi(OpenAiAgent.ZAI_BASE, zkey, "glm-4.6v-flash", b64) }
            .getOrNull()?.let { return "[GLM-4.6V] $it" }
        val orkey = p.getString("orkey", "").orEmpty()
        if (orkey.isNotBlank()) {
            val v = runCatching { FreeModels.openRouter(ctx) }.getOrDefault(emptyList()).filter { it.vision }.take(2)
            for (m in v) runCatching { openAi(FreeModels.OPENROUTER_BASE, orkey, m.id, b64) }
                .getOrNull()?.let { return "[${m.name}] $it" }
        }
        return null
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20_000
        c.readTimeout = 90_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    private fun gemini(key: String, model: String, b64: String): String? {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", PROMPT)))))
        val r = post("${GeminiAgent.BASE}/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
        val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        return (0 until parts.length()).mapNotNull { parts.getJSONObject(it).takeUnless { p -> p.optBoolean("thought") }?.optString("text") }
            .joinToString("").ifBlank { null }
    }

    private fun openAi(base: String, key: String, model: String, b64: String): String? {
        val body = JSONObject().put("model", model).put("max_tokens", 1200).put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                .put(JSONObject().put("type", "text").put("text", PROMPT)))))
        val r = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        return r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.ifBlank { null }
    }
}
