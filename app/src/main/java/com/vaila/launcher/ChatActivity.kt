package com.vaila.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.webkit.WebView
import android.widget.HorizontalScrollView
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.anthropic.models.messages.MessageParam

/** «ИИ-мастер»: chat with Claude that edits the local site. */
class ChatActivity : Activity() {

    companion object {
        /** One conversation: its own history for every provider and its transcript. */
        class ChatState(var title: String) {
            val history = mutableListOf<MessageParam>()
            val geminiHistory = org.json.JSONArray()
            val glmHistory = org.json.JSONArray()
            val localHistory = org.json.JSONArray()
            val orHistories = HashMap<String, org.json.JSONArray>()
            val openAiHistory = org.json.JSONArray()
            val transcript = mutableListOf<Pair<Int, String>>()
        }

        // Survives activity recreation (rotation) while the process lives.
        val chats = mutableListOf(ChatState("Чат 1"))
        var cur = 0
        val chat get() = chats[cur]
        val history get() = chat.history
        val geminiHistory get() = chat.geminiHistory
        val glmHistory get() = chat.glmHistory
        val localHistory get() = chat.localHistory
        val orHistories get() = chat.orHistories
        val openAiHistory get() = chat.openAiHistory
        val transcript get() = chat.transcript
        @Volatile var running = false
        @Volatile var cancel = false
        var listener: ((Int, String) -> Unit)? = null

        const val K_USER = 0
        const val K_AI = 1
        const val K_TOOL = 2
        const val K_TOOL_ERR = 3
        const val K_STATUS = 4
    }

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var attachInfo: TextView
    private val pending = mutableListOf<Pair<String, ByteArray>>() // saved path, jpeg
    private val prefs by lazy { getSharedPreferences("ai", MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ИИ-мастер · " + providerName()
        WebView.enableSlowWholeDocumentDraw()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(topBar())
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(quickRow())
        attachInfo = TextView(this).apply {
            setPadding(px(12), px(2), px(12), 0)
            setTextColor(0xFF1E88E5.toInt())
            visibility = android.view.View.GONE
            setOnClickListener { pending.clear(); updateAttach() }
        }
        root.addView(attachInfo)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(8), px(4), px(8), px(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Например: сделай шапку сайта тёмно-синей"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
        }
        send = Button(this).apply { setOnClickListener { onSend() } }
        val attach = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "📎"
            textSize = 20f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { pickPhotos() }
        }
        bar.addView(attach)
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send)
        root.addView(bar)
        setContentView(root)

