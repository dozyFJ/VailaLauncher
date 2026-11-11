package com.vaila.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/** Built-in terminal: /system/bin/sh with our php / mariadb on PATH. */
class TerminalActivity : Activity() {

    private lateinit var view: TerminalView
    private var session: TerminalSession? = null
    private var ctrl = false
    private var alt = false
    private lateinit var ctrlBtn: Button
    private lateinit var altBtn: Button
    private var fontSize = 28

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val st = Stack(this)
        try {
            st.prepare { }
        } catch (_: Exception) {
        }

        view = TerminalView(this, null).apply {
            setTerminalViewClient(ViewClient())
            setTextSize(fontSize)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF000000.toInt())
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(extraKeys())
        setContentView(root)
        actionBar?.hide()

        session = TerminalSession(
            "/system/bin/sh",
            st.htdocs.path.takeIf { File(it).isDirectory } ?: st.base.path,
            arrayOf("-sh"),
            env(st),
            3000,
            SessionClient()
        )
        view.attachSession(session)
        view.requestFocus()
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        super.onDestroy()
    }

    private fun env(st: Stack): Array<String> {
        val home = File(st.base, "home").apply { mkdirs() }
        val rc = File(home, ".shrc")
        rc.writeText(
            """
            export PS1='shop:${'$'}{PWD##*/} ${'$'} '
            alias mysql='mariadb -h 127.0.0.1 -P ${Stack.MYSQL_PORT} -u root'
            alias mariadb='mariadb -h 127.0.0.1 -P ${Stack.MYSQL_PORT} -u root'
            alias php='php -c ${st.phpIni.path}'
            alias ll='ls -la'
            alias www='cd ${st.htdocs.path}'
            echo "PocketShop terminal. www = папка сайта, mysql = консоль базы, php -v"
            """.trimIndent() + "\n"
        )
        return st.shellEnv() + arrayOf("ENV=${rc.path}", "COLORTERM=truecolor")
    }

    private fun send(s: String) {
        session?.write(s)
    }

    private fun extraKeys(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF202020.toInt())
        }
        fun key(label: String, action: (Button) -> Unit): Button =
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                setTextColor(0xFFFFFFFF.toInt())
                minWidth = 0
                minimumWidth = 0
                setPadding(28, 0, 28, 0)
                gravity = Gravity.CENTER
                setOnClickListener { action(this) }
                row.addView(this)
            }
        key("ESC") { send("\u001b") }
        ctrlBtn = key("CTRL") { ctrl = !ctrl; updateMods() }
        altBtn = key("ALT") { alt = !alt; updateMods() }
        key("TAB") { send("\t") }
        key("←") { send("\u001b[D") }
        key("↓") { send("\u001b[B") }
        key("↑") { send("\u001b[A") }
        key("→") { send("\u001b[C") }
        key("-") { send("-") }
        key("/") { send("/") }
        key("|") { send("|") }
        key("~") { send("~") }
        key("A+") { fontSize = (fontSize + 4).coerceAtMost(72); view.setTextSize(fontSize) }
        key("A-") { fontSize = (fontSize - 4).coerceAtLeast(12); view.setTextSize(fontSize) }
        key("⌨") { showKeyboard() }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun updateMods() {
        ctrlBtn.setTextColor(if (ctrl) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
        altBtn.setTextColor(if (alt) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun showKeyboard() {
        view.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
    }

    private inner class SessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = view.onScreenUpdated()
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) = finish()
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(this@TerminalActivity)?.toString() ?: return
            view.mEmulator?.paste(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                fontSize = (fontSize * scale).toInt().coerceIn(12, 72)
                view.setTextSize(fontSize)
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_ENTER && session?.isRunning != true) {
                finish(); return true
            }
            return false
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey(): Boolean {
            val v = ctrl
            if (v) { ctrl = false; runOnUiThread { updateMods() } }
            return v
        }
        override fun readAltKey(): Boolean {
            val v = alt
            if (v) { alt = false; runOnUiThread { updateMods() } }
            return v
        }
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}
