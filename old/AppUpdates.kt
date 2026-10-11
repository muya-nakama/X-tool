package jp.muya.xsaver

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val version: String, val url: String)
data class AppVersions(val version: String, val url: String, val previous: AppUpdate?)
object AppUpdates {
    fun newer(remote: String, local: String): Boolean {
        fun parts(version: String): List<Int> = version.removePrefix("v").split('.').map { it.toInt() }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
    fun check(): AppVersions {
        val connection = URL("https://raw.githubusercontent.com/muya-nakama/X-tool/main/app-update.json")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.useCaches = false
            require(connection.responseCode == 200)
            val body = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    require(buffer.size() + n <= 8192)
                    buffer.write(chunk, 0, n)
                }
                buffer.toString("UTF-8")
            }
            val json = JSONObject(body)
            val version = json.getString("version")
            require(version.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){1,2}")))
            val url = json.getString("url")
            require(url == "https://github.com/muya-nakama/X-tool/releases/tag/v$version")
            val previous = json.optJSONObject("previous")?.let { old ->
                val v = old.getString("version")
                require(v.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){1,2}")) && newer(version, v))
                val link = old.getString("url")
                require(link == "https://github.com/muya-nakama/X-tool/releases/tag/v$v")
                AppUpdate(v, link)
            }
            return AppVersions(version, url, previous)
        } finally { connection.disconnect() }
    }
}
