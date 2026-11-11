package com.vaila.launcher

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Local AI: llama.cpp server running a downloaded GGUF model on the phone. */
object LocalLlm {
    const val PORT = 8081
    const val BASE = "http://127.0.0.1:$PORT/v1"
    const val CONTEXT = 6144

    data class Model(val id: String, val title: String, val url: String, val approxBytes: Long, val noThink: Boolean)

    val MODELS = listOf(
        Model(
            "qwen3-1.7b", "Qwen3 1.7B — быстрая (≈1.1 ГБ)",
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            1_100_000_000L, noThink = true,
        ),
        Model(
            "qwen3-4b", "Qwen3 4B Instruct — умнее, медленнее (≈2.5 ГБ)",
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            2_500_000_000L, noThink = false,
        ),
    )

    @Volatile private var proc: Process? = null
    @Volatile private var loaded: String? = null

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: Model) = File(dir(ctx), m.id + ".gguf")
    fun selected(ctx: Context): Model {
        val id = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE).getString("lmodel", MODELS[0].id)
        return MODELS.firstOrNull { it.id == id } ?: MODELS[0]
    }

    fun totalRamGb(ctx: Context): Double {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }

    /** Downloads with resume support into models/<id>.gguf. */
    fun download(ctx: Context, m: Model, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val dest = file(ctx, m)
        val part = File(dest.path + ".part")
        var url = URL(m.url)
        var have = if (part.exists()) part.length() else 0L
        var redirects = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "PocketShop/1.0")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Редирект без адреса")
                url = URL(url, loc)
                if (++redirects > 8) throw IOException("Слишком много редиректов")
                continue
            }
            if (code == 416) break // already complete
            if (code != 200 && code != 206) throw IOException("HTTP $code при скачивании модели")
            if (code == 200) have = 0 // server ignored Range
            val total = have + c.contentLengthLong.coerceAtLeast(0)
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(have)
                raf.seek(have)
                c.inputStream.use { inp ->
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException("Отменено")
                        val n = inp.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        have += n
                        if (have - last > 4_000_000) { progress(have, total); last = have }
                    }
                }
            }
            progress(have, total)
            break
        }
        if (part.length() < 50_000_000) throw IOException("Файл модели слишком маленький, скачивание не удалось")
        if (!part.renameTo(dest)) throw IOException("Не удалось сохранить модель")
    }

    private fun healthy(): Int = try {
        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1000
        c.readTimeout = 2000
        c.responseCode
    } catch (_: Exception) { -1 }

    /** Starts llama-server with [model] if it is not already serving it. */
    fun ensure(st: Stack, model: File, status: (String) -> Unit) {
        if (proc?.isAlive == true && loaded == model.path && healthy() == 200) return
        stop()
        val bin = File(st.bin, "llama-server")
        if (!bin.exists()) throw IOException("В этой сборке нет llama-server")
        val log = File(st.logs, "llm.log")
        Stack.trimLog(log)
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        status("Загружаю модель в память… (первый раз до пары минут)")
        val p = st.env(
            ProcessBuilder(
                bin.path, "-m", model.path,
                "--host", "127.0.0.1", "--port", PORT.toString(),
                "-c", CONTEXT.toString(), "-t", threads.toString(),
                "--jinja",
            )
        ).directory(st.lib) // ggml looks for backend libs next to cwd
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        proc = p
        val until = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < until) {
            if (!p.isAlive) throw IOException("Локальный ИИ не запустился (код ${p.exitValue()}):\n" + Stack.tail(log, 1200))
            if (healthy() == 200) { loaded = model.path; return }
            Thread.sleep(1000)
        }
        stop()
        throw IOException("Модель не загрузилась за 5 минут. Не хватает памяти? Попробуй маленькую модель.")
    }

    fun stop() {
        proc?.let { it.destroy(); runCatching { it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) } }
        proc = null
        loaded = null
    }
}
