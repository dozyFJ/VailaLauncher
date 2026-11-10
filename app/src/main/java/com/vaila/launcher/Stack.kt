package com.vaila.launcher

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.ZipInputStream

/** Files and processes of the local PHP + MariaDB stack. */
class Stack(private val ctx: Context) {
    val base: File = ctx.filesDir
    val usr = File(base, "usr")
    val bin = File(usr, "bin")
    val lib = File(usr, "lib")
    val share = File(usr, "share")
    val etc = File(base, "etc")
    val tmp = File(base, "tmp")
    val logs = File(base, "logs")
    val htdocs = File(base, "htdocs")
    val mysqlData = File(base, "mysql/data")
    val phpIni = File(etc, "php.ini")

    /** Unpack binaries (after install/update) and OpenCart (only once). */
    fun prepare(progress: (String) -> Unit) {
        val stamp = File(base, ".runtime-stamp")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (!stamp.exists() || stamp.readText() != ver || !File(bin, "php").exists()) {
            progress("Распаковка PHP и MariaDB…")
            usr.deleteRecursively()
            unzipAsset("runtime.zip", base)
            bin.listFiles()?.forEach { it.setExecutable(true, false) }
            stamp.writeText(ver)
        }
        listOf(etc, tmp, logs).forEach { it.mkdirs() }
        if (!htdocs.exists()) {
            progress("Распаковка OpenCart…")
            val t = File(base, "htdocs.tmp")
            t.deleteRecursively()
            unzipAsset("htdocs.zip", t)
            if (!t.renameTo(htdocs)) throw IOException("Не удалось подготовить htdocs")
        }
        writePhpIni()
    }

