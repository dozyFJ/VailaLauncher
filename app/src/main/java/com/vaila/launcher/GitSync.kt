package com.vaila.launcher

import android.util.Base64
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Syncs the site with a GitHub repository using the bundled git.
 * Repo layout: htdocs/ (site files) and db/opencart.sql (database dump).
 * The git dir lives outside the web root so it is never served.
 */
class GitSync(private val st: Stack, repo: String, private val token: String) {
    private val url = normalize(repo)
    private val gitDir = File(st.base, "git")
    private val log = StringBuilder()

    private fun normalize(r: String): String {
        val t = r.trim().removeSuffix("/").removeSuffix(".git")
        val path = when {
            t.startsWith("https://github.com/") -> t.removePrefix("https://github.com/")
            t.startsWith("github.com/") -> t.removePrefix("github.com/")
            else -> t
        }
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(path)) throw IOException("Укажи репозиторий как владелец/имя, например dozyFJ/myshop")
        return "https://github.com/$path.git"
    }

    private fun git(vararg args: String, check: Boolean = true): Pair<Int, String> {
        val auth = Base64.encodeToString("x-access-token:$token".toByteArray(), Base64.NO_WRAP)
        val cmd = listOf(
            File(st.bin, "git").path,
            "--git-dir=${gitDir.path}", "--work-tree=${st.base.path}",
            "-c", "http.extraHeader=Authorization: Basic $auth",
            "-c", "user.name=PocketShop", "-c", "user.email=pocketshop@users.noreply.github.com",
            "-c", "core.autocrlf=false", "-c", "core.fileMode=false",
        ) + args
        val pb = ProcessBuilder(cmd).directory(st.base).redirectErrorStream(true)
        pb.environment().clear()
        st.shellEnv().forEach { kv -> val i = kv.indexOf('='); pb.environment()[kv.substring(0, i)] = kv.substring(i + 1) }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        // Never show the token, even if git echoes the header.
        val clean = out.replace(token, "***").replace(auth, "***")
        log.append("$ git ${args.joinToString(" ")}\n$clean\n")
        if (check && code != 0) throw IOException("git ${args.firstOrNull()} завершился с кодом $code:\n" + clean.takeLast(800))
        return code to clean
    }

    private fun ensureRepo() {
        if (!File(st.bin, "git").exists()) throw IOException("В этой сборке нет git")
        if (!File(gitDir, "HEAD").exists()) {
            git("init", "-q", "-b", "main")
        }
        File(st.base, ".gitignore").writeText(
            """
            # Only the site and the database dump are synced
            /*
            !/.gitignore
            !/htdocs/
            !/db/
            /htdocs/system/storage/cache/*
            !/htdocs/system/storage/cache/index.html
            /htdocs/system/storage/logs/*
            /htdocs/system/storage/session/*
            /htdocs/image/cache/*
            *.bak
            """.trimIndent() + "\n"
        )
        val (_, remotes) = git("remote", check = false)
        if ("origin" in remotes.lines()) git("remote", "set-url", "origin", url) else git("remote", "add", "origin", url)
    }

    private fun dumpDb() {
        val dir = File(st.base, "db").apply { mkdirs() }
        val out = File(dir, "opencart.sql")
        val p = st.env(ProcessBuilder(File(st.bin, "mariadb-dump").path, "-h", "127.0.0.1", "-P", Stack.MYSQL_PORT.toString(),
            "-u", "root", "--single-transaction", "--skip-dump-date", "--skip-extended-insert", Stack.DB_NAME))
            .redirectOutput(out).redirectError(File(st.logs, "git-dump.log")).start()
        if (p.waitFor() != 0) throw IOException("Не удалось выгрузить базу: " + Stack.tail(File(st.logs, "git-dump.log"), 500))
    }

    private fun remoteHasMain(): Boolean = git("ls-remote", "--heads", "origin", "main", check = false).second.contains("refs/heads/main")

    private fun commitLocal(): Boolean {
        git("add", "-A")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val (code, _) = git("commit", "-q", "-m", "Синхронизация с телефона $stamp", check = false)
        return code == 0
    }

    /** Uploads the current site + database to GitHub. */
    fun push(progress: (String) -> Unit): String {
        ensureRepo()
        progress("Выгружаю базу…"); dumpDb()
        progress("Готовлю файлы…"); val changed = commitLocal()
        progress("Отправляю на GitHub…")
        if (remoteHasMain()) {
            git("fetch", "-q", "origin", "main")
            // First sync into a repo that already has commits (e.g. a README): join histories.
            val (c, _) = git("merge", "-q", "--no-edit", "--allow-unrelated-histories", "-X", "ours", "origin/main", check = false)
            if (c != 0) throw IOException("Не удалось объединить с GitHub. Подробности — в логах (git.log).")
        }
        git("push", "-q", "origin", "HEAD:main")
        saveLog()
        return if (changed) "Сайт и база отправлены в $url" else "Изменений не было, GitHub уже актуален."
    }

    /** Downloads changes from GitHub; imports the database if the dump changed. */
    fun pull(progress: (String) -> Unit): String {
        ensureRepo()
        if (!remoteHasMain()) throw IOException("В репозитории пока пусто. Сначала нажми «Отправить».")
        progress("Сохраняю локальные изменения…")
        dumpDb()
        commitLocal()
        val dbBefore = git("rev-parse", "HEAD:db/opencart.sql", check = false).second.trim()
        progress("Забираю изменения с GitHub…")
        git("fetch", "-q", "origin", "main")
        val (c, out) = git("merge", "--no-edit", "--allow-unrelated-histories", "origin/main", check = false)
        if (c != 0) {
            git("merge", "--abort", check = false)
            saveLog()
            throw IOException("Конфликт: одни и те же файлы менялись и на телефоне, и на GitHub. Изменения не применены.\n" + out.takeLast(600))
        }
        val dbAfter = git("rev-parse", "HEAD:db/opencart.sql", check = false).second.trim()
        var msg = "Изменения с GitHub применены."
        if (dbAfter != dbBefore && File(st.base, "db/opencart.sql").exists()) {
            progress("Загружаю обновлённую базу…")
            val p = st.env(ProcessBuilder(File(st.bin, "mariadb").path, "-h", "127.0.0.1", "-P", Stack.MYSQL_PORT.toString(),
                "-u", "root", Stack.DB_NAME))
                .redirectInput(File(st.base, "db/opencart.sql"))
                .redirectErrorStream(true).redirectOutput(File(st.logs, "git-import.log")).start()
            if (p.waitFor() != 0) throw IOException("Файлы обновлены, но база не загрузилась: " + Stack.tail(File(st.logs, "git-import.log"), 500))
            msg += " База обновлена."
        }
        st.clearCache()
        git("push", "-q", "origin", "HEAD:main", check = false) // share the merge result
        saveLog()
        return "$msg Кеш очищен."
    }

    private fun saveLog() {
        File(st.logs, "git.log").writeText(log.toString().takeLast(20_000))
    }
}
