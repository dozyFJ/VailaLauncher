package com.vaila.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var phpView: TextView
    private lateinit var dbView: TextView
    private lateinit var lanBox: CheckBox
    private lateinit var lanView: TextView
    private lateinit var installBtn: Button

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        phpView = findViewById(R.id.php_state)
        dbView = findViewById(R.id.db_state)
        lanBox = findViewById(R.id.lan)
        lanView = findViewById(R.id.lan_url)
        installBtn = findViewById(R.id.open_install)

        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        lanBox.isChecked = prefs.getBoolean("lan", false)
        lanBox.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("lan", v).apply() }

        findViewById<Button>(R.id.start).setOnClickListener { ServerService.start(this, lanBox.isChecked) }
        findViewById<Button>(R.id.stop).setOnClickListener { ServerService.stop(this) }
        findViewById<Button>(R.id.open_site).setOnClickListener { open("/") }
        findViewById<Button>(R.id.open_admin).setOnClickListener { open("/admin/") }
        installBtn.setOnClickListener { open("/install/") }
        findViewById<Button>(R.id.auto_install).setOnClickListener { autoInstallDialog() }
        findViewById<Button>(R.id.logs).setOnClickListener { showLogs() }
        findViewById<Button>(R.id.ai).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        findViewById<Button>(R.id.terminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.remove_install).setOnClickListener { removeInstall() }
        findViewById<Button>(R.id.reset).setOnClickListener { confirmReset() }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val php = ServerService.phpRunning()
        val db = ServerService.dbRunning()
        statusView.text = ServerService.status
        phpView.text = if (php) "● PHP — порт ${Stack.HTTP_PORT}" else "○ PHP — выключен"
        dbView.text = if (db) "● MariaDB — порт ${Stack.MYSQL_PORT}" else "○ MariaDB — выключена"
        val green = 0xFF2E7D32.toInt()
        val grey = 0xFF888888.toInt()
        phpView.setTextColor(if (php) green else grey)
        dbView.setTextColor(if (db) green else grey)
        val ip = if (php && ServerService.lan) wifiIp() else null
        lanView.text = if (ip != null) "В сети: http://$ip:${Stack.HTTP_PORT}" else ""
        installBtn.isEnabled = File(Stack(this).htdocs, "install").exists() || !Stack(this).htdocs.exists()
    }

    private fun open(path: String) {
        if (!ServerService.phpRunning()) {
            Toast.makeText(this, "Сначала нажми «Запустить»", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${Stack.HTTP_PORT}$path")))
        } catch (e: Exception) {
            Toast.makeText(this, "Нет браузера: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLogs() {
        val tv = TextView(this).apply {
            text = Stack(this@MainActivity).logsText()
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("Логи").setView(sv)
            .setPositiveButton("OK", null).show()
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun autoInstallDialog() {
        if (!ServerService.dbRunning() || !ServerService.phpRunning()) {
            Toast.makeText(this, "Сначала нажми «Запустить» и дождись, пока загорятся PHP и MariaDB", Toast.LENGTH_LONG).show()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, type: Int) = android.widget.EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            box.addView(this)
        }
        val t = android.text.InputType.TYPE_CLASS_TEXT
        val login = field("Логин админки", "admin", t)
        val pass = field("Пароль админки (мин. 4 символа)", "", t or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val mail = field("E-mail", "admin@example.com", t or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        AlertDialog.Builder(this)
            .setTitle("Автоустановка OpenCart")
            .setMessage("База, пользователь БД и настройки создадутся сами. Текущая база opencart будет очищена.")
            .setView(box)
            .setPositiveButton("Установить") { _, _ ->
                val l = login.text.toString().trim()
                val p = pass.text.toString()
                val m = mail.text.toString().trim()
                if (l.length < 3 || p.length < 4 || !m.contains("@")) {
                    Toast.makeText(this, "Логин от 3 символов, пароль от 4, нормальный e-mail", Toast.LENGTH_LONG).show()
                } else {
                    runAutoInstall(l, p, m)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runAutoInstall(login: String, pass: String, mail: String) {
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Подготовка…")
            setCancelable(false)
            show()
        }
        Thread {
            val result = try {
                val out = Stack(this).autoInstall(login, pass, mail) { msg -> runOnUiThread { progress.setMessage(msg) } }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess {
                    AlertDialog.Builder(this)
                        .setTitle("OpenCart установлен ✅")
                        .setMessage("Админка: http://127.0.0.1:${Stack.HTTP_PORT}/admin/\nЛогин: $login\nПароль: $pass\n\nПапка install удалена.")
                        .setPositiveButton("Открыть админку") { _, _ -> open("/admin/") }
                        .setNegativeButton("Магазин") { _, _ -> open("/") }
                        .show()
                }.onFailure {
                    AlertDialog.Builder(this)
                        .setTitle("Не получилось")
                        .setMessage(it.message ?: it.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun removeInstall() {
        val dir = File(Stack(this).htdocs, "install")
        if (!dir.exists()) {
            Toast.makeText(this, "Папки install уже нет", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Удалить папку install? Делай это только после установки OpenCart.")
            .setPositiveButton("Удалить") { _, _ ->
                dir.deleteRecursively()
                Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Сбросить всё?")
            .setMessage("Будут удалены файлы магазина и вся база данных. OpenCart распакуется заново при следующем запуске.")
            .setPositiveButton("Сбросить") { _, _ ->
                if (ServerService.phpRunning() || ServerService.dbRunning()) {
                    Toast.makeText(this, "Сначала останови сервер", Toast.LENGTH_LONG).show()
                } else {
                    Stack(this).resetAll()
                    Toast.makeText(this, "Сброшено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Уже разрешено. На Realme ещё включи «Автозапуск» и «Работа в фоне» в настройках приложения.", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun wifiIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress
    } catch (_: Exception) {
        null
    }
}
