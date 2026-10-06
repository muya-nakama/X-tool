package jp.muya.xsaver

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

class DownloadService : Service() {
    companion object {
        const val DOWNLOAD = "jp.muya.xsaver.DOWNLOAD"
        const val UPDATE = "jp.muya.xsaver.UPDATE"
        const val CANCEL = "jp.muya.xsaver.CANCEL"
        private const val CHANNEL = "download"
        private const val NOTICE = 1
    }
    @Volatile private var working = false
    @Volatile private var cancelled = false
    private val processId = UUID.randomUUID().toString()
    private var wake: PowerManager.WakeLock? = null
    private var cookieFile: File? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "保存中の進捗", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (working) {
                cancelled = true
                StateHub.publish(DownloadState(true, "キャンセルしています…"))
                YoutubeDL.destroyProcessById(processId)
            } else stopSelf()
            return START_NOT_STICKY
        }
        if (working) return START_NOT_STICKY
        val updating = intent?.action == UPDATE
        val url = XLink.parse(intent?.getStringExtra("url").orEmpty())
        val session = intent?.getStringExtra("session")
        intent?.removeExtra("url")
        intent?.removeExtra("session")
        if (!updating && url == null) { stopSelf(); return START_NOT_STICKY }
        working = true
        cancelled = false
        val first = if (updating) "取得エンジンを更新しています…" else "内容を確認しています…"
        startForeground(NOTICE, notification(first))
        StateHub.publish(DownloadState(true, first))
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xsaver:download").apply { acquire(6 * 60 * 60 * 1000L) }
        Thread({ runJob(url, updating, session) }, "x-saver-job").start()
        return START_NOT_STICKY
    }
    private fun checkCancel() { if (cancelled) throw CancellationException() }
    private fun runJob(url: String?, updating: Boolean, session: String?) {
        val tasks = File(cacheDir, "downloads")
        val published = mutableListOf<Uri>()
        var finished = false
        var result = DownloadState(message = "処理を終了しました。")
        try {
            tasks.deleteRecursively() // Also removes leftovers after force-stop/process death.
            cleanPendingRows()
            checkCancel()
            YoutubeDL.init(applicationContext)
            FFmpeg.init(applicationContext)
            checkCancel()
            if (updating) {
                YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                checkCancel()
                result = DownloadState(message = "取得エンジンを更新しました。")
                finished = true
                return
            }
            val task = File(tasks, UUID.randomUUID().toString()).apply { check(mkdirs()) }
            val output = File(task, "media").apply { check(mkdirs()) }
            if (session != null) cookieFile = File(task, "session.txt").apply { writeText(SessionCookies.filter(session)) }
            val infoRequest = baseRequest(url!!).addOption("--dump-single-json").addOption("--skip-download")
            val info = YoutubeDL.execute(infoRequest, processId, null).out
            checkCancel()
            val json = JSONObject(info)
            val media = leaves(json)
            if (media.isEmpty()) throw IllegalStateException("このリンクに保存できる動画・音声がありません。")
            if (media.any { it.optBoolean("is_live") || it.optString("live_status") in setOf("is_live", "is_upcoming", "post_live") }) {
                throw IllegalStateException("この版は録音済みスペース用です。配信終了後、録音が公開されてからお試しください。")
            }
            val audio = media.all { item ->
                item.optString("extractor").contains("spaces", true) || item.optString("extractor_key").contains("spaces", true) ||
                    (item.optJSONArray("formats")?.let { formats ->
                        (0 until formats.length()).all { i -> formats.getJSONObject(i).optString("vcodec") == "none" }
                    } == true && item.optJSONArray("formats")!!.length() > 0)
            }
            val metadata = File(task, "info.json").apply { writeText(info) }
            val request = baseRequest(null)
                .addOption("--load-info-json", metadata.absolutePath)
                .addOption("-o", File(output, "%(title).100B [%(id)s].%(ext)s").absolutePath)
                .addOption("--windows-filenames")
                .addOption("--concurrent-fragments", 4)
                .addOption("--no-mtime")
            if (audio) request.addOption("-x").addOption("--audio-format", "m4a")
            else request.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b")
                .addOption("--merge-output-format", "mp4").addOption("--remux-video", "mp4")
            show(if (audio) "スペースの音声をダウンロードしています…" else "動画をダウンロードしています…")
            checkCancel()
            var lastNotice = 0L
            YoutubeDL.execute(request, processId) { percent, _, _ ->
                // Raw downloader output may contain URLs: do not display or log it.
                if (cancelled) YoutubeDL.destroyProcessById(processId)
                else {
                    val p = percent.toInt().coerceIn(0, 100)
                    val message = if (p == 100) "取得データを仕上げています…" else "ダウンロードしています… $p%"
                    StateHub.publish(DownloadState(true, message, p))
                    if (System.currentTimeMillis() - lastNotice > 1000) {
                        getSystemService(NotificationManager::class.java).notify(NOTICE, notification(message))
                        lastNotice = System.currentTimeMillis()
                    }
                }
            }
            checkCancel()
            val ext = if (audio) "m4a" else "mp4"
            val files = output.listFiles()?.filter { it.isFile && it.extension == ext && it.length() > 0 }.orEmpty()
            if (files.isEmpty()) throw IllegalStateException("保存できるファイルが生成されませんでした。")
            val mime = if (audio) "audio/mp4" else "video/mp4"
            val folder = "Download/X保存/" + if (audio) "スペース/" else "動画/"
            show("端末に保存しています…")
            for (file in files) {
                checkCancel()
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, folder)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("保存先を作成できませんでした。")
                published.add(uri)
                contentResolver.openOutputStream(uri, "w")?.use { dest ->
                    file.inputStream().use { source ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            checkCancel()
                            val n = source.read(buffer)
                            if (n < 0) break
                            dest.write(buffer, 0, n)
                        }
                    }
                } ?: throw IllegalStateException("保存先に書き込めませんでした。")
            }
            checkCancel()
            for (uri in published) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            finished = true
            val message = if (files.size == 1) "${files.first().name}\nダウンロードしました。" else "${files.size}件のファイルをダウンロードしました。"
            result = DownloadState(message = message, file = published.first(), mime = mime)
        } catch (e: Exception) {
            val message = when {
                cancelled || e is CancellationException || e is YoutubeDL.CanceledException -> "キャンセルしました。"
                e.message.orEmpty().contains("auth", true) || e.message.orEmpty().contains("login", true) || e.message.orEmpty().contains("cookies", true) ->
                    "Xへのログインが必要です。「ログインが必要な場合」から、今回だけ使うCookieファイルを読み込んで再度お試しください。"
                e is IllegalStateException -> e.message ?: "保存できませんでした。"
                else -> "取得できませんでした。リンク・通信状態を確認し、取得エンジンを更新して再度お試しください。"
            }
            result = DownloadState(message = message)
        } finally {
            if (!finished) published.forEach { uri -> runCatching { contentResolver.delete(uri, null, null) } }
            tasks.deleteRecursively()
            cookieFile = null
            if (wake?.isHeld == true) wake?.release()
            working = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            StateHub.publish(result)
        }
    }
    private fun baseRequest(url: String?): YoutubeDLRequest {
        val request = (if (url == null) YoutubeDLRequest(emptyList()) else YoutubeDLRequest(url))
            .addOption("--ignore-config").addOption("--no-cache-dir").addOption("--no-playlist")
            .addOption("--socket-timeout", 20).addOption("--retries", 3).addOption("--fragment-retries", 3)
            .addOption("--abort-on-unavailable-fragments")
        cookieFile?.let { request.addOption("--cookies", it.absolutePath) }
        return request
    }
    private fun leaves(item: JSONObject): List<JSONObject> {
        val entries = item.optJSONArray("entries") ?: return listOf(item)
        return (0 until entries.length()).flatMap { i -> entries.optJSONObject(i)?.let { leaves(it) }.orEmpty() }
    }
    private fun cleanPendingRows() {
        // Scoped storage exposes this application's unfinished rows, not other apps' pending data.
        contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.IS_PENDING}=1 AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("Download/X保存/%"), null)?.use { rows ->
            val ids = mutableListOf<Long>()
            while (rows.moveToNext()) ids.add(rows.getLong(0))
            ids.forEach { id -> contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }
    private fun show(message: String) {
        StateHub.publish(DownloadState(true, message))
        getSystemService(NotificationManager::class.java).notify(NOTICE, notification(message))
    }
    private fun notification(message: String): Notification {
        val view = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(jp.muya.xsaver.R.drawable.ic_download)
            .setContentTitle("X保存").setContentText(message).setContentIntent(view).setOnlyAlertOnce(true)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "キャンセル", cancel).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
        YoutubeDL.destroyProcessById(processId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        cancelled = true
        YoutubeDL.destroyProcessById(processId)
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
}
