package jp.muya.xsaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object ThumbnailLoader {
    fun load(info: MediaInfo): Bitmap? {
        for (url in info.thumbnails.ifEmpty { listOfNotNull(info.thumbnail) }) {
            if (Thread.currentThread().isInterrupted) return null
            val bitmap = runCatching { read(url, info.thumbnailHeaders) }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }
    private fun read(source: String, headers: Map<String, String>): Bitmap? {
        var url = ThumbnailPolicy.imageUrl(source) ?: return null
        repeat(4) { attempt ->
            check(!Thread.currentThread().isInterrupted)
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000; c.readTimeout = 15000; c.useCaches = false; c.instanceFollowRedirects = false
                headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
                val code = c.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    require(attempt < 3)
                    url = ThumbnailPolicy.imageUrl(URI(url).resolve(c.getHeaderField("Location")).toString()) ?: return null
                    return@repeat
                }
                require(code == 200 && c.contentLengthLong <= 5L * 1024 * 1024)
                val bytes = c.inputStream.use { input ->
                    val output = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                    while (true) {
                        check(!Thread.currentThread().isInterrupted)
                        val n = input.read(chunk); if (n < 0) break
                        require(output.size() + n <= 5 * 1024 * 1024)
                        output.write(chunk, 0, n)
                    }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000)
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally { c.disconnect() }
        }
        return null
    }
}
