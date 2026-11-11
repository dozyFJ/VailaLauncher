package com.vaila.launcher

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolBash20250124
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolTextEditor20250728
import com.fasterxml.jackson.databind.ObjectMapper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AI assistant that edits the local OpenCart site: Claude with the bash and
 * text editor tools, both executed here on the phone inside the app sandbox.
 */
class Agent(
    private val st: Stack,
    apiKey: String,
    private val model: String,
    private val screenshot: ((String) -> ByteArray?)? = null,
) {

    /** Events for the chat UI. */
    sealed class Event {
        data class Text(val text: String) : Event()
        data class Tool(val title: String, val output: String, val error: Boolean) : Event()
        data class Status(val text: String) : Event()
    }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(java.time.Duration.ofMinutes(10))
        .build()

    private val mapper = ObjectMapper()

    /** Run one user request to completion; [history] is updated in place. */
    fun run(
        history: MutableList<MessageParam>,
        userText: String,
        images: List<ByteArray>,
        emit: (Event) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val start = history.size
        fun rollback() { while (history.size > start) history.removeAt(history.lastIndex) }
        val content = images.map { ContentBlockParam.ofImage(image(it)) } +
            ContentBlockParam.ofText(TextBlockParam.builder().text(userText).build())
        history += MessageParam.ofUserOfContentBlockParams(content)
        for (step in 1..MAX_STEPS) {
            if (cancelled()) {
                // History must not end with tool_use lacking results; it ends with
                // our tool results (user turn) here, which is valid to continue from.
                emit(Event.Status("Остановлено"))
                return
            }
            emit(Event.Status(if (step == 1) "Думаю…" else "Шаг $step…"))
            val msg: Message = try {
                client.messages().create(params(history))
            } catch (e: AnthropicServiceException) {
                rollback()
                emit(Event.Status("Ошибка API ${e.statusCode()}: ${e.message}"))
                return
            } catch (e: Exception) {
                rollback()
                emit(Event.Status("Ошибка сети: ${e.message}"))
                return
            }

            // Keep the full assistant content (incl. thinking blocks) unchanged.
            history += msg.toParam()

            val stop = msg.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) {
                emit(Event.Status("Claude отказался выполнять этот запрос."))
                return
            }

            val results = mutableListOf<ContentBlockParam>()
            for (block in msg.content()) {
                block.text().ifPresent { t -> if (t.text().isNotBlank()) emit(Event.Text(t.text())) }
                block.toolUse().ifPresent { tu ->
                    val input = toJson(tu._input())
                    if (tu.name() == "view_site") {
                        val path = input.optString("path", "/")
                        val jpg = screenshot?.invoke(path)
                        if (jpg == null) {
                            emit(Event.Tool("👁 $path", "Не удалось снять скриншот (сервер запущен?)", true))
                            results += ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder().toolUseId(tu.id())
                                    .content("Не удалось открыть страницу. Проверь, что PHP-сервер запущен.")
                                    .isError(true).build()
                            )
                        } else {
                            emit(Event.Tool("👁 $path", "Скриншот ${jpg.size / 1024} КБ", false))
                            results += ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder().toolUseId(tu.id())
                                    .contentOfBlocks(listOf(ToolResultBlockParam.Content.Block.ofImage(image(jpg))))
                                    .build()
                            )
                        }
                        return@ifPresent
                    }
                    val (title, out, err) = when (tu.name()) {
                        "bash" -> bash(input)
                        "str_replace_based_edit_tool" -> editor(input)
                        else -> Triple(tu.name(), "Unknown tool ${tu.name()}", true)
                    }
                    emit(Event.Tool(title, out, err))
                    results += ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(tu.id())
                            .content(out.ifEmpty { "(пусто)" })
                            .isError(err)
                            .build()
                    )
                }
            }

            if (stop == StopReason.MAX_TOKENS && results.isEmpty()) {
                emit(Event.Status("Ответ обрезан (лимит токенов)."))
                return
            }
            if (results.isEmpty()) {
                emit(Event.Status("Готово"))
                return
            }
            history += MessageParam.ofUserOfContentBlockParams(results)
        }
        emit(Event.Status("Достигнут лимит шагов ($MAX_STEPS). Напиши «продолжай», чтобы продолжить."))
    }

    private fun params(history: List<MessageParam>): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(systemPrompt())
            .addTool(ToolBash20250124.builder().build())
            .addTool(ToolTextEditor20250728.builder().build())
            .addTool(viewSiteTool)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
            .messages(history)
            // Server-side fallback: a refused request is retried on a suitable model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

    private val viewSiteTool: Tool = Tool.builder()
        .name("view_site")
        .description(
            "Открывает страницу локального сайта в мобильном браузере (ширина телефона) и возвращает скриншот. " +
                "Используй, чтобы посмотреть, как выглядит сайт до и после правок дизайна."
        )
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    Tool.InputSchema.Properties.builder()
                        .putAdditionalProperty(
                            "path",
                            JsonValue.from(mapOf("type" to "string", "description" to "Путь страницы, например / или index.php?route=product/category&path=20"))
                        )
                        .build()
                )
                .required(listOf("path"))
                .build()
        )
        .build()

    private fun image(jpg: ByteArray): ImageBlockParam = ImageBlockParam.builder()
        .source(
            Base64ImageSource.builder()
                .data(android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP))
                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                .build()
        )
        .build()

    private fun systemPrompt() = """
        Ты — ИИ-мастер внутри Android-приложения VailaLauncher. Оно запускает на телефоне
        локальный веб-сервер (PHP 8.5, встроенный сервер php -S) и MariaDB, на которых работает магазин OpenCart 4.0.2.3.
        Пользователь пишет по-русски и просит изменить сайт: дизайн, тексты, товары, настройки.
        Делай изменения сам с помощью инструментов, потом коротко по-русски расскажи, что сделал.

        Окружение:
        - Папка сайта (корень OpenCart): ${st.htdocs.path}
        - Сайт: http://127.0.0.1:${Stack.HTTP_PORT}/  Админка: /admin/
        - База: MariaDB 127.0.0.1:${Stack.MYSQL_PORT}, база ${Stack.DB_NAME}, префикс таблиц oc_.
          Консоль: mariadb -h 127.0.0.1 -u root ${Stack.DB_NAME} -e "SQL"
        - Shell: Android /system/bin/sh (mksh + toybox: ls, cat, sed, grep, find, cp, mv, unzip…).
          Каждая команда bash запускается заново в папке сайта; cd между вызовами не сохраняется.
          Нет root, нет apt/pkg, нет git и composer. Доступны php и mariadb.
        - Инструмент str_replace_based_edit_tool работает только внутри ${st.base.path};
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
    """.trimIndent()

    // ---------- tools ----------

    private fun toJson(v: JsonValue): JSONObject =
        try { JSONObject(mapper.writeValueAsString(v)) } catch (_: Exception) { JSONObject() }

    private fun bash(input: JSONObject): Triple<String, String, Boolean> {
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

    private fun editor(input: JSONObject): Triple<String, String, Boolean> {
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
        const val MAX_STEPS = 40
        const val MAX_OUTPUT = 20_000
        const val BASH_TIMEOUT_S = 120L
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}
