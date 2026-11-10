package com.vaila.launcher

import android.app.Activity
import android.app.AlertDialog
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
        // Survives activity recreation (rotation) while the process lives.
        val history = mutableListOf<MessageParam>()
        val transcript = mutableListOf<Pair<Int, String>>() // kind, text
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
    private val prefs by lazy { getSharedPreferences("ai", MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ИИ-мастер"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send)
        root.addView(bar)
        setContentView(root)

        if (transcript.isEmpty()) {
            transcript += K_STATUS to ("Пиши, что поменять на сайте: дизайн, цвета, тексты, товары, настройки. " +
                "Я сам правлю файлы и базу прямо на телефоне. Перед большими правками делаю .bak-копии.")
        }
        if (prefs.getString("key", "").isNullOrBlank()) settings()
    }

    override fun onResume() {
        super.onResume()
        // Re-render everything: events may have arrived while paused.
        list.removeAllViews()
        transcript.toList().forEach { render(it.first, it.second) }
        updateSend()
        listener = { k, t -> runOnUiThread { render(k, t); updateSend() } }
    }

    override fun onPause() {
        listener = null
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Новый чат")
        menu.add(0, 2, 1, "Ключ и модель")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> if (!running) {
                history.clear(); transcript.clear(); list.removeAllViews()
                add(K_STATUS, "Новый чат.")
            }
            2 -> settings()
        }
        return true
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
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) { settings(); return }
        if (!ServerService.phpRunning() || !ServerService.dbRunning()) {
            add(K_STATUS, "Подсказка: сервер не запущен. Правки файлов сработают, но для проверки сайта и SQL нажми «Запустить» на главном экране.")
        }
        input.setText("")
        add(K_USER, text)
        running = true
        cancel = false
        updateSend()
        val model = prefs.getString("model", Agent.DEFAULT_MODEL) ?: Agent.DEFAULT_MODEL
        Thread {
            try {
                Agent(Stack(applicationContext), key, model).run(history, text, { ev ->
                    when (ev) {
                        is Agent.Event.Text -> post(K_AI, ev.text)
                        is Agent.Event.Tool -> post(if (ev.error) K_TOOL_ERR else K_TOOL, ev.title + "\n" + ev.output.take(1500))
                        is Agent.Event.Status -> post(K_STATUS, ev.text)
                    }
                }, { cancel })
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

    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        val key = EditText(this).apply {
            hint = "sk-ant-…"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        box.addView(TextView(this).apply { text = "API-ключ Anthropic (console.anthropic.com → API Keys). Хранится только на этом телефоне." })
        box.addView(key)
        val models = listOf(
            "claude-opus-5-5" to "Opus 5.5 — умнее (рекомендуется)",
            "claude-sonnet-5-5" to "Sonnet 5.5 — дешевле и быстрее",
        )
        val group = RadioGroup(this)
        val current = prefs.getString("model", Agent.DEFAULT_MODEL)
        models.forEachIndexed { i, (id, label) ->
            group.addView(RadioButton(this).apply { text = label; this.id = 100 + i; isChecked = id == current })
        }
        box.addView(group)
        AlertDialog.Builder(this)
            .setTitle("Ключ и модель")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val idx = (group.checkedRadioButtonId - 100).coerceIn(0, models.lastIndex)
                prefs.edit()
                    .putString("key", key.text.toString().trim())
                    .putString("model", models[idx].first)
                    .apply()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun px(v: Int) = (v * dp).toInt()
}
