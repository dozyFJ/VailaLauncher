package com.vaila.launcher

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Same assistant as [Agent], on Google Gemini (has a free tier).
 * Uses the REST generateContent API with function calling; model turns are
 * stored exactly as returned so thought signatures go back unchanged.
 */
class GeminiAgent(
    st: Stack,
    private val apiKey: String,
    private var model: String,
    private val screenshot: ((String) -> ByteArray?)? = null,
    /** Called when the model was retired and replaced automatically. */
    private val onModelChanged: ((String) -> Unit)? = null,
) {
    private var switched = false
    private val box = ToolBox(st)

    /** Retries on 429/5xx before giving up (lower in auto mode to switch faster). */
    var retries = 3
    private val system = ToolBox.systemPrompt(st)

    class ApiException(val code: Int, message: String, val retryAfterS: Int) : IOException(message)

    fun run(
        history: JSONArray,
        userText: String,
        images: List<ByteArray>,
        emit: (Agent.Event) -> Unit,
        cancelled: () -> Boolean,
    ): Boolean {
        val start = history.length()
        fun rollback() { while (history.length() > start) history.remove(history.length() - 1) }

        val parts = JSONArray()
        images.forEach { parts.put(imagePart(it)) }
        parts.put(JSONObject().put("text", userText))
        history.put(JSONObject().put("role", "user").put("parts", parts))

        for (step in 1..Agent.MAX_STEPS) {
            if (cancelled()) { emit(Agent.Event.Status("Остановлено")); return true }
            emit(Agent.Event.Status(if (step == 1) "Думаю…" else "Шаг $step…"))

            val resp = try {
                callWithRetry(history, emit)
            } catch (e: ApiException) {
                rollback()
                emit(Agent.Event.Status(friendly(e)))
                return false
            } catch (e: Exception) {
                rollback()
                emit(Agent.Event.Status("Ошибка сети: ${e.message}"))
                return false
            }

            val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
            val content = cand?.optJSONObject("content")
            val respParts = content?.optJSONArray("parts")
            val finish = cand?.optString("finishReason").orEmpty()
            if (cand == null || respParts == null || respParts.length() == 0) {
                rollback()
                val block = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                emit(Agent.Event.Status("Gemini не ответил (${block.ifEmpty { finish.ifEmpty { "пустой ответ" } }}). Попробуй переформулировать."))
                return false
            }
            if (!content.has("role")) content.put("role", "model")
            history.put(content)

            val results = JSONArray()
            val shots = mutableListOf<ByteArray>()
            for (i in 0 until respParts.length()) {
                val p = respParts.getJSONObject(i)
                if (p.optBoolean("thought")) continue
                p.optString("text").takeIf { it.isNotBlank() }?.let { emit(Agent.Event.Text(it.trim())) }
                val call = p.optJSONObject("functionCall") ?: continue
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                val (title, out, err) = exec(name, args, shots)
                emit(Agent.Event.Tool(title, out, err))
                val fr = JSONObject().put("name", name)
                    .put("response", JSONObject().put("output", out.ifEmpty { "(пусто)" }).put("error", err))
                if (call.has("id")) fr.put("id", call.getString("id"))
                results.put(JSONObject().put("functionResponse", fr))
            }

            if (results.length() == 0) {
                if (finish == "MAX_TOKENS") emit(Agent.Event.Status("Ответ обрезан (лимит токенов)."))
                else emit(Agent.Event.Status("Готово"))
                return true
            }
            shots.forEach { results.put(imagePart(it)) }
            history.put(JSONObject().put("role", "user").put("parts", results))
        }
        emit(Agent.Event.Status("Достигнут лимит шагов (${Agent.MAX_STEPS}). Напиши «продолжай»."))
        return true
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
                val jpg = screenshot?.invoke(path)
                if (jpg == null) Triple("👁 $path", "Не удалось снять скриншот. Проверь, что PHP-сервер запущен.", true)
                else { shots += jpg; Triple("👁 $path", "Скриншот приложен следующим изображением (${jpg.size / 1024} КБ).", false) }
            }
            else -> box.extra(name, a) ?: Triple(name, "Неизвестный инструмент $name", true)
        }

    private fun callWithRetry(history: JSONArray, emit: (Agent.Event) -> Unit): JSONObject {
        var attempt = 0
        while (true) {
            try {
                return call(history)
            } catch (e: ApiException) {
                if (!switched && isRetired(e)) {
                    switched = true
                    val repl = runCatching { pickModel(listModels(apiKey), model) }.getOrNull()
                    if (repl != null && repl != model) {
                        emit(Agent.Event.Status("Модель $model больше недоступна — переключаюсь на $repl"))
                        model = repl
                        onModelChanged?.invoke(repl)
                        continue
                    }
                }
                attempt++
                if ((e.code == 429 || e.code == 503 || e.code == 500) && attempt <= retries) {
                    val wait = e.retryAfterS.coerceIn(5, 60)
                    emit(Agent.Event.Status("Лимит/перегрузка Gemini, жду $wait с… (попытка $attempt из $retries)"))
                    Thread.sleep(wait * 1000L)
                } else throw e
            }
        }
    }

    private fun call(history: JSONArray): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", history)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", TOOLS)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 16384))
        val c = URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000
        c.readTimeout = 300_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-goog-api-key", apiKey)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
            var retry = 20
            err?.optJSONArray("details")?.let { d ->
                for (i in 0 until d.length()) {
                    val rd = d.optJSONObject(i)?.optString("retryDelay").orEmpty()
                    rd.removeSuffix("s").toDoubleOrNull()?.let { retry = it.toInt() + 1 }
                }
            }
            throw ApiException(code, err?.optString("message") ?: text.take(500), retry)
        }
        return JSONObject(text)
    }

    private fun isRetired(e: ApiException): Boolean {
        val m = e.message.orEmpty().lowercase()
        return e.code == 404 || "no longer available" in m || ("model" in m && "not found" in m)
    }

    private fun friendly(e: ApiException) = when (e.code) {
        429 -> "Бесплатный лимит Gemini исчерпан (${e.message?.take(200)}). Подожди минуту, а если это дневной лимит — до завтра, или выбери другую модель в настройках."
        400 -> "Gemini отклонил запрос (400): ${e.message}"
        401, 403 -> "Ключ Gemini не подходит (${e.code}): ${e.message}"
        404 -> "Модель «$model» не найдена. Открой «Ключ и модель» → «Список моделей»."
        else -> "Ошибка Gemini ${e.code}: ${e.message}"
    }

    private fun imagePart(jpg: ByteArray) = JSONObject().put(
        "inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpg, Base64.NO_WRAP))
    )

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-flash-latest"

        private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        private fun fn(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(required)))

        val TOOLS: JSONArray = JSONArray()
            .put(fn("bash", "Выполнить команду в Android shell (mksh + toybox, php, mariadb) в папке сайта. Возвращает вывод.",
                JSONObject().put("command", prop("STRING", "Команда shell")), listOf("command")))
            .put(fn("view_file", "Показать файл с номерами строк или список файлов папки. Путь относительно папки сайта.",
                JSONObject().put("path", prop("STRING", "Путь к файлу или папке"))
                    .put("start_line", prop("INTEGER", "С какой строки (необязательно)"))
                    .put("end_line", prop("INTEGER", "По какую строку, -1 = до конца (необязательно)")),
                listOf("path")))
            .put(fn("write_file", "Создать или полностью перезаписать файл (старая версия сохраняется в .bak).",
                JSONObject().put("path", prop("STRING", "Путь к файлу")).put("content", prop("STRING", "Новое содержимое файла")),
                listOf("path", "content")))
            .put(fn("str_replace", "Заменить в файле ровно один фрагмент old_str на new_str. old_str должен встречаться в файле ровно 1 раз.",
                JSONObject().put("path", prop("STRING", "Путь к файлу")).put("old_str", prop("STRING", "Точный фрагмент"))
                    .put("new_str", prop("STRING", "Чем заменить")),
                listOf("path", "old_str", "new_str")))
            .put(fn("remember", "Запомнить полезный факт или удачный рецепт в долговременную память (видна в следующих чатах).",
                JSONObject().put("note", prop("STRING", "Короткая заметка")), listOf("note")))
            .put(fn("search_kb", "Поиск по базе знаний (шпаргалка OpenCart, изученные страницы) и памяти.",
                JSONObject().put("query", prop("STRING", "Что искать, ключевые слова")), listOf("query")))
            .put(fn("learn_url", "Скачать страницу из интернета и сохранить её текст в базу знаний.",
                JSONObject().put("url", prop("STRING", "Ссылка http(s)://")), listOf("url")))
            .put(fn("view_site", "Сделать скриншот страницы локального сайта в ширину телефона, чтобы посмотреть дизайн.",
                JSONObject().put("path", prop("STRING", "Путь страницы, например /")), listOf("path")))

        /**
         * Picks a replacement for a retired model: the "latest" alias if offered,
         * otherwise the newest Flash (Flash-Lite if the old one was Lite), skipping previews.
         */
        fun pickModel(ids: List<String>, old: String): String? {
            if (ids.isEmpty()) return null
            val lite = "lite" in old
            ids.firstOrNull { it == (if (lite) "gemini-flash-lite-latest" else "gemini-flash-latest") }?.let { return it }
            val stable = ids.filter { "flash" in it && ("lite" in it) == lite && listOf("preview", "exp").none { x -> x in it } }
            val ver = { id: String -> Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
            return (stable.ifEmpty { ids.filter { "flash" in it } }.ifEmpty { ids }).maxByOrNull { ver(it) }
        }

        /** Model ids from the account that support generateContent. */
        fun listModels(apiKey: String): List<String> {
            val c = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: " + text.take(300))
            val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                val id = m.optString("name").removePrefix("models/")
                if ("generateContent" in methods && id.startsWith("gemini") &&
                    listOf("image", "tts", "audio", "embedding", "live").none { it in id }) out += id
            }
            return out.sortedDescending()
        }
    }
}
