package jp.muya.xsaver

import org.json.JSONObject
import java.util.Locale

data class MediaChoice(val selector: String, val label: String, val audio: Boolean, val bytes: Long?, val extension: String)
data class MediaInfo(val url: String, val title: String, val thumbnail: String?, val duration: Double, val choices: List<MediaChoice>, val uploader: String)
object MediaFormats {
    fun size(f: JSONObject, duration: Double): Long? {
        for (key in listOf("filesize", "filesize_approx")) { val n = f.optDouble(key, 0.0); if (n > 0 && n.isFinite()) return n.toLong() }
        val rate = f.optDouble("tbr", 0.0).takeIf { it > 0 } ?: (f.optDouble("abr", 0.0) + f.optDouble("vbr", 0.0))
        return if (rate > 0 && duration > 0 && rate.isFinite() && duration.isFinite()) (rate * 1000 / 8 * duration).toLong() else null
    }
    fun sizeLabel(bytes: Long?) = if (bytes == null) "容量不明" else if (bytes >= 1024L * 1024 * 1024)
        String.format(Locale.US, "約 %.2f GB", bytes / (1024.0 * 1024 * 1024)) else String.format(Locale.US, "約 %.1f MB", bytes / (1024.0 * 1024))
    fun audioLabel(f: JSONObject): String {
        val note = f.optString("format_note", "")
        val codec = f.optString("acodec", "不明")
        val sample = f.optInt("asr", 0)
        val depth = f.optInt("audio_bits_per_sample", f.optInt("bits_per_sample", 0))
        val channels = f.optInt("audio_channels", 0)
        val spatial = Regex("atmos|spatial|ambisonic|360 reality|binaural", RegexOption.IGNORE_CASE).containsMatchIn(note + " " + codec)
        val lossless = Regex("flac|alac|pcm|wav", RegexOption.IGNORE_CASE).containsMatchIn(codec)
        val hiRes = lossless && sample >= 96000 && depth >= 24
        return listOfNotNull(
            if (hiRes) "ハイレゾ" else null,
            if (spatial) "立体音響（配信元表記）" else if (channels > 2) "${channels}ch 多チャンネル" else null,
            codec, if (sample > 0) "${sample / 1000.0}kHz" else null,
            if (depth > 0) "${depth}bit" else null,
            if (f.optDouble("abr", f.optDouble("tbr", 0.0)) > 0) "${f.optDouble("abr", f.optDouble("tbr", 0.0)).toInt()}kbps" else null,
            note.takeIf { it.isNotBlank() }
        ).distinct().joinToString(" · ")
    }
    fun parse(url: String, json: JSONObject): MediaInfo {
        require(json.optJSONArray("entries") == null) { "複数動画の投稿は、各動画のリンクを指定してください。" }
        require(!json.optBoolean("is_live") && json.optString("live_status") !in setOf("is_live", "is_upcoming", "post_live")) { "公開済みの動画・録音を指定してください。" }
        val duration = json.optDouble("duration", 0.0)
        val rows = json.optJSONArray("formats")
        val formats = if (rows == null) listOf(json) else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val available = formats.filter { !it.optBoolean("has_drm") && it.optString("format_id").matches(Regex("[A-Za-z0-9_.-]+")) && it.optString("url").isNotBlank() }
        val audios = available.filter { it.optString("vcodec") == "none" && it.optString("acodec", "unknown") != "none" }
        val bestAudio = audios.maxByOrNull { it.optDouble("abr", it.optDouble("tbr", 0.0)) }
        val choices = mutableListOf<MediaChoice>()
        for (v in available.filter { (it.optString("vcodec", "unknown") != "none" && (it.optInt("height", 0) > 0 || it.optString("vcodec", "unknown") != "unknown")) }.sortedByDescending { it.optInt("height", 0) }) {
            val separate = v.optString("acodec", "unknown") == "none"
            val audio = if (separate) bestAudio else null
            val bytes = size(v, duration)?.let { n -> if (audio == null) n else size(audio, duration)?.let { n + it } }
            val ext = if (audio == null) v.optString("ext", "mp4") else "mkv"
            val selector = v.getString("format_id") + if (audio != null) "+${audio.getString("format_id")}" else ""
            val resolution = if (v.optInt("width", 0) > 0) "${v.optInt("width")}×${v.optInt("height")}" else "${v.optInt("height", 0)}p"
            val label = "$resolution · ${v.optString("vcodec", "配信元形式")} · $ext · ${sizeLabel(bytes)}" +
                v.optString("format_note").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            choices.add(MediaChoice(selector, label, false, bytes, ext))
        }
        for (a in audios.sortedByDescending { it.optDouble("abr", it.optDouble("tbr", 0.0)) }) {
            val bytes = size(a, duration)
            choices.add(MediaChoice(a.getString("format_id"), "${audioLabel(a)} · ${sizeLabel(bytes)}", true, bytes, a.optString("ext", "m4a")))
        }
        // Muxed-only sources can also be extracted without re-encoding using yt-dlp's best audio mode.
        if (audios.isEmpty()) for (a in available.filter { it.optString("acodec", "unknown") != "none" }) {
            val bytes = size(a, duration)
            choices.add(MediaChoice(a.getString("format_id"), "${audioLabel(a)} · ${sizeLabel(bytes)}（取得通信量・音声抽出）", true, bytes, "best"))
        }
        require(choices.isNotEmpty()) { "保存できる動画・音声の形式が見つかりませんでした。" }
        return MediaInfo(url, json.optString("title", "動画・音声"), json.optString("thumbnail").takeIf { it.startsWith("https://") }, duration, choices,
            json.optString("uploader_id", json.optString("uploader", "投稿者不明")).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).trim('.', ' '))
    }
}
