package jp.muya.xsaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.URI

@androidx.media3.common.util.UnstableApi
class PreviewActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var status: TextView
    private lateinit var start: Button
    private var position = 0L
    private var userStarted = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private fun trusted(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
    private var mobileApproved = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        position = state?.getLong("position", 0L) ?: 0L
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 24, 39))
            setPadding(16, 16, 16, 16)
            setOnApplyWindowInsetsListener { v, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    v.setPadding(16 + bars.left, 16 + bars.top, 16 + bars.right, 16 + bars.bottom)
                }
                insets
            }
        }
        body.addView(TextView(this).apply { text = intent.getStringExtra("title"); textSize = 18f; setTextColor(Color.WHITE) })
        view = PlayerView(this).apply { keepScreenOn = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
        body.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "再生には通信を使います。保存せずに中身を確認できます。"; setTextColor(Color.WHITE) }
        body.addView(status)
        start = Button(this).apply { text = "プレビューを再生"; setOnClickListener { requestPlayback() } }; body.addView(start)
        body.addView(Button(this).apply { text = "閉じる"; setOnClickListener { finish() } })
        setContentView(body)
        requestPlayback()
    }
    private fun requestPlayback() {
        if (!trusted() && !mobileApproved) {
            AlertDialog.Builder(this).setTitle("プレビューの通信について")
                .setMessage("Wi-Fi・有線接続ではありません。再生した分の通信量がかかります。再生しますか？")
                .setPositiveButton("再生する") { _, _ -> mobileApproved = true; play() }
                .setNegativeButton("戻る") { _, _ -> finish() }.show()
        } else play()
    }
    private fun safeUrl(value: String?): String? = value?.takeIf { text -> runCatching {
        val u = URI(text); u.scheme == "https" && u.host != null && u.userInfo == null && u.port in setOf(-1, 443)
    }.getOrDefault(false) }
    private fun headers(key: String): Map<String, String> {
        val json = runCatching { JSONObject(intent.getStringExtra(key).orEmpty()) }.getOrDefault(JSONObject())
        return listOf("User-Agent", "Referer", "Origin").mapNotNull { name ->
            json.optString(name).takeIf { it.length in 1..2048 && it.none(Char::isISOControl) }?.let { name to it }
        }.toMap()
    }
    private fun play() {
        releasePlayer()
        val video = safeUrl(intent.getStringExtra("video")); val audio = safeUrl(intent.getStringExtra("audio"))
        if (video == null && audio == null) { status.text = "この動画はプレビューに対応していません。保存して確認してください。"; return }
        userStarted = true
        runCatching {
            val p = PreviewPlayback.create(this, video, audio, intent.getStringExtra("videoMime"), intent.getStringExtra("audioMime"), headers("videoHeaders"), headers("audioHeaders"))
            player = p; view.player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    status.text = when (state) {
                        Player.STATE_BUFFERING -> "再生の準備をしています…"
                        Player.STATE_READY -> "プレビュー中（保存はしていません）"
                        Player.STATE_ENDED -> "再生が終わりました。"
                        else -> ""
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Never expose/log signed media URLs or raw transport errors.
                    releasePlayer()
                    status.text = "プレビューを再生できませんでした。元の画面で読み込み直すか、保存して確認してください。"
                    start.visibility = View.VISIBLE; start.text = "再試行"
                }
            })
            p.seekTo(position); p.prepare(); p.playWhenReady = true
            start.visibility = View.GONE
        }.onFailure { releasePlayer(); status.text = "プレビューを開始できませんでした。保存して確認してください。"; start.visibility = View.VISIBLE }
    }
    override fun onStart() {
        super.onStart()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                runOnUiThread {
                    if (player != null && !mobileApproved && !trusted()) {
                        releasePlayer(); status.text = "Wi-Fi・有線接続が切れたため停止しました。再生する場合は通信量を確認してください。"
                        start.visibility = View.VISIBLE
                    }
                }
            }
            override fun onLost(network: android.net.Network) { runOnUiThread {
                if (player != null && !mobileApproved && !trusted()) { releasePlayer(); start.visibility = View.VISIBLE; status.text = "通信が切れたため停止しました。" }
            } }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback); networkCallback = callback }
        if (userStarted && player == null) { status.text = "再生を停止しました。続きから再生できます。"; start.visibility = View.VISIBLE }
    }
    private fun releasePlayer() {
        player?.let { position = it.currentPosition; view.player = null; it.release() }; player = null
    }
    override fun onStop() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }; networkCallback = null
        releasePlayer(); super.onStop()
    }
    override fun onDestroy() { releasePlayer(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { out.putLong("position", player?.currentPosition ?: position); super.onSaveInstanceState(out) }
    companion object {
        fun open(context: Context, title: String, video: String?, audio: String?, videoMime: String?, audioMime: String?, videoHeaders: Map<String, String>, audioHeaders: Map<String, String>) {
            context.startActivity(Intent(context, PreviewActivity::class.java).putExtra("title", title).putExtra("video", video).putExtra("audio", audio)
                .putExtra("videoMime", videoMime).putExtra("audioMime", audioMime)
                .putExtra("videoHeaders", JSONObject(videoHeaders).toString()).putExtra("audioHeaders", JSONObject(audioHeaders).toString()))
        }
    }
}
