package jp.muya.xsaver

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Stable identifiers only; expiring media URLs and account/session data are never stored. */
object DownloadIdentity {
    private fun digest(parts: List<String>) = MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(parts).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun media(metadata: JSONObject, fallbackUrl: String): String {
        val extractor = metadata.optString("extractor_key").ifBlank { metadata.optString("extractor") }.lowercase()
        val id = metadata.optString("id")
        return if (extractor.isNotBlank() && id.isNotBlank()) digest(listOf("media-v1", extractor, id))
        else {
            val uri = URI(fallbackUrl)
            val clean = URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.path, uri.query, null).toASCIIString()
            digest(listOf("url-v1", clean))
        }
    }
    fun selection(media: String, selector: String, audio: Boolean, extension: String): String =
        digest(listOf("selection-v1", media, selector, audio.toString(), extension))
}

data class SavedDownloadFile(val uri: String, val folder: String, val name: String, val mime: String, val bytes: Long) {
    fun toJson() = JSONObject().put("uri", uri).put("folder", folder).put("name", name).put("mime", mime).put("bytes", bytes)
    companion object {
        fun fromJson(json: JSONObject) = SavedDownloadFile(json.getString("uri"), json.getString("folder"),
            json.getString("name"), json.getString("mime"), json.getLong("bytes")).also {
            require(it.uri.startsWith("content://media/") && it.folder.startsWith("Download/MOVIE&AUDIO-TOOL/") && it.bytes > 0)
        }
    }
}

data class SavedDownload(val key: String, val files: List<SavedDownloadFile>, val savedAt: Long) {
    fun toJson() = JSONObject().put("key", key).put("savedAt", savedAt)
        .put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
    companion object {
        fun fromJson(json: JSONObject): SavedDownload {
            val rows = json.getJSONArray("files")
            return SavedDownload(json.getString("key"), (0 until rows.length()).map { SavedDownloadFile.fromJson(rows.getJSONObject(it)) },
                json.getLong("savedAt")).also { require(it.key.matches(Regex("[a-f0-9]{64}")) && it.files.isNotEmpty()) }
        }
    }
}

enum class SavedFileStatus { PRESENT, MISSING, UNAVAILABLE }
enum class DownloadPresence { NEW, EXISTS, REMOVED, UNAVAILABLE }
data class DownloadLookup(val presence: DownloadPresence, val file: SavedDownloadFile? = null)

object DownloadHistory {
    const val EXISTS_MESSAGE = "既にこのファイルは存在します"
    const val REMOVED_MESSAGE = "ファイルは削除または移動されています"
    const val UNAVAILABLE_MESSAGE = "保存先のファイルを確認できませんでした。時間をおいて再度お試しください。"
    fun inspect(record: SavedDownload?, probe: (SavedDownloadFile) -> SavedFileStatus): DownloadLookup {
        if (record == null) return DownloadLookup(DownloadPresence.NEW)
        val statuses = record.files.map(probe)
        // An access/provider error must never be described as deletion or movement.
        return when {
            statuses.any { it == SavedFileStatus.UNAVAILABLE } -> DownloadLookup(DownloadPresence.UNAVAILABLE)
            statuses.any { it == SavedFileStatus.MISSING } -> DownloadLookup(DownloadPresence.REMOVED)
            else -> DownloadLookup(DownloadPresence.EXISTS, record.files.first())
        }
    }
}
