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
    private val box = ToolBox(st)

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
                        "bash" -> box.bash(input)
                        "str_replace_based_edit_tool" -> box.editor(input)
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

    private fun systemPrompt() = ToolBox.systemPrompt(st)

    // ---------- tools ----------

    private fun toJson(v: JsonValue): JSONObject =
        try { JSONObject(mapper.writeValueAsString(v)) } catch (_: Exception) { JSONObject() }

    companion object {
        const val MAX_STEPS = 40
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}
