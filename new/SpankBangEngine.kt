package jp.muya.xsaver

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/** Separate process and Python home; the existing engine and account store are never replaced. */
object SpankBangEngine {
    private const val REVISION = "35f2609d80ce26b6683a0b2b6bc52f32efe4c81c"
    private val processes = ConcurrentHashMap<String, Process>()
    private val wrapper = """
        import os, signal, sys, runpy
        os.setsid()
        def stop(signum, frame):
            os.killpg(os.getpgrp(), signal.SIGKILL)
        signal.signal(signal.SIGTERM, stop)
        engine = sys.argv.pop(1)
        sys.argv[0] = engine
        runpy.run_path(engine, run_name='__main__')
    """.trimIndent()
    fun cancel(id: String) { processes[id]?.destroy() }

    @Synchronized
    private fun home(context: Context, checkCancel: () -> Unit): File {
        val base = File(context.noBackupFilesDir, "spankbang-transport")
        val ready = File(base, "revision")
        if (ready.isFile && ready.readText() == REVISION + ":2") return base
        base.deleteRecursively(); check(base.mkdirs())
        val payload = File(context.applicationInfo.nativeLibraryDir, "libsbpythonpayload.so")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            payload.inputStream().use { input ->
                val chunk = ByteArray(65536)
                while (true) { checkCancel(); val n = input.read(chunk); if (n < 0) break; hash.update(chunk, 0, n) }
            }
            val expected = if (android.os.Build.SUPPORTED_ABIS.first() == "x86_64")
                "31ee24c1c503f15183e72f09bbc86245b7d956198b1aa2c048c6d07219f09f1f" else
                "dfd2013985b16f63c69dc5a408488ca3ad96b01141e717d57d89e4ade15da296"
            check(hash.digest().joinToString("") { "%02x".format(it) } == expected) { "通信部品の確認に失敗しました。アプリを更新してください。" }
            ZipInputStream(payload.inputStream()).use { zip ->
                var total = 0L
                while (true) {
                    checkCancel(); val entry = zip.nextEntry ?: break
                    val file = File(base, entry.name)
                    check(file.canonicalPath.startsWith(base.canonicalPath + File.separator))
                    // Upstream bundles a Termux liblzma that must not shadow Android's library.
                    if (entry.name == "usr/lib/liblzma.so") continue
                    if (entry.isDirectory) { file.mkdirs(); continue }
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        val chunk = ByteArray(65536)
                        while (true) {
                            checkCancel(); val n = zip.read(chunk); if (n < 0) break
                            total += n; check(total <= 180L * 1024 * 1024)
                            out.write(chunk, 0, n)
                        }
                    }
                }
            }
            RuntimeLibraries.restore(File(base, "usr/lib"))
            ready.writeText(REVISION + ":2")
            return base
        } catch (e: Exception) { base.deleteRecursively(); throw e }
    }
    fun execute(context: Context, request: YoutubeDLRequest, id: String, checkCancel: () -> Unit,
                callback: ((Float, Long, String) -> Unit)? = null): String {
        val engine = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        check(engine.isFile) { "取得エンジンの準備ができていません。" }
        return run(context, listOf("-c", wrapper, engine.absolutePath) + request.buildCommand() +
            listOf("--impersonate", "chrome", "--ffmpeg-location", File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
                "--newline", "--no-colors"), id, checkCancel, callback)
    }
    /** Native Android smoke test; only lists supported transports and performs a public TLS request. */
    fun selfCheck(context: Context): String = run(context, listOf("-c", """
        import ssl, certifi, cffi, curl_cffi
        from curl_cffi import requests
        response = requests.get('https://example.com/', impersonate='chrome', timeout=30)
        assert response.status_code == 200
        print('SPANKBANG_TRANSPORT_OK')
    """.trimIndent()), "transport-self-check", {}, null)

    private fun run(context: Context, args: List<String>, id: String, checkCancel: () -> Unit,
                    callback: ((Float, Long, String) -> Unit)?): String {
        val home = home(context, checkCancel)
        val native = context.applicationInfo.nativeLibraryDir
        val oldPackages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val builder = ProcessBuilder(listOf(File(native, "libsbpython.so").absolutePath) + args)
        builder.environment().apply {
            put("PYTHONHOME", File(home, "usr").absolutePath)
            put("PYTHONDONTWRITEBYTECODE", "1")
            put("PYTHONUNBUFFERED", "1")
            put("LD_LIBRARY_PATH", "${home.absolutePath}/usr/lib:${oldPackages.absolutePath}/ffmpeg/usr/lib:$native")
            put("SSL_CERT_FILE", "${home.absolutePath}/usr/lib/python3.14/site-packages/certifi/cacert.pem")
            put("HOME", home.absolutePath); put("TMPDIR", context.cacheDir.absolutePath)
            put("PATH", "$native:${System.getenv("PATH").orEmpty()}")
        }
        checkCancel()
        val process = builder.start()
        processes[id] = process
        val output = StringBuilder(); val errors = StringBuilder()
        val readFailure = AtomicReference<Throwable?>()
        fun reader(error: Boolean) = Thread {
            try {
                (if (error) process.errorStream else process.inputStream).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (error) {
                            errors.append(line.take(65536)).append('\n')
                            if (errors.length > 65536) errors.delete(0, errors.length - 65536)
                        } else {
                            if (callback == null) {
                                check(output.length + line.length <= 6 * 1024 * 1024) { "動画情報が大きすぎます。" }
                                output.append(line).append('\n')
                            }
                            SpankBangPolicy.percent(line)?.let { callback?.invoke(it, 0, "") }
                        }
                    }
                }
            } catch (e: Throwable) { readFailure.compareAndSet(null, e); process.destroy() }
        }.apply { start() }
        val out = reader(false); val err = reader(true)
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) checkCancel()
            checkCancel(); out.join(); err.join()
            readFailure.get()?.let { throw IllegalStateException("通信処理を読み取れませんでした。", it) }
            if (process.exitValue() != 0) throw Exception(errors.toString()) // classified in memory; never logged or saved
            return output.toString()
        } finally {
            processes.remove(id); process.destroy()
            out.join(3000); err.join(3000)
        }
    }
}
