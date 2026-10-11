package jp.muya.xsaver

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

// Legacy startup still checks both components, without blocking available downloads.
object StartupUpdates {
    fun check(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching { AppUpdates.check() }
            runCatching {
                JSONObject(UpdateHttp.get("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")).getString("tag_name")
                synchronized(EngineGate.lock) {
                    YoutubeDL.init(app)
                    YoutubeDL.execute(YoutubeDLRequest(emptyList()).addOption("--version")).out
                }
            }
        }.start()
    }
}
