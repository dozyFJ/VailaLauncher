package com.vaila.launcher

import android.text.Html
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

/**
 * What the assistant "learns": a memory file it writes itself, and a knowledge base of
 * text files (built-in OpenCart notes + pages fetched from the internet) it can search.
 */
class Knowledge(private val st: Stack) {
    val dir = File(st.base, "ai").apply { mkdirs() }
    val kbDir = File(dir, "kb").apply { mkdirs() }
    val memFile = File(dir, "memory.md")

    init { installBuiltin() }

    private fun installBuiltin() {
        val names = runCatching { st.ctx.assets.list("kb") }.getOrNull() ?: return
        for (n in names) {
            val dest = File(kbDir, n)
            runCatching {
                st.ctx.assets.open("kb/$n").use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            }
        }
    }

    // ---------- memory ----------

    fun memory(): String = if (memFile.exists()) memFile.readText() else ""
    fun setMemory(text: String) = memFile.writeText(text)

    fun remember(note: String): Triple<String, String, Boolean> {
        val n = note.trim().replace("\n", " ")
        if (n.isEmpty()) return Triple("🧠 запомнить", "Пустая заметка", true)
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        memFile.appendText("- [$stamp] $n\n")
        // Keep memory compact: drop the oldest lines beyond the limit.
        val lines = memFile.readLines()
        if (lines.size > MAX_MEMORY_LINES) memFile.writeText(lines.takeLast(MAX_MEMORY_LINES).joinToString("\n") + "\n")
        return Triple("🧠 запомнил", n, false)
    }

    // ---------- knowledge base ----------

    fun files(): List<File> = kbDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    private data class Chunk(val source: String, val text: String)

    private fun chunks(): List<Chunk> {
        val out = mutableListOf<Chunk>()
        val sources = files().map { it.name to it.readText() } + listOf("память" to memory())
        for ((name, text) in sources) {
            val buf = StringBuilder()
            for (para in text.split(Regex("\n\\s*\n"))) {
                if (buf.length + para.length > 900 && buf.isNotEmpty()) {
                    out += Chunk(name, buf.toString().trim()); buf.clear()
                }
                buf.append(para).append("\n\n")
            }
            if (buf.isNotBlank()) out += Chunk(name, buf.toString().trim())
        }
        return out
    }

    private fun terms(s: String): List<String> =
        Regex("[\\p{L}\\p{N}_]{3,}").findAll(s.lowercase()).map { it.value.take(6) }.toList()

    /** Keyword search with IDF weighting and crude stemming (first 6 letters). */
    fun search(query: String): Triple<String, String, Boolean> {
        val q = terms(query).distinct()
        if (q.isEmpty()) return Triple("🔎 база", "Пустой запрос", true)
        val cs = chunks()
        val docs = cs.map { terms(it.text) }
        val df = q.associateWith { t -> docs.count { d -> t in d }.coerceAtLeast(1) }
        val scored = cs.indices.map { i ->
            val d = docs[i]
            var s = 0.0
            for (t in q) {
                val tf = d.count { it == t }
                if (tf > 0) s += (1 + ln(tf.toDouble())) * ln(1 + cs.size.toDouble() / df.getValue(t))
            }
            i to s
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(4)
        if (scored.isEmpty()) return Triple("🔎 $query", "Ничего не найдено. Можно изучить страницу через learn_url.", false)
        val text = scored.joinToString("\n\n---\n\n") { (i, _) -> "[${cs[i].source}]\n${cs[i].text}" }
        return Triple("🔎 $query", text, false)
    }

    /** Downloads a web page, keeps readable text and saves it to the knowledge base. */
    fun learnUrl(raw: String): Triple<String, String, Boolean> {
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return Triple("🌐 $url", "Нужна ссылка http(s)://", true)
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PocketShop")
            c.setRequestProperty("Accept-Language", "ru,en;q=0.8")
            if (c.responseCode !in 200..299) return Triple("🌐 $url", "HTTP ${c.responseCode}", true)
            val bytes = c.inputStream.use { it.readNBytesCompat(3_000_000) }
            val html = String(bytes, Charsets.UTF_8)
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim().orEmpty()
            val cleaned = html
                .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n\n")
            @Suppress("DEPRECATION")
            var text = Html.fromHtml(cleaned).toString()
                .replace(' ', ' ')
                .replace(Regex("[ \t]+"), " ")
                .replace(Regex("\n\\s*\n\\s*(\n\\s*)+"), "\n\n")
                .trim()
            if (text.length < 200) return Triple("🌐 $url", "На странице почти нет текста (возможно, она рисуется скриптами).", true)
            if (text.length > MAX_PAGE_CHARS) text = text.take(MAX_PAGE_CHARS)
            val u = URL(url)
            val name = (u.host + u.path).replace(Regex("[^A-Za-z0-9а-яА-Я._-]+"), "_").trim('_').take(80) + ".md"
            File(kbDir, name).writeText("# ${title.ifEmpty { url }}\nИсточник: $url\n\n$text\n")
            Triple("🌐 изучил $url", "Сохранено в базу как $name (${text.length} символов). Начало:\n" + text.take(1500), false)
        } catch (e: Exception) {
            Triple("🌐 $url", "Не удалось скачать: ${e.message}", true)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Section appended to the system prompt. */
    fun promptSection(): String {
        val mem = memory().let { if (it.length > MAX_MEMORY_IN_PROMPT) "…\n" + it.takeLast(MAX_MEMORY_IN_PROMPT) else it }
        val kb = files().joinToString(", ") { it.name }
        return """

            Память и база знаний (так ты «учишься»):
            - remember(note): запиши коротко полезный факт или удачный рецепт (что сделал, какие файлы/таблицы, что понравилось пользователю).
              Записывай после успешных задач и когда пользователь сообщает о своих предпочтениях. Не записывай пароли и ключи.
            - search_kb(query): поиск по базе знаний и памяти. Используй перед незнакомой задачей (SQL для товаров, валюта, шаблоны).
            - learn_url(url): скачать страницу из интернета в базу знаний (документация, статьи). Используй, когда пользователь даёт ссылку
              или когда в базе нет ответа и ты знаешь нужную страницу.
            Файлы в базе: ${kb.ifEmpty { "(пусто)" }}
            Твоя память (прошлые заметки):
        """.trimIndent() + "\n" + mem.ifBlank { "(пока пусто)" }
    }

    fun exec(name: String, a: org.json.JSONObject): Triple<String, String, Boolean>? = when (name) {
        "remember" -> remember(a.optString("note"))
        "search_kb" -> search(a.optString("query"))
        "learn_url" -> learnUrl(a.optString("url"))
        else -> null
    }

    companion object {
        const val MAX_MEMORY_LINES = 200
        const val MAX_MEMORY_IN_PROMPT = 4000
        const val MAX_PAGE_CHARS = 60_000
    }
}
