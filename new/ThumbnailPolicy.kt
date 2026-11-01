package jp.muya.xsaver

import org.json.JSONObject
import java.net.URI

/** Only image URLs and non-secret request headers; never cookies or authorization. */
object ThumbnailPolicy {
    fun imageUrl(value: String): String? = runCatching {
        val text = if (value.startsWith("//")) "https:$value" else value
        val uri = URI(text)
        val host = uri.host.orEmpty().lowercase()
        text.takeIf { uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.contains(':') && !host.matches(Regex("[0-9.]+")) }
    }.getOrNull()
    fun sources(info: JSONObject): List<String> {
        val rows = info.optJSONArray("thumbnails")
        val images = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optInt("rows", 1) <= 1 && it.optInt("columns", 1) <= 1 }
            .sortedByDescending { it.optDouble("width", 0.0) * it.optDouble("height", 0.0) }
        return (listOf(info.optString("thumbnail")) + images.map { it.optString("url") })
            .mapNotNull(::imageUrl).distinct().take(4)
    }
    fun headers(info: JSONObject, page: String): Map<String, String> {
        val source = info.optJSONObject("http_headers") ?: JSONObject()
        val result = mutableMapOf("User-Agent" to "Mozilla/5.0")
        for (key in listOf("User-Agent", "Referer", "Origin")) {
            val value = source.optString(key).takeIf { it.length in 1..2048 && it.none { c -> c.isISOControl() } } ?: continue
            if (key == "User-Agent" || imageUrl(value) != null) result[key] = value
        }
        if ("Referer" !in result) imageUrl(page)?.let { result["Referer"] = it }
        return result
    }
}