        if (transcript.isEmpty()) {
            transcript += K_STATUS to ("Пиши, что поменять на сайте: дизайн, цвета, тексты, товары, настройки. " +
                "Я сам правлю файлы и базу прямо на телефоне. Перед большими правками делаю .bak-копии.")
        }
        if (currentKey().isBlank()) settings()
    }

    override fun onResume() {
        super.onResume()
        // Re-render everything: events may have arrived while paused.
        list.removeAllViews()
        transcript.toList().forEach { render(it.first, it.second) }
        updateSend()
        updateTop()
        listener = { k, t -> runOnUiThread { render(k, t); updateSend() } }
    }

    override fun onPause() {
        listener = null
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Новый чат")
        menu.add(0, 2, 1, "Ключ и модель")
        menu.add(0, 3, 2, "🧠 Память ИИ")
        menu.add(0, 4, 3, "📚 База знаний")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> if (!running) {
                chats += ChatState("Чат ${chats.size + 1}"); cur = chats.lastIndex; rerender()
            }
            2 -> settings()
            3 -> memoryDialog()
            4 -> kbDialog()
        }
        return true
    }

    private lateinit var chatBtn: Button
    private lateinit var provBtn: Button

    private fun topBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(2), px(6), 0)
        }
        chatBtn = Button(this, null, android.R.attr.buttonStyleSmall).apply {
            isAllCaps = false
            setOnClickListener { chatSwitcher() }
        }
        provBtn = Button(this, null, android.R.attr.buttonStyleSmall).apply {
            isAllCaps = false
            setOnClickListener { providerSwitcher() }
        }
        bar.addView(chatBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(provBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updateTop()
        return bar
    }

    private fun updateTop() {
        if (!::chatBtn.isInitialized) return
        chatBtn.text = "💬 ${chat.title.take(18)} ▾"
        provBtn.text = "🤖 ${providerName()} ▾"
        title = "ИИ-мастер · " + providerName()
    }

    private fun rerender() {
        list.removeAllViews()
        if (transcript.isEmpty()) transcript += K_STATUS to "Новый чат. Пиши, что поменять на сайте."
        transcript.toList().forEach { render(it.first, it.second) }
        updateTop()
    }

    private fun chatSwitcher() {
        if (running) { android.widget.Toast.makeText(this, "Дождись ответа или нажми «Стоп»", android.widget.Toast.LENGTH_SHORT).show(); return }
        val items = chats.mapIndexed { i, c -> (if (i == cur) "● " else "   ") + c.title } + listOf("➕ Новый чат", "🗑 Удалить текущий")
        AlertDialog.Builder(this).setTitle("Чаты")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i < chats.size -> cur = i
                    i == chats.size -> { chats += ChatState("Чат ${chats.size + 1}"); cur = chats.lastIndex }
                    else -> {
                        if (chats.size == 1) { chats[0] = ChatState("Чат 1") } else { chats.removeAt(cur); cur = 0 }
                    }
                }
                rerender()
            }.show()
    }

    private fun providerSwitcher() {
        if (running) { android.widget.Toast.makeText(this, "Дождись ответа или нажми «Стоп»", android.widget.Toast.LENGTH_SHORT).show(); return }
        data class P(val id: String, val label: String, val ready: Boolean)
        fun has(k: String) = !prefs.getString(k, "").isNullOrBlank()
        val localReady = LocalLlm.MODELS.any { LocalLlm.file(this, it).exists() }
        val ps = listOf(
            P("auto", "🔄 Авто — лучшая бесплатная", true),
            P("glm", "GLM (Z.ai) — бесплатно", has("zkey")),
            P("gemini", "Gemini — бесплатно", has("gkey")),
            P("openrouter", "OpenRouter — бесплатно", has("orkey")),
            P("local", "Локальный — офлайн", localReady),
            P("openai", "ChatGPT — платно", has("oakey")),
            P("claude", "Claude — платно", has("key")),
        )
        val items = ps.map { (if (it.id == provider()) "● " else "   ") + it.label + if (it.ready) "" else "  (нет ключа)" }
        AlertDialog.Builder(this).setTitle("Модель")
            .setItems(items.toTypedArray()) { _, i ->
                val p = ps[i]
                prefs.edit().putString("provider", p.id).apply()
                updateTop()
                if (!p.ready) settings()
            }
            .setNeutralButton("⚙ Ключи…") { _, _ -> settings() }
            .show()
    }

    private fun updateSend() {
        send.text = if (running) "Стоп" else "➤"
    }

    private fun onSend() {
        if (running) {
            cancel = true
            add(K_STATUS, "Останавливаю после текущего шага…")
            return
        }
        var text = input.text.toString().trim()
        if (text.isEmpty() && pending.isEmpty()) return
        if (text.isEmpty()) text = "Посмотри фото."
        val photos = pending.toList()
        pending.clear(); updateAttach()
        if (photos.isNotEmpty()) {
            text += "\n\n[Фото сохранены на сайте: " + photos.joinToString(", ") { it.first } + "]"
        }
        val key = currentKey()
        if (key.isBlank()) { settings(); return }
        if (!ServerService.phpRunning() || !ServerService.dbRunning()) {
            add(K_STATUS, "Подсказка: сервер не запущен. Правки файлов сработают, но для проверки сайта и SQL нажми «Запустить» на главном экране.")
        }
        input.setText("")
        if (chat.title.matches(Regex("Чат \\d+")) && transcript.none { it.first == K_USER }) {
            chat.title = text.lineSequence().first().take(24)
            updateTop()
        }
        add(K_USER, text)
        running = true
        cancel = false
        updateSend()
        val prov = provider()
        val model = when (prov) {
            "gemini" -> prefs.getString("gmodel", GeminiAgent.DEFAULT_MODEL) ?: GeminiAgent.DEFAULT_MODEL
            "glm" -> prefs.getString("zmodel", OpenAiAgent.ZAI_DEFAULT_MODEL) ?: OpenAiAgent.ZAI_DEFAULT_MODEL
            else -> prefs.getString("model", Agent.DEFAULT_MODEL) ?: Agent.DEFAULT_MODEL
        }
        val glmFast = prefs.getBoolean("zfast", true)
        val images = photos.map { it.second }
        Thread {
            try {
                val shot = SiteShot(this)
                val emit: (Agent.Event) -> Unit = { ev ->
                    when (ev) {
                        is Agent.Event.Text -> post(K_AI, ev.text)
                        is Agent.Event.Tool -> post(if (ev.error) K_TOOL_ERR else K_TOOL, ev.title + "\n" + ev.output.take(1500))
                        is Agent.Event.Status -> post(K_STATUS, ev.text)
                    }
                }
                val st = Stack(applicationContext)
                if (prov == "auto") {
                    runAuto(st, text, images, emit, shot)
                } else if (prov == "openai") {
                    val m = prefs.getString("oamodel", OpenAiAgent.OPENAI_DEFAULT_MODEL) ?: OpenAiAgent.OPENAI_DEFAULT_MODEL
                    OpenAiAgent(st, OpenAiAgent.OPENAI_BASE, key, m, vision = true, fast = false) { path -> shot.capture(path) }
                        .apply {
                            tokenField = "max_completion_tokens"
                            replaceModel = {
                                OpenAiAgent.pickOpenAi(OpenAiAgent.listOpenAi(key))
                                    ?.also { prefs.edit().putString("oamodel", it).apply() }
                            }
                        }
                        .run(openAiHistory, text, images, emit) { cancel }
                } else if (prov == "openrouter") {
                    val m = FreeModels.best(this, images.isNotEmpty())
                    if (m == null) post(K_STATUS, "Не удалось получить список бесплатных моделей OpenRouter. Проверь интернет.")
                    else {
                        post(K_STATUS, "Модель: ${m.name}")
                        OpenAiAgent(st, FreeModels.OPENROUTER_BASE, key, m.id, vision = m.vision, fast = false) { path -> shot.capture(path) }
                            .run(orHistories.getOrPut(m.id) { org.json.JSONArray() }, text, images, emit) { cancel }
                    }
                } else if (prov == "gemini") {
                    GeminiAgent(st, key, model, { path -> shot.capture(path) }) { m ->
                        prefs.edit().putString("gmodel", m).apply()
                    }.run(geminiHistory, text, images, emit) { cancel }
                } else if (prov == "local") {
                    val m = LocalLlm.selected(this)
                    val f = LocalLlm.file(this, m)
                    if (!f.exists()) {
                        post(K_STATUS, "Модель не скачана. Меню ⋮ → «Ключ и модель» → «Локальный ИИ» → «Скачать».")
                    } else {
                        LocalLlm.ensure(st, f) { post(K_STATUS, it) }
                        val extra = if (m.noThink) org.json.JSONObject().put("chat_template_kwargs",
                            org.json.JSONObject().put("enable_thinking", false)) else null
                        OpenAiAgent(st, LocalLlm.BASE, "local", m.id, vision = false, fast = false,
                            extraBody = extra, maxToolChars = 3000) { path -> shot.capture(path) }
                            .run(localHistory, text, images, emit) { cancel }
                    }
                } else if (prov == "glm") {
                    val vision = Regex("\\d+(\\.\\d+)?v").containsMatchIn(model) || "vision" in model
                    OpenAiAgent(st, OpenAiAgent.ZAI_BASE, key, model, vision, glmFast) { path -> shot.capture(path) }
                        .run(glmHistory, text, images, emit) { cancel }
                } else {
                    Agent(st, key, model) { path -> shot.capture(path) }
                        .run(history, text, images, emit) { cancel }
                }
            } catch (e: Exception) {
                post(K_STATUS, "Ошибка: ${e.message}")
            } finally {
                running = false
                post(K_STATUS, null)
            }
        }.start()
    }

    /** From the worker thread. Status "null" just refreshes the button. */
    private fun post(kind: Int, text: String?) {
        if (text != null) transcript += kind to text
        val l = listener
        if (l != null && text != null) l(kind, text) else runOnUiThread { updateSend() }
    }

    private fun add(kind: Int, text: String) {
        transcript += kind to text
        render(kind, text)
    }

    private fun render(kind: Int, text: String) {
        // Collapse consecutive progress statuses into one line.
        if (kind == K_STATUS && list.childCount > 0) {
            val last = list.getChildAt(list.childCount - 1)
            if (last.tag == K_STATUS && (text.startsWith("Шаг") || text == "Думаю…")) {
                (last as TextView).text = text
                return
            }
        }
        val tv = TextView(this).apply {
            this.text = text
            tag = kind
            setTextIsSelectable(true)
            setPadding(px(12), px(8), px(12), px(8))
            textSize = 15f
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = px(6)
        when (kind) {
            K_USER -> { tv.setBackgroundColor(0xFF1E88E5.toInt()); tv.setTextColor(0xFFFFFFFF.toInt()); lp.gravity = Gravity.END; lp.leftMargin = px(48) }
            K_AI -> { tv.setBackgroundColor(0x14000000); lp.rightMargin = px(24) }
            K_TOOL, K_TOOL_ERR -> {
                tv.typeface = Typeface.MONOSPACE
                tv.textSize = 11f
                tv.maxLines = 8
                tv.setBackgroundColor(if (kind == K_TOOL) 0xFF263238.toInt() else 0xFF5D1F1F.toInt())
                tv.setTextColor(0xFFE0E0E0.toInt())
                tv.setOnClickListener { tv.maxLines = if (tv.maxLines == 8) Int.MAX_VALUE else 8 }
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            }
            K_STATUS -> { tv.setTextColor(0xFF888888.toInt()); tv.textSize = 13f }
        }
        list.addView(tv, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun quickRow(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), 0, px(4), 0)
        }
        val quick = listOf(
            "🎨 Новый дизайн" to "Посмотри главную страницу и сделай витрину современнее: цвета, шрифты, кнопки, шапка. Покажи результат.",
            "🖼 Логотип из фото" to "Поставь присланное фото логотипом магазина.",
            "📦 Добавить товар" to "Добавь товар: название — , цена — , описание — . Фото — присланное.",
            "🏷 Название и контакты" to "Поменяй название магазина на « », телефон на , e-mail на , адрес на .",
            "👁 Как выглядит сайт" to "Посмотри главную страницу и скажи, что бы ты улучшил.",
            "↩️ Откатить" to "Откати последние изменения файлов из .bak-копий и очисти кеш.",
            "🧹 Кеш" to "Очисти кеш OpenCart.",
            "📚 Изучи ссылку" to "Изучи страницу  и запомни главное, что пригодится для моего магазина.",
            "🧠 Запомни" to "Запомни: ",
            "🇷🇺 Русский язык" to "Сделай витрину на русском: поставь русский язык по умолчанию (если пакета нет — переведи основные тексты витрины) и рубли валютой по умолчанию.",
        )
        for ((label, prompt) in quick) {
            row.addView(Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setOnClickListener {
                    input.setText(prompt)
                    val caret = when {
                        prompt.contains(" — ") -> prompt.indexOf(" — ") + 3
                        prompt.contains("страницу  ") -> prompt.indexOf("страницу  ") + 9
                        else -> prompt.length
                    }
                    input.setSelection(caret)
                    input.requestFocus()
                }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun updateAttach() {
        attachInfo.visibility = if (pending.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        attachInfo.text = "📎 Фото: ${pending.size} (нажми, чтобы убрать)"
    }

    private fun pickPhotos() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Выбери фото"), 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        Thread {
            for (u in uris.take(5)) {
                try { pending += savePhoto(u) } catch (e: Exception) {
                    runOnUiThread { add(K_STATUS, "Не удалось открыть фото: ${e.message}") }
                }
            }
            runOnUiThread { updateAttach() }
        }.start()
    }

    /** Downscale, fix rotation, save into the site's image folder; returns (site path, jpeg). */
    private fun savePhoto(uri: Uri): Pair<String, ByteArray> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1568) sample *= 2
        var bmp = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("не картинка")
        val rotation = try {
            contentResolver.openInputStream(uri)!!.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            }
        } catch (_: Exception) { 0f }
        val scale = minOf(1f, 1568f / maxOf(bmp.width, bmp.height))
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val jpg = out.toByteArray()
        val st = Stack(applicationContext)
        val dir = File(st.htdocs, "image/catalog/uploads").apply { mkdirs() }
        val name = "photo_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        File(dir, name).writeBytes(jpg)
        return "image/catalog/uploads/$name" to jpg
    }

    private fun provider() = prefs.getString("provider", "auto") ?: "auto"
    /** Tries free providers in turn and switches on errors or limits. */
    private fun runAuto(st: Stack, text: String, images: List<ByteArray>, emit: (Agent.Event) -> Unit, shot: SiteShot) {
        val vision = images.isNotEmpty()
        val cands = mutableListOf<Pair<String, () -> Boolean>>()
        val gkey = prefs.getString("gkey", "").orEmpty()
        val zkey = prefs.getString("zkey", "").orEmpty()
        val orkey = prefs.getString("orkey", "").orEmpty()

        val gemini: () -> Boolean = {
            GeminiAgent(st, gkey, prefs.getString("gmodel", GeminiAgent.DEFAULT_MODEL) ?: GeminiAgent.DEFAULT_MODEL,
                { p -> shot.capture(p) }) { m -> prefs.edit().putString("gmodel", m).apply() }
                .apply { retries = 1 }
                .run(geminiHistory, text, images, emit) { cancel }
        }
        val glm: () -> Boolean = {
            val model = if (vision) "glm-4.6v-flash"
                else prefs.getString("zmodel", OpenAiAgent.ZAI_DEFAULT_MODEL) ?: OpenAiAgent.ZAI_DEFAULT_MODEL
            val v = Regex("\\d+(\\.\\d+)?v").containsMatchIn(model)
            OpenAiAgent(st, OpenAiAgent.ZAI_BASE, zkey, model, v, prefs.getBoolean("zfast", true)) { p -> shot.capture(p) }
                .apply { retries = 1 }
                .run(glmHistory, text, images, emit) { cancel }
        }
        if (vision) {
            if (gkey.isNotBlank()) cands += "Gemini" to gemini
            if (zkey.isNotBlank()) cands += "GLM" to glm
        } else {
            if (zkey.isNotBlank()) cands += "GLM" to glm
            if (gkey.isNotBlank()) cands += "Gemini" to gemini
        }
        if (orkey.isNotBlank()) {
            post(K_STATUS, "🔎 Ищу лучшие бесплатные модели…")
            FreeModels.ranked(this, vision).take(3).forEach { m ->
                cands += "OpenRouter · ${m.name}" to {
                    OpenAiAgent(st, FreeModels.OPENROUTER_BASE, orkey, m.id, m.vision, false) { p -> shot.capture(p) }
                        .apply { retries = 0 }
                        .run(orHistories.getOrPut(m.id) { org.json.JSONArray() }, text, images, emit) { cancel }
                }
            }
        }
        val lm = LocalLlm.selected(this)
        if (prefs.getBoolean("auto_local", true) && LocalLlm.file(this, lm).exists()) {
            cands += "Локальный · ${lm.id}" to {
                LocalLlm.ensure(st, LocalLlm.file(this, lm)) { post(K_STATUS, it) }
                val extra = if (lm.noThink) org.json.JSONObject().put("chat_template_kwargs",
                    org.json.JSONObject().put("enable_thinking", false)) else null
                OpenAiAgent(st, LocalLlm.BASE, "local", lm.id, vision = false, fast = false,
                    extraBody = extra, maxToolChars = 3000) { p -> shot.capture(p) }
                    .run(localHistory, text, images, emit) { cancel }
            }
        }
        if (cands.isEmpty()) {
            post(K_STATUS, "Для «Авто» добавь хотя бы один бесплатный ключ (GLM, Gemini или OpenRouter) или скачай локальную модель: меню ⋮ → «Ключ и модель».")
            return
        }
        // Start with the provider that worked last time (keeps the conversation together).
        val last = prefs.getString("auto_last", null)
        val ordered = cands.sortedBy { if (it.first == last) 0 else 1 }
        for ((name, run) in ordered) {
            if (cancel) return
            post(K_STATUS, "🔄 $name")
            val ok = try { run() } catch (e: Exception) { post(K_STATUS, "Ошибка: ${e.message}"); false }
            if (ok) { prefs.edit().putString("auto_last", name).apply(); return }
            post(K_STATUS, "↪ $name не ответил — переключаюсь на следующую модель")
        }
        post(K_STATUS, "Все бесплатные модели сейчас недоступны (лимиты). Подожди пару минут и повтори.")
    }

    private fun isGemini() = provider() == "gemini"
    private fun providerName() = when (provider()) {
        "auto" -> "Авто"; "gemini" -> "Gemini"; "glm" -> "GLM"; "local" -> "Локальный"; "openrouter" -> "OpenRouter"; "openai" -> "ChatGPT"; else -> "Claude"
    }
    private fun currentKey() = when (provider()) {
        "local", "auto" -> provider()
        else -> prefs.getString(when (provider()) { "gemini" -> "gkey"; "glm" -> "zkey"; "openrouter" -> "orkey"; "openai" -> "oakey"; else -> "key" }, "").orEmpty()
    }
    private fun clear(a: org.json.JSONArray) { while (a.length() > 0) a.remove(a.length() - 1) }
    private fun clearGemini() = clear(geminiHistory)

    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        fun label(t: String, bold: Boolean = false) = TextView(this).apply {
            text = t
            if (bold) { setTypeface(typeface, Typeface.BOLD); setPadding(0, px(12), 0, 0) }
            box.addView(this)
        }
        fun keyField(pref: String, hint: String) = EditText(this).apply {
            this.hint = hint
            setText(prefs.getString(pref, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            box.addView(this)
        }

        label("Какой ИИ использовать", true)
        val prov = RadioGroup(this)
        val rbG = RadioButton(this).apply { text = "Google Gemini — бесплатно (с лимитами)"; id = 201 }
        val rbZ = RadioButton(this).apply { text = "GLM (Z.ai) — бесплатно, быстрый"; id = 203 }
        val rbC = RadioButton(this).apply { text = "Claude — платно, умнее"; id = 202 }
        val rbL = RadioButton(this).apply { text = "Локальный ИИ — на телефоне, офлайн, бесплатно"; id = 204 }
        val rbA = RadioButton(this).apply { text = "🔄 Авто — сам выбирает лучшую бесплатную и переключается (рекомендуется)"; id = 205 }
        val rbO = RadioButton(this).apply { text = "OpenRouter — лучшая бесплатная модель из каталога"; id = 206 }
        val rbP = RadioButton(this).apply { text = "ChatGPT (OpenAI API) — платно"; id = 207 }
        prov.addView(rbA); prov.addView(rbG); prov.addView(rbZ); prov.addView(rbO); prov.addView(rbL); prov.addView(rbP); prov.addView(rbC)
        prov.check(when (provider()) { "auto" -> 205; "gemini" -> 201; "glm" -> 203; "local" -> 204; "openrouter" -> 206; "openai" -> 207; else -> 202 })
        label("«Авто» использует все бесплатные ключи ниже и скачанную локальную модель: если одна упрётся в лимит, сразу переключится на другую.")
        val autoLocal = android.widget.CheckBox(this).apply {
            text = "В «Авто» использовать локальный ИИ, если облако недоступно"
            isChecked = prefs.getBoolean("auto_local", true)
            box.addView(this)
        }
        box.addView(prov)

        label("Google Gemini", true)
        label("Ключ: aistudio.google.com → Get API key (бесплатно)")
        val gkey = keyField("gkey", "AIza…")
        val gmodel = EditText(this).apply {
            hint = GeminiAgent.DEFAULT_MODEL
            setText(prefs.getString("gmodel", GeminiAgent.DEFAULT_MODEL))
            isSingleLine = true
        }
        val gRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gRow.addView(gmodel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gRow.addView(Button(this).apply {
            text = "Список"
            isAllCaps = false
            setOnClickListener {
                val k = gkey.text.toString().trim()
                if (k.isEmpty()) { android.widget.Toast.makeText(this@ChatActivity, "Сначала вставь ключ", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                Thread {
                    val r = runCatching { GeminiAgent.listModels(k) }
                    runOnUiThread {
                        r.onSuccess { ids ->
                            AlertDialog.Builder(this@ChatActivity).setTitle("Модели Gemini")
                                .setItems(ids.toTypedArray()) { _, i -> gmodel.setText(ids[i]) }.show()
                        }.onFailure {
                            android.widget.Toast.makeText(this@ChatActivity, "Не получилось: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        })
        label("Модель (бесплатны обычно модели Flash и Flash-Lite):")
        box.addView(gRow)

        label("GLM (Z.ai)", true)
        label("Ключ: z.ai → API Keys (бесплатные модели Flash)")
        val zkey = keyField("zkey", "ключ Z.ai")
        val zmodel = EditText(this).apply {
            hint = OpenAiAgent.ZAI_DEFAULT_MODEL
            setText(prefs.getString("zmodel", OpenAiAgent.ZAI_DEFAULT_MODEL))
            isSingleLine = true
        }
        val zRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zRow.addView(zmodel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        zRow.addView(Button(this).apply {
            text = "Выбрать"
            isAllCaps = false
            setOnClickListener {
                val ids = arrayOf("glm-4.7-flash", "glm-4.6v-flash", "glm-4.5-flash")
                val names = arrayOf("glm-4.7-flash — бесплатно, текст", "glm-4.6v-flash — бесплатно, видит фото и скриншоты", "glm-4.5-flash — бесплатно, старее")
                AlertDialog.Builder(this@ChatActivity).setTitle("Модели GLM")
                    .setItems(names) { _, i -> zmodel.setText(ids[i]) }.show()
            }
        })
        label("Модель:")
        box.addView(zRow)
        val zfast = android.widget.CheckBox(this).apply {
            text = "Быстрый режим (без долгих размышлений)"
            isChecked = prefs.getBoolean("zfast", true)
            box.addView(this)
        }

        label("OpenRouter (каталог бесплатных моделей)", true)
        label("Ключ: openrouter.ai → Keys (бесплатно, у бесплатных моделей дневной лимит)")
        val orkey = keyField("orkey", "sk-or-…")
        val orInfo = TextView(this).apply {
            val pin = prefs.getString("ormodel", "").orEmpty()
            text = "Модель: " + pin.ifEmpty { "авто — всегда лучшая бесплатная" }
            box.addView(this)
        }
        box.addView(Button(this).apply {
            text = "🔎 Найти лучшие бесплатные модели"
            isAllCaps = false
            setOnClickListener {
                text = "Ищу…"
                Thread {
                    val r = runCatching { FreeModels.openRouter(this@ChatActivity, force = true) }
                    runOnUiThread {
                        text = "🔎 Найти лучшие бесплатные модели"
                        r.onSuccess { list ->
                            val top = list.take(25)
                            val items = (listOf("⭐ Авто — всегда лучшая") + top.mapIndexed { i, m ->
                                "${i + 1}. ${m.name} · ${m.context / 1000}K" + if (m.vision) " · 👁" else ""
                            }).toTypedArray()
                            AlertDialog.Builder(this@ChatActivity)
                                .setTitle("Бесплатные модели с инструментами: ${list.size}")
                                .setItems(items) { _, i ->
                                    val id = if (i == 0) "" else top[i - 1].id
                                    prefs.edit().putString("ormodel", id).apply()
                                    orInfo.text = "Модель: " + id.ifEmpty { "авто — всегда лучшая бесплатная" }
                                }.show()
                        }.onFailure {
                            android.widget.Toast.makeText(this@ChatActivity, "Не получилось: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        })

        label("Локальный ИИ", true)
        label("Память телефона: %.1f ГБ (без «расширения»). Модель скачивается один раз.".format(LocalLlm.totalRamGb(this)))
        val lgroup = RadioGroup(this)
        val lcur = LocalLlm.selected(this).id
        LocalLlm.MODELS.forEachIndexed { i, m ->
            val have = LocalLlm.file(this, m).exists()
            lgroup.addView(RadioButton(this).apply {
                text = m.title + if (have) "  ✓ скачана" else ""
                id = 300 + i
                isChecked = m.id == lcur
            })
        }
        box.addView(lgroup)
        val lRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lRow.addView(Button(this).apply {
            text = "Скачать"
            isAllCaps = false
            setOnClickListener {
                val m = LocalLlm.MODELS[(lgroup.checkedRadioButtonId - 300).coerceIn(0, LocalLlm.MODELS.lastIndex)]
                downloadModel(m)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lRow.addView(Button(this).apply {
            text = "Удалить модели"
            isAllCaps = false
            setOnClickListener {
                LocalLlm.stop()
                LocalLlm.dir(this@ChatActivity).listFiles()?.forEach { it.delete() }
                android.widget.Toast.makeText(this@ChatActivity, "Модели удалены", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(lRow)

        label("ChatGPT (OpenAI API)", true)
        label("Ключ: platform.openai.com → API keys. Платно, отдельно от подписки ChatGPT.")
        val oakey = keyField("oakey", "sk-…")
        val oamodel = EditText(this).apply {
            hint = OpenAiAgent.OPENAI_DEFAULT_MODEL
            setText(prefs.getString("oamodel", OpenAiAgent.OPENAI_DEFAULT_MODEL))
            isSingleLine = true
        }
        val oRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        oRow.addView(oamodel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        oRow.addView(Button(this).apply {
            text = "Список"
            isAllCaps = false
            setOnClickListener {
                val k = oakey.text.toString().trim()
                if (k.isEmpty()) { android.widget.Toast.makeText(this@ChatActivity, "Сначала вставь ключ", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                Thread {
                    val r = runCatching { OpenAiAgent.listOpenAi(k) }
                    runOnUiThread {
                        r.onSuccess { ids ->
                            AlertDialog.Builder(this@ChatActivity).setTitle("Модели OpenAI")
                                .setItems(ids.toTypedArray()) { _, i -> oamodel.setText(ids[i]) }.show()
                        }.onFailure {
                            android.widget.Toast.makeText(this@ChatActivity, "Не получилось: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        })
        label("Модель (mini — дешевле):")
        box.addView(oRow)

        label("Claude", true)
        label("Ключ: console.anthropic.com → API Keys (нужен баланс)")
        val ckey = keyField("key", "sk-ant-…")
        val models = listOf(
            "claude-opus-5-5" to "Opus 5.5 — умнее",
            "claude-sonnet-5-5" to "Sonnet 5.5 — дешевле и быстрее",
        )
        val group = RadioGroup(this)
        val current = prefs.getString("model", Agent.DEFAULT_MODEL)
        models.forEachIndexed { i, (id, label) ->
            group.addView(RadioButton(this).apply { text = label; this.id = 100 + i; isChecked = id == current })
        }
        box.addView(group)
        label("Ключи хранятся только на этом телефоне.")

        AlertDialog.Builder(this)
            .setTitle("Ключ и модель")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                val idx = (group.checkedRadioButtonId - 100).coerceIn(0, models.lastIndex)
                val newProv = when (prov.checkedRadioButtonId) {
                    202 -> "claude"; 203 -> "glm"; 204 -> "local"; 205 -> "auto"; 206 -> "openrouter"; 207 -> "openai"; else -> "gemini"
                }
                val lm = LocalLlm.MODELS[(lgroup.checkedRadioButtonId - 300).coerceIn(0, LocalLlm.MODELS.lastIndex)]
                prefs.edit().putString("lmodel", lm.id).apply()
                prefs.edit()
                    .putString("provider", newProv)
                    .putString("gkey", gkey.text.toString().trim())
                    .putString("gmodel", gmodel.text.toString().trim().ifEmpty { GeminiAgent.DEFAULT_MODEL })
                    .putString("zkey", zkey.text.toString().trim())
                    .putString("orkey", orkey.text.toString().trim())
                    .putString("oakey", oakey.text.toString().trim())
                    .putString("oamodel", oamodel.text.toString().trim().ifEmpty { OpenAiAgent.OPENAI_DEFAULT_MODEL })
                    .putBoolean("auto_local", autoLocal.isChecked)
                    .putString("zmodel", zmodel.text.toString().trim().ifEmpty { OpenAiAgent.ZAI_DEFAULT_MODEL })
                    .putBoolean("zfast", zfast.isChecked)
                    .putString("key", ckey.text.toString().trim())
                    .putString("model", models[idx].first)
                    .apply()
                updateTop()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadModel(m: LocalLlm.Model) {
        if (LocalLlm.file(this, m).exists()) {
            android.widget.Toast.makeText(this, "Уже скачана", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        var stop = false
        @Suppress("DEPRECATION")
        val pd = android.app.ProgressDialog(this).apply {
            setTitle("Скачиваю ${m.title}")
            setMessage("Лучше по Wi-Fi. Можно прервать и продолжить потом.")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Прервать") { _, _ -> stop = true }
            show()
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val r = runCatching {
                LocalLlm.download(this, m, { have, total ->
                    val t = if (total > 0) total else m.approxBytes
                    runOnUiThread {
                        pd.progress = (have * 100 / t).toInt().coerceIn(0, 100)
                        pd.setMessage("%.0f / %.0f МБ".format(have / 1e6, t / 1e6))
                    }
                }) { stop }
            }
            runOnUiThread {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                pd.dismiss()
                val msg = r.fold({ "Модель скачана ✅ Выбери «Локальный ИИ» и сохрани." }, { "Не скачалась: ${it.message}" })
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun memoryDialog() {
        val k = Knowledge(Stack(applicationContext))
        val et = EditText(this).apply {
            setText(k.memory())
            hint = "Пока пусто. ИИ сам будет записывать сюда полезное."
            textSize = 13f
            minLines = 8
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("🧠 Память ИИ")
            .setMessage("Это ИИ видит в каждом чате. Можно править руками.")
            .setView(ScrollView(this).apply { setPadding(px(16), 0, px(16), 0); addView(et) })
            .setPositiveButton("Сохранить") { _, _ -> k.setMemory(et.text.toString().trimEnd() + "\n") }
            .setNeutralButton("Очистить") { _, _ -> k.setMemory("") }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun kbDialog() {
        val k = Knowledge(Stack(applicationContext))
        val files = k.files()
        val items = files.map { "${it.name}  (${it.length() / 1024} КБ)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📚 База знаний")
            .setItems(if (items.isEmpty()) arrayOf("(пусто)") else items) { _, i ->
                val f = files.getOrNull(i) ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle(f.name)
                    .setMessage(f.readText().take(3000))
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Удалить") { _, _ -> f.delete() }
                    .show()
            }
            .setPositiveButton("+ Добавить по ссылке") { _, _ -> addUrlDialog(k) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun addUrlDialog(k: Knowledge) {
        val et = EditText(this).apply {
            hint = "https://…"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Изучить страницу")
            .setView(LinearLayout(this).apply { setPadding(px(20), 0, px(20), 0); addView(et) })
            .setPositiveButton("Скачать") { _, _ ->
                val url = et.text.toString().trim()
                add(K_STATUS, "Скачиваю $url в базу знаний…")
                Thread {
                    val (_, out, err) = k.learnUrl(url)
                    post(if (err) K_TOOL_ERR else K_TOOL, "🌐 $url\n" + out.take(600))
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun px(v: Int) = (v * dp).toInt()
}
