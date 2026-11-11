package com.vaila.launcher

import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Tools the AI assistant runs on the phone: shell and a file editor confined to the app dir. */
class ToolBox(private val st: Stack) {
    private val knowledge by lazy { Knowledge(st) }

    /** remember / search_kb / learn_url, or null if [name] is not one of them. */
    fun extra(name: String, a: JSONObject) = knowledge.exec(name, a)

    fun bash(input: JSONObject): Triple<String, String, Boolean> {
        if (input.optBoolean("restart")) return Triple("bash restart", "Shell перезапущен.", false)
        val cmd = input.optString("command")
        if (cmd.isBlank()) return Triple("bash", "Пустая команда", true)
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .directory(st.htdocs.takeIf { it.isDirectory } ?: st.base)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    st.shellEnv().forEach { kv ->
                        val i = kv.indexOf('=')
                        environment()[kv.substring(0, i)] = kv.substring(i + 1)
                    }
                }
                .start()
            p.outputStream.close()
            val out = StringBuilder()
            val reader = Thread {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { if (out.length < MAX_OUTPUT) out.append(it).append('\n') }
                }
            }.apply { start() }
            val done = p.waitFor(BASH_TIMEOUT_S, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            reader.join(2000)
            var text = out.toString()
            if (text.length >= MAX_OUTPUT) text += "\n…(вывод обрезан)"
            if (!done) text += "\n(команда прервана по таймауту ${BASH_TIMEOUT_S}с)"
            val code = if (done) p.exitValue() else -1
            if (code != 0) text += "\n[exit $code]"
            Triple("$ $cmd", text.trimEnd(), code != 0)
        } catch (e: Exception) {
            Triple("$ $cmd", "Ошибка запуска: ${e.message}", true)
        }
    }

    /** Resolve a model-supplied path and keep it inside the app's files dir. */
    private fun safePath(raw: String): File {
        val root = st.base.canonicalFile
        val f = (if (raw.startsWith("/")) File(raw) else File(st.htdocs, raw)).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Путь вне папки приложения: $raw")
        }
        return f
    }

    fun editor(input: JSONObject): Triple<String, String, Boolean> {
        val cmd = input.optString("command")
        val raw = input.optString("path")
        val title = "✎ $cmd $raw"
        return try {
            val f = safePath(raw)
            when (cmd) {
                "view" -> {
                    if (f.isDirectory) {
                        val list = f.walkTopDown().maxDepth(2).drop(1).take(400)
                            .joinToString("\n") { it.relativeTo(f).path + if (it.isDirectory) "/" else "" }
                        Triple(title, list, false)
                    } else {
                        if (!f.exists()) return Triple(title, "Файл не найден: $raw", true)
                        val lines = f.readLines()
                        val range = input.optJSONArray("view_range")
                        val from = range?.optInt(0, 1)?.coerceAtLeast(1) ?: 1
                        val toRaw = range?.optInt(1, -1) ?: -1
                        val to = if (toRaw == -1) lines.size else toRaw.coerceAtMost(lines.size)
                        val body = (from..to).joinToString("\n") { "$it\t${lines.getOrElse(it - 1) { "" }}" }
                        Triple(title, if (body.length > MAX_OUTPUT) body.take(MAX_OUTPUT) + "\n…(обрезано, используй view_range)" else body, false)
                    }
                }
                "create" -> {
                    if (f.exists()) f.copyTo(File(f.path + ".bak"), overwrite = true)
                    f.parentFile?.mkdirs()
                    f.writeText(input.optString("file_text"))
                    Triple(title, "Файл записан.", false)
                }
                "str_replace" -> {
                    if (!f.isFile) return Triple(title, "Файл не найден: $raw", true)
                    val text = f.readText()
                    val old = input.optString("old_str")
                    var count = 0
                    if (old.isNotEmpty()) {
                        var i = text.indexOf(old)
                        while (i >= 0 && count < 2) { count++; i = text.indexOf(old, i + 1) }
                    }
                    when {
                        count == 0 -> Triple(title, "old_str не найден в файле", true)
                        count > 1 -> Triple(title, "old_str найден несколько раз, нужен ровно 1 — добавь контекст", true)
                        else -> {
                            f.writeText(text.replaceFirst(old, input.optString("new_str")))
                            Triple(title, "Заменено.", false)
                        }
                    }
                }
                "insert" -> {
                    if (!f.isFile) return Triple(title, "Файл не найден: $raw", true)
                    val lines = f.readLines().toMutableList()
                    val at = input.optInt("insert_line", 0).coerceIn(0, lines.size)
                    lines.addAll(at, input.optString("insert_text").split("\n"))
                    f.writeText(lines.joinToString("\n") + "\n")
                    Triple(title, "Вставлено после строки $at.", false)
                }
                else -> Triple(title, "Неизвестная команда $cmd", true)
            }
        } catch (e: Exception) {
            Triple(title, "Ошибка: ${e.message}", true)
        }
    }

    companion object {
        const val MAX_OUTPUT = 20_000
        const val BASH_TIMEOUT_S = 120L

        fun systemPrompt(st: Stack) = """
            Ты — ИИ-мастер внутри Android-приложения PocketShop Server. Оно запускает на телефоне
            локальный веб-сервер (PHP 8.5, встроенный сервер php -S) и MariaDB, на которых работает магазин OpenCart 4.0.2.3.
            Пользователь пишет по-русски и просит изменить сайт: дизайн, тексты, товары, настройки.
            Делай изменения сам с помощью инструментов, потом коротко по-русски расскажи, что сделал.

            Окружение:
            - Папка сайта (корень OpenCart): ${st.htdocs.path}
            - Сайт: http://127.0.0.1:${Stack.HTTP_PORT}/  Админка: /admin/
            - База: MariaDB 127.0.0.1:${Stack.MYSQL_PORT}, база ${Stack.DB_NAME}, префикс таблиц oc_.
              Консоль: mariadb -h 127.0.0.1 -u root ${Stack.DB_NAME} -e "SQL"
            - Shell: Android /system/bin/sh (mksh + toybox: ls, cat, sed, grep, find, cp, mv, unzip…).
              Каждая команда shell запускается заново в папке сайта; cd между вызовами не сохраняется.
              Нет root, нет apt/pkg, нет git и composer. Доступны php и mariadb.
            - Инструменты работы с файлами работают только внутри ${st.base.path};
              относительные пути считаются от папки сайта.

            - Инструмент view_site делает скриншот страницы сайта, как на телефоне. После правок дизайна
              посмотри результат и при необходимости поправь.
            - Фото, которые присылает пользователь, уже сохранены в image/catalog/uploads/ (путь указан в сообщении).
              В OpenCart картинки товаров и логотип задаются путём относительно image/, например catalog/uploads/x.jpg
              (логотип: ключ config_logo в oc_setting, store_id=0, code=config).

            OpenCart 4:
            - Шаблоны витрины: catalog/view/template/ (Twig), стили: catalog/view/stylesheet/stylesheet.css,
              языковые файлы: catalog/language/. Настройки магазина — таблица oc_setting.
            - После правки шаблонов или настроек очисти кеш: rm -rf system/storage/cache/* 
              (папку cache не удаляй, только содержимое).
            - Перед большими правками делай копию файла (cp file file.bak), чтобы можно было откатить.
            - Не удаляй базу и не трогай config.php без явной просьбы.
            Пиши кратко. Если задача неоднозначна, сделай разумный вариант и предложи, что можно поменять.
        """.trimIndent() + Knowledge(st).promptSection()
    }
}
