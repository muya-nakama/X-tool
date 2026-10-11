package jp.muya.xsaver

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

object UpdateHttp {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 10000; c.useCaches = false
            c.setRequestProperty("User-Agent", "MOVIE-AUDIO-TOOL")
            require(c.responseCode == 200)
            return c.inputStream.use { stream ->
                val buffer = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val n = stream.read(chunk); if (n < 0) break; require(buffer.size() + n <= 2 * 1024 * 1024); buffer.write(chunk, 0, n) }
                buffer.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
}
