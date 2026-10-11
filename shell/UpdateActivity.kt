package jp.muya.xsaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Own-screen update: only an authenticated APK can reach Android's installer. */
class UpdateActivity : Activity() {
    companion object {
        @Volatile var running = false
            private set
        fun open(activity: Activity) {
            if (running || StateHub.state.busy || jp.muya.xsaver.legacy.StateHub.state.busy) {
                Toast.makeText(activity, "保存・更新が終わってからお試しください。", Toast.LENGTH_LONG).show()
                return
            }
            activity.startActivity(Intent(activity, UpdateActivity::class.java))
        }
    }
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var install: Button
    private var ownsJob = false
    private var worker: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null
    private var verified = false
    private var waitingPermission = false
    private var installing = false
    private val apk by lazy { File(cacheDir, "updates/update.apk") }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (running) { finish(); return }
        running = true; ownsJob = true
        apk.parentFile?.mkdirs(); apk.delete()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        body.addView(TextView(this).apply { text = "アプリを更新"; textSize = 24f })
        message = TextView(this).apply { text = "更新内容を確認しています…"; textSize = 16f }; body.addView(message)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }; body.addView(progress)
        install = Button(this).apply { text = "更新をインストール"; visibility = View.GONE; setOnClickListener { startInstall() } }; body.addView(install)
        body.addView(Button(this).apply { text = "中止して戻る"; setOnClickListener { finish() } })
        setContentView(body)
        worker = Thread({
            try {
                val remote = AppUpdates.check()
                if (!AppUpdates.newer(remote.version, BuildConfig.VERSION_NAME)) throw IllegalStateException("今のアプリは最新版です。")
                val update = remote.apk ?: throw IllegalStateException("この版はアプリ内更新に対応していません。今のアプリを削除せず、配布情報を確認してください。")
                checkCancel()
                if (NetworkPolicy.needsWarning(this, update.bytes)) {
                    runOnUiThread {
                        if (!isFinishing) AlertDialog.Builder(this).setTitle("通信量にご注意ください")
                            .setMessage("Wi-Fi・有線接続ではありません。更新ファイルは約${update.bytes / (1024 * 1024)}MBです。取得しますか？")
                            .setPositiveButton("取得する") { _, _ -> beginDownload(update) }
                            .setNegativeButton("戻る") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                    }
                } else beginDownload(update)
            } catch (e: Exception) { failed(e) }
        }, "app-update-check").apply { start() }
    }
    private fun checkCancel() { if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException() }
    private fun beginDownload(update: UpdatePackage) {
        if (cancelled || isFinishing) return
        worker = Thread({
            try {
                var done = false
                for (attempt in 0..2) {
                    checkCancel()
                    try { download(update); done = true; break }
                    catch (e: IOException) {
                        apk.delete(); checkCancel()
                        if (attempt == 2) throw e
                        runOnUiThread { if (!isFinishing) message.text = "通信が途切れたので、もう一度取得します…" }
                        Thread.sleep((attempt + 1) * 1000L)
                    }
                }
                check(done)
                verify(update)
                checkCancel()
                verified = true
                runOnUiThread {
                    if (!isFinishing) {
                        progress.visibility = View.GONE
                        message.text = "更新ファイルを確認しました。ログイン情報と設定を残して、Androidの確認画面から更新します。"
                        install.visibility = View.VISIBLE
                        startInstall()
                    }
                }
            } catch (e: Exception) { apk.delete(); failed(e) }
        }, "app-update-download").apply { start() }
    }
    private fun download(update: UpdatePackage) {
        var url = URL(update.url)
        var response: HttpURLConnection? = null
        try {
            for (redirect in 0..5) {
                checkCancel()
                require(url.protocol == "https" && url.userInfo == null && url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
                val c = url.openConnection() as HttpURLConnection
                response = c; connection = c
                c.connectTimeout = 30000; c.readTimeout = 60000; c.useCaches = false; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "MOVIE-AUDIO-TOOL")
                val code = c.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val next = c.getHeaderField("Location") ?: throw IOException()
                    url = URL(url, next); c.disconnect(); continue
                }
                if (code != 200) throw IOException()
                val declared = c.contentLengthLong
                require(declared < 0 || declared == update.bytes)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L; var lastProgress = -1
                c.inputStream.use { input -> apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancel(); val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= update.bytes)
                        output.write(buffer, 0, n); digest.update(buffer, 0, n)
                        val percent = (total * 100 / update.bytes).toInt()
                        if (percent != lastProgress) {
                            lastProgress = percent
                            runOnUiThread { if (!isFinishing) { progress.isIndeterminate = false; progress.progress = percent; message.text = "更新ファイルを取得しています… $percent%" } }
                        }
                    }
                } }
                if (total != update.bytes) throw IOException()
                require(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256)
                return
            }
            throw IOException()
        } finally { response?.disconnect(); connection = null }
    }
    @Suppress("DEPRECATION")
    private fun verify(update: UpdatePackage) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val current = packageManager.getPackageInfo(packageName, flags)
        val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalStateException("更新ファイルを読み取れませんでした。")
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { UpdatePackage.hash(it.toByteArray()) }?.toSet().orEmpty()
        if (signers(current) != signers(candidate)) throw IllegalStateException("署名が違うため上書き更新できません。ログイン情報を守るため停止しました。今のアプリは削除しないでください。")
        require(candidate.versionName == update.version && candidate.longVersionCode == update.versionCode)
        require(UpdatePackage.compatible(current.packageName, candidate.packageName, current.longVersionCode, candidate.longVersionCode, signers(current), signers(candidate)))
    }
    @Suppress("DEPRECATION")
    private fun startInstall() {
        if (!verified || installing || isFinishing) return
        if (!packageManager.canRequestPackageInstalls()) {
            waitingPermission = true
            message.text = "初回だけ、このアプリからのインストールを許可してください。許可して戻ると更新を続けます。"
            install.text = "インストールを許可する"
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { waitingPermission = false; message.text = "インストールの許可画面を開けませんでした。" }
            return
        }
        waitingPermission = false; installing = true
        val uri = Uri.parse("content://$packageName.updates/update.apk")
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true), 2)
        }.onFailure { installing = false; message.text = "Androidの更新画面を開けませんでした。今のアプリはそのまま使えます。" }
    }
    override fun onResume() {
        super.onResume()
        if (waitingPermission && packageManager.canRequestPackageInstalls()) startInstall()
    }
    @Deprecated("Activity result API used for platform installer")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 2) {
            installing = false; verified = false; apk.delete()
            message.text = if (result == RESULT_OK) "更新しました。" else "更新を中止しました。ログイン情報と今のアプリはそのままです。"
            install.visibility = View.GONE
        }
    }
    private fun failed(error: Exception) {
        if (cancelled) return
        runOnUiThread {
            if (!isFinishing) {
                progress.visibility = View.GONE
                message.text = if (error is IllegalStateException) error.message else "更新ファイルを取得・確認できませんでした。今のアプリはそのまま使えます。通信状態を確認してお試しください。"
            }
        }
    }
    override fun onDestroy() {
        if (!ownsJob) { super.onDestroy(); return }
        cancelled = true; connection?.disconnect(); worker?.interrupt(); running = false
        if (!installing) apk.delete()
        super.onDestroy()
    }
}
