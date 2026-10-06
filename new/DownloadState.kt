package jp.muya.xsaver

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

data class DownloadState(
    val busy: Boolean = false,
    val message: String = "リンクを貼り付けて保存できます。",
    val progress: Int = -1,
    val file: Uri? = null,
    val mime: String? = null
)

// Process memory only: no URL, history, or progress is written to disk.
object StateHub {
    @Volatile var state = DownloadState()
        private set
    private val listeners = CopyOnWriteArraySet<(DownloadState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun listen(listener: (DownloadState) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (DownloadState) -> Unit) { listeners.remove(listener) }
    fun publish(next: DownloadState) {
        state = next
        main.post { listeners.forEach { it(next) } }
    }
}
