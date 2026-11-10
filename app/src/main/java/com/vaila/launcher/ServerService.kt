package com.vaila.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/** Foreground service that owns the php and mariadbd processes. */
class ServerService : Service() {

    companion object {
        const val ACTION_START = "com.vaila.launcher.START"
        const val ACTION_STOP = "com.vaila.launcher.STOP"
        const val EXTRA_LAN = "lan"
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1

        @Volatile var php: Process? = null
        @Volatile var db: Process? = null
        @Volatile var status: String = "Остановлено"
        @Volatile var busy = false
        @Volatile var lan = false

        fun phpRunning() = php?.isAlive == true
        fun dbRunning() = db?.isAlive == true

        fun start(ctx: Context, lan: Boolean) {
            ctx.startForegroundService(
                Intent(ctx, ServerService::class.java).setAction(ACTION_START).putExtra(EXTRA_LAN, lan)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification("Запуск…"))
        if (intent?.action == ACTION_STOP) {
            Thread {
                stopAll()
                stopForeground(true)
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }
        val wantLan = intent?.getBooleanExtra(EXTRA_LAN, false) ?: false
        if (!busy) {
            busy = true
            Thread { startAll(wantLan) }.start()
        }
        return START_NOT_STICKY
    }

    private fun setStatus(s: String) {
        status = s
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s))
    }

    private fun startAll(wantLan: Boolean) {
        val st = Stack(this)
        try {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaila:server").apply { acquire() }
            }
            // Restart PHP if the LAN setting changed.
            if (phpRunning() && lan != wantLan) {
                php?.destroy(); php?.waitFor(); php = null
            }
            if (!phpRunning() && !dbRunning()) st.killStale()

            st.prepare(::setStatus)

            if (!dbRunning()) {
                if (!st.mysqlInitialized()) {
                    setStatus("Первый запуск MariaDB (создание базы)…")
                    st.initMysql()
                }
                setStatus("Запуск MariaDB…")
                st.log("starting mariadbd")
                db = st.startMysql()
                waitPort(Stack.MYSQL_PORT, db!!, 90, "MariaDB", File(st.logs, "mysql.log"))
            }
            if (!phpRunning()) {
                setStatus("Запуск PHP…")
                st.log("starting php lan=$wantLan")
                php = st.startPhp(wantLan)
                lan = wantLan
                waitPort(Stack.HTTP_PORT, php!!, 20, "PHP", File(st.logs, "php_server.log"))
            }
            setStatus("Работает: http://127.0.0.1:${Stack.HTTP_PORT}")
        } catch (e: Exception) {
            st.log("error: $e")
            setStatus("Ошибка: ${e.message}")
        } finally {
            busy = false
        }
    }

    private fun waitPort(port: Int, p: Process, seconds: Int, name: String, log: File) {
        repeat(seconds * 4) {
            if (Stack.portOpen(port)) return
            if (!p.isAlive) {
                throw IllegalStateException(
                    "$name завершился (код ${p.exitValue()})\n" + Stack.tail(log, 1500)
                )
            }
            Thread.sleep(250)
        }
        throw IllegalStateException("$name не открыл порт $port за $seconds с")
    }

    private fun stopAll() {
        busy = true
        setStatus("Остановка…")
        val st = Stack(this)
        php?.let { it.destroy(); it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
        db?.let {
            it.destroy() // SIGTERM: clean InnoDB shutdown
            if (!it.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
        php = null
        db = null
        st.killStale()
        st.log("stopped")
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        status = "Остановлено"
        busy = false
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Сервер", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text.lineSequence().first())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Стоп", stop
                ).build()
            )
            .build()
    }
}