    private fun unzipAsset(name: String, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        ZipInputStream(ctx.assets.open(name).buffered()).use { zip ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad zip entry ${e.name}")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { o ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        }
    }

    private fun writePhpIni() {
        val extDir = File(lib, "php")
        val exts = extDir.listFiles()?.map { it.name }?.filter { it.endsWith(".so") }?.sorted().orEmpty()
        val extLines = exts.joinToString("\n") {
            if (it == "opcache.so" || it == "xdebug.so") "zend_extension = ${File(extDir, it).path}" else "extension = $it"
        }
        val ca = File(usr, "etc/tls/cert.pem").path
        phpIni.writeText(
            """
            extension_dir = ${extDir.path}
            openssl.cafile = $ca
            curl.cainfo = $ca
            error_reporting = E_ALL & ~E_DEPRECATED""".trimIndent() + "\n" + extLines + "\n" +
            """
            memory_limit = 256M
            upload_max_filesize = 64M
            post_max_size = 64M
            max_execution_time = 300
            max_input_vars = 5000
            output_buffering = 4096
            date.timezone = UTC
            display_errors = Off
            log_errors = On
            error_log = ${File(logs, "php_errors.log").path}
            session.save_path = ${tmp.path}
            upload_tmp_dir = ${tmp.path}
            sys_temp_dir = ${tmp.path}
            file_uploads = On
            allow_url_fopen = On
            opcache.enable = 1
            opcache.enable_cli = 1
            opcache.lockfile_path = ${tmp.path}
            """.trimIndent() + "\n"
        )
    }

    fun env(pb: ProcessBuilder): ProcessBuilder {
        val e = pb.environment()
        e["LD_LIBRARY_PATH"] = lib.path
        e["HOME"] = base.path
        e["TMPDIR"] = tmp.path
        e["TZ"] = "UTC"
        e["PHPRC"] = etc.path
        e["PREFIX"] = usr.path
        return pb
    }

    private fun mysqldArgs(): MutableList<String> {
        val msg = File(share, "mariadb")
        return mutableListOf(
            File(bin, "mariadbd").path,
            "--no-defaults",
            "--basedir=${usr.path}",
            "--datadir=${mysqlData.path}",
            "--plugin-dir=${File(lib, "plugin").path}",
            "--lc-messages-dir=${msg.path}",
            "--character-sets-dir=${File(msg, "charsets").path}",
            "--tmpdir=${tmp.path}",
            "--socket=${File(tmp, "mysqld.sock").path}",
            "--pid-file=${File(tmp, "mysqld.pid").path}",
            "--skip-name-resolve",
            "--innodb-use-native-aio=0",
            "--innodb-buffer-pool-size=32M",
            "--performance-schema=OFF",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_general_ci",
        )
    }

    fun mysqlInitialized() = File(mysqlData, "mysql").isDirectory

    fun initMysql() {
        if (mysqlInitialized()) return
        mysqlData.deleteRecursively()
        mysqlData.mkdirs()
        val log = File(logs, "mysql_bootstrap.log")
        val args = mysqldArgs().apply {
            addAll(listOf("--bootstrap", "--log-warnings=0", "--enforce-storage-engine=",
                "--max_allowed_packet=8M", "--net_buffer_length=16K"))
        }
        val p = env(ProcessBuilder(args))
            .redirectInput(File(share, "bootstrap.sql"))
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        val code = p.waitFor()
        if (code != 0 || !mysqlInitialized()) {
            mysqlData.deleteRecursively()
            throw IOException("MariaDB не инициализировалась (код $code)\n" + tail(log, 1500))
        }
    }

    fun startMysql(): Process {
        val log = File(logs, "mysql.log")
        trimLog(log)
        val args = mysqldArgs().apply {
            addAll(listOf("--port=$MYSQL_PORT", "--bind-address=127.0.0.1",
                "--log-error=${log.path}", "--max-connections=30",
                "--sql-mode=NO_ENGINE_SUBSTITUTION"))
        }
        return env(ProcessBuilder(args))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
    }

    fun startPhp(lan: Boolean): Process {
        val log = File(logs, "php_server.log")
        trimLog(log)
        val host = if (lan) "0.0.0.0" else "127.0.0.1"
        val pb = ProcessBuilder(File(bin, "php").path, "-c", phpIni.path,
            "-S", "$host:$HTTP_PORT", "-t", htdocs.path)
            .directory(htdocs)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        env(pb).environment()["PHP_CLI_SERVER_WORKERS"] = "4"
        return pb.start()
    }

    /** Run a php CLI command, returns exit code and combined output. */
    fun runPhp(dir: File, vararg args: String): Pair<Int, String> {
        val pb = env(ProcessBuilder(listOf(File(bin, "php").path, "-c", phpIni.path) + args))
            .directory(dir)
            .redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /**
     * Installs OpenCart with its own CLI installer: creates a DB user,
     * runs install/cli_install.php and removes the install folder.
     */
    fun autoInstall(admin: String, password: String, email: String, progress: (String) -> Unit): String {
        val installDir = File(htdocs, "install")
        if (!File(installDir, "cli_install.php").exists()) throw IOException("Папки install нет: OpenCart уже установлен. Для переустановки нажми «Сбросить всё».")
        val log = StringBuilder()

        progress("Создаю базу и пользователя…")
        val sql = File(tmp, "mkdb.php")
        sql.writeText(
            """
            <?php
            mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);
            ${'$'}m = new mysqli('127.0.0.1', 'root', '', '', $MYSQL_PORT);
            ${'$'}m->query("DROP DATABASE IF EXISTS `$DB_NAME`");
            ${'$'}m->query("CREATE DATABASE `$DB_NAME` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            ${'$'}m->query("CREATE USER IF NOT EXISTS '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASS'");
            ${'$'}m->query("GRANT ALL PRIVILEGES ON `$DB_NAME`.* TO '$DB_USER'@'127.0.0.1'");
            ${'$'}m->query("FLUSH PRIVILEGES");
            echo "db ok\n";
            """.trimIndent() + "\n"
        )
        val (c1, o1) = runPhp(tmp, sql.path)
        sql.delete()
        log.append(o1)
        if (c1 != 0 || !o1.contains("db ok")) throw IOException("Не удалось создать базу:\n$o1")

        progress("Устанавливаю OpenCart (до минуты)…")
        val (c2, o2) = runPhp(
            installDir, "cli_install.php", "install",
            "--username", admin,
            "--password", password,
            "--email", email,
            "--http_server", "http://127.0.0.1:$HTTP_PORT/",
            "--db_driver", "mysqli",
            "--db_hostname", "127.0.0.1",
            "--db_username", DB_USER,
            "--db_password", DB_PASS,
            "--db_database", DB_NAME,
            "--db_port", MYSQL_PORT.toString(),
            "--db_prefix", "oc_",
        )
        log.append(o2)
        File(logs, "install.log").writeText(log.toString())
        val cfg = File(htdocs, "config.php")
        val ok = cfg.exists() && cfg.readText().contains("'$DB_NAME'")
        if (c2 != 0 || !ok) throw IOException("Установщик OpenCart вернул ошибку (код $c2):\n" + o2.takeLast(1500))

        installDir.deleteRecursively()
        return o2.trim().takeLast(300)
    }

    /** Kill leftovers of our binaries (e.g. after the app was killed). */
    fun killStale() {
        val prefix = bin.path
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val cmd = try { File(d, "cmdline").readText() } catch (_: Exception) { return@forEach }
            if (cmd.startsWith(prefix)) android.os.Process.killProcess(pid)
        }
    }

    fun resetAll() {
        htdocs.deleteRecursively()
        File(base, "mysql").deleteRecursively()
        tmp.deleteRecursively()
        logs.deleteRecursively()
    }

    fun logsText(): String = buildString {
        for (n in listOf("php_server.log", "php_errors.log", "mysql.log", "mysql_bootstrap.log", "install.log", "service.log")) {
            val f = File(logs, n)
            if (f.exists()) append("===== $n =====\n").append(tail(f, 6000)).append("\n\n")
        }
        val info = File(share, "runtime-info.txt")
        if (info.exists()) append("===== версии =====\n").append(info.readText())
    }.ifEmpty { "Логов пока нет" }

    fun log(msg: String) {
        logs.mkdirs()
        File(logs, "service.log").appendText("${java.util.Date()}  $msg\n")
    }

    companion object {
        const val HTTP_PORT = 8080
        const val MYSQL_PORT = 3306
        const val DB_NAME = "opencart"
        const val DB_USER = "opencart"
        const val DB_PASS = "opencart"

        fun portOpen(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
        } catch (_: Exception) {
            false
        }

        fun tail(f: File, max: Int): String {
            if (!f.exists()) return ""
            RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val start = maxOf(0L, len - max)
                r.seek(start)
                val b = ByteArray((len - start).toInt())
                r.readFully(b)
                return String(b)
            }
        }

        fun trimLog(f: File) {
            if (f.exists() && f.length() > 2_000_000) f.delete()
        }
    }
}
