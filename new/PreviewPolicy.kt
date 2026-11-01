package jp.muya.xsaver

import org.json.JSONObject

data class PreviewTrack(val url: String, val mime: String?, val headers: Map<String, String>)
data class PreviewPlan(val video: PreviewTrack?, val audio: PreviewTrack?)
object PreviewPolicy {
    private fun playable(row: JSONObject): Boolean {
        val protocol = row.optString("protocol")
        return !row.optBoolean("has_drm") && ThumbnailPolicy.imageUrl(row.optString("url")) != null &&
            (protocol.isEmpty() || protocol in setOf("https", "http", "m3u8", "m3u8_native", "http_dash_segments")) &&
            (row.optJSONArray("fragments") == null || protocol.startsWith("m3u8"))
    }
    private fun track(row: JSONObject, parent: JSONObject, page: String): PreviewTrack {
        val protocol = row.optString("protocol")
        val path = java.net.URI(row.getString("url")).path.orEmpty()
        val mime = when {
            protocol.startsWith("m3u8") || path.endsWith(".m3u8", true) -> "application/x-mpegURL"
            path.endsWith(".mpd", true) -> "application/dash+xml"
            else -> null
        }
        val merged = JSONObject(parent.toString())
        row.optJSONObject("http_headers")?.let { merged.put("http_headers", it) }
        return PreviewTrack(ThumbnailPolicy.imageUrl(row.getString("url"))!!, mime, ThumbnailPolicy.headers(merged, page))
    }
    fun select(page: String, info: JSONObject): PreviewPlan? {
        if (info.optBoolean("has_drm")) return null
        val array = info.optJSONArray("formats")
        val rows = (if (array == null) listOf(info) else (0 until array.length()).mapNotNull { array.optJSONObject(it) }).filter(::playable)
        val videos = rows.filter { it.optString("vcodec", "unknown") != "none" && (it.optInt("height") > 0 || it.optString("vcodec", "unknown") != "unknown") }
        val audio = rows.filter { it.optString("vcodec") == "none" && it.optString("acodec", "unknown") != "none" }
            .minByOrNull { kotlin.math.abs(it.optDouble("abr", 128.0) - 128.0) }
        // Prefer a modest combined stream. Split streams are synchronized by the player.
        val candidates = videos.filter { it.optString("acodec", "unknown") != "none" }.ifEmpty { videos }
        val video = candidates.minWithOrNull(compareBy<JSONObject>(
            { if (it.optInt("height") in 1..720) 0 else 1 },
            { if (it.optString("vcodec").startsWith("avc") || it.optString("vcodec").startsWith("h264")) 0 else 1 },
            { kotlin.math.abs(it.optInt("height", 720) - 480) }))
        if (video == null) return audio?.let { PreviewPlan(null, track(it, info, page)) }
        val split = video.optString("acodec", "unknown") == "none"
        // Do not offer a silent preview for a source which requires a missing audio track.
        if (split && audio == null) return null
        return PreviewPlan(track(video, info, page), if (split) track(audio!!, info, page) else null)
    }
    fun audio(page: String, info: JSONObject): PreviewPlan? {
        val plan = select(page, info) ?: return null
        val array = info.optJSONArray("formats")
        val rows = if (array == null) listOf(info) else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val audio = rows.filter { playable(it) && it.optString("vcodec") == "none" && it.optString("acodec", "unknown") != "none" }
            .minByOrNull { kotlin.math.abs(it.optDouble("abr", 128.0) - 128.0) }
        return if (audio == null) plan else PreviewPlan(null, track(audio, info, page))
    }
}
