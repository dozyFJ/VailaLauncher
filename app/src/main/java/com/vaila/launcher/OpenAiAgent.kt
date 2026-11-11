package com.vaila.launcher

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Assistant over an OpenAI-compatible chat/completions API (used for Z.ai GLM,
 * which has free Flash models). Same tools as the other providers.
 */
class OpenAiAgent(
    st: Stack,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val vision: Boolean,
    private val fast: Boolean,
    private val extraBody: JSONObject? = null,
    private val maxToolChars: Int = ToolBox.MAX_OUTPUT,
    private val screenshot: ((String) -> ByteArray?)? = null,
) {
    private val box = ToolBox(st)
    private val system = ToolBox.systemPrompt(st) +
        if (vision) "" else "\nЭта модель не видит изображения: присланные фото доступны только как файлы по указанным путям, скриншоты view_site недоступны."

    class ApiException(val code: Int, message: String) : IOException(message)

    fun run(
        history: JSONArray,
        userText: String,
        images: List<ByteArray>,
        emit: (Agent.Event) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val start = history.length()
        fun rollback() { while (history.length() > start) history.remove(history.length() - 1) }
        history.put(userMessage(userText, images))

        for (step in 1..Agent.MAX_STEPS) {
            if (cancelled()) { emit(Agent.Event.Status("Остановлено")); return }
            emit(Agent.Event.Status(if (step == 1) "Думаю…" else "Шаг $step…"))
            val resp = try {
                callWithRetry(history, emit)
            } catch (e: ApiException) {
                rollback(); emit(Agent.Event.Status(friendly(e))); return
            } catch (e: Exception) {
                rollback(); emit(Agent.Event.Status("Ошибка сети: ${e.message}")); return
            }
            val choice = resp.optJSONArray("choices")?.optJSONObject(0)
            val msg = choice?.optJSONObject("message")
            if (msg == null) {
                rollback(); emit(Agent.Event.Status("Пустой ответ от модели. Попробуй ещё раз.")); return
            }
            val content = msg.optString("content").takeUnless { msg.isNull("content") }.orEmpty()
            val calls = msg.optJSONArray("tool_calls")

            // Store a clean assistant message (role, content, tool_calls).
            val stored = JSONObject().put("role", "assistant").put("content", content)
            if (calls != null && calls.length() > 0) stored.put("tool_calls", calls)
            history.put(stored)
            if (content.isNotBlank()) emit(Agent.Event.Text(content.trim()))

            if (calls == null || calls.length() == 0) {
                if (choice.optString("finish_reason") == "length") emit(Agent.Event.Status("Ответ обрезан (лимит токенов)."))
                else emit(Agent.Event.Status("Готово"))
                return
            }
            val shots = mutableListOf<ByteArray>()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
                val (title, out, err) = exec(name, args, shots)
                emit(Agent.Event.Tool(title, out, err))
                history.put(
                    JSONObject().put("role", "tool").put("tool_call_id", c.optString("id"))
                        .put("content", (if (err) "ОШИБКА: " else "") + clip(out.ifEmpty { "(пусто)" }))
                )
            }
            if (shots.isNotEmpty()) history.put(userMessage("Скриншот страницы:", shots))
        }
        emit(Agent.Event.Status("Достигнут лимит шагов (${Agent.MAX_STEPS}). Напиши «продолжай»."))
    }

    private fun clip(s: String) =
        if (s.length <= maxToolChars) s else s.take(maxToolChars) + "\n…(обрезано: смотри файл по частям через start_line/end_line или grep)"

    private fun userMessage(text: String, images: List<ByteArray>): JSONObject {
        if (images.isEmpty() || !vision) return JSONObject().put("role", "user").put("content", text)
        val parts = JSONArray()
        images.forEach {
            parts.put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(it, Base64.NO_WRAP))))
        }
        parts.put(JSONObject().put("type", "text").put("text", text))
        return JSONObject().put("role", "user").put("content", parts)
    }

    private fun exec(name: String, a: JSONObject, shots: MutableList<ByteArray>): Triple<String, String, Boolean> =
        when (name) {
            "bash" -> box.bash(JSONObject().put("command", a.optString("command")))
            "view_file" -> box.editor(JSONObject().put("command", "view").put("path", a.optString("path")).apply {
                if (a.has("start_line")) put("view_range", JSONArray().put(a.optInt("start_line", 1)).put(a.optInt("end_line", -1)))
            })
            "write_file" -> box.editor(JSONObject().put("command", "create").put("path", a.optString("path"))
                .put("file_text", a.optString("content")))
            "str_replace" -> box.editor(JSONObject().put("command", "str_replace").put("path", a.optString("path"))
                .put("old_str", a.optString("old_str")).put("new_str", a.optString("new_str")))
            "view_site" -> {
                val path = a.optString("path", "/")
                if (!vision) Triple("👁 $path", "Эта модель не видит изображения — скриншот недоступен.", true)
                else {
                    val jpg = screenshot?.invoke(path)
                    if (jpg == null) Triple("👁 $path", "Не удалось снять скриншот. Проверь, что PHP-сервер запущен.", true)
                    else { shots += jpg; Triple("👁 $path", "Скриншот приложен следующим сообщением.", false) }
                }
            }
            else -> box.extra(name, a) ?: Triple(name, "Неизвестный инструмент $name", true)
        }

    private fun callWithRetry(history: JSONArray, emit: (Agent.Event) -> Unit): JSONObject {
        var attempt = 0
        while (true) {
            try {
                return call(history)
            } catch (e: ApiException) {
                attempt++
                if ((e.code == 429 || e.code >= 500) && attempt <= 3) {
                    emit(Agent.Event.Status("Сервер занят или лимит, жду 15 с… (попытка $attempt из 3)"))
                    Thread.sleep(15_000)
                } else throw e
            }
        }
    }

    private fun call(history: JSONArray): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until history.length()) messages.put(history.get(i))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", TOOLS)
            .put("tool_choice", "auto")
            .put("max_tokens", 8192)
        if (fast) body.put("thinking", JSONObject().put("type", "disabled"))
        extraBody?.keys()?.forEach { k -> body.put(k, extraBody.get(k)) }
        val c = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000
        c.readTimeout = 300_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
            throw ApiException(code, err?.optString("message")?.ifBlank { null } ?: text.take(500))
        }
        return JSONObject(text)
    }

    private fun friendly(e: ApiException) = when (e.code) {
        429 -> "Лимит запросов исчерпан: ${e.message?.take(200)}. Подожди немного и повтори."
        401, 403 -> "Ключ не подходит (${e.code}): ${e.message}"
        404 -> "Модель «$model» не найдена: ${e.message}"
        400 -> if ("context" in e.message.orEmpty().lowercase()) "Разговор не влезает в память модели. Начни «Новый чат» (меню ⋮)." else "Ошибка API 400: ${e.message}"
        else -> "Ошибка API ${e.code}: ${e.message}"
    }

    companion object {
        const val ZAI_BASE = "https://api.z.ai/api/paas/v4"
        const val ZAI_DEFAULT_MODEL = "glm-4.7-flash"

        /** Same tools as Gemini, converted to JSON-schema (lowercase types). */
        val TOOLS: JSONArray by lazy {
            val out = JSONArray()
            val src = GeminiAgent.TOOLS
            for (i in 0 until src.length()) {
                val f = JSONObject(src.getJSONObject(i).toString())
                f.put("parameters", lowerTypes(f.getJSONObject("parameters")))
                out.put(JSONObject().put("type", "function").put("function", f))
            }
            out
        }

        private fun lowerTypes(o: JSONObject): JSONObject {
            if (o.has("type")) o.put("type", o.getString("type").lowercase())
            o.optJSONObject("properties")?.let { p ->
                p.keys().forEach { k -> p.optJSONObject(k)?.let { lowerTypes(it) } }
            }
            return o
        }
    }
}
