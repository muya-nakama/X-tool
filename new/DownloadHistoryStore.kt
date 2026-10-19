package jp.muya.xsaver

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Shared by the screen and service; lives through same-signature APK updates. */
class DownloadHistoryStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "download-history-v1.json"))
    private val resolver = context.contentResolver
    companion object { private val lock = Any() }

    private fun read(): List<SavedDownload> {
        val text = try { file.readFully().toString(Charsets.UTF_8) }
            catch (_: FileNotFoundException) { return emptyList() }
        val json = JSONObject(text)
        require(json.getInt("version") == 1)
        val rows = json.getJSONArray("downloads")
        return (0 until rows.length()).map { SavedDownload.fromJson(rows.getJSONObject(it)) }
    }
    fun inspect(key: String): DownloadLookup = synchronized(lock) {
        DownloadHistory.inspect(read().firstOrNull { it.key == key }, ::probe)
    }
    fun record(record: SavedDownload) = synchronized(lock) {
        // Replace this selection's old location only after all files have been published successfully.
        val records = read().filterNot { it.key == record.key } + record
        val json = JSONObject().put("version", 1).put("downloads", JSONArray().apply { records.forEach { put(it.toJson()) } })
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun probe(saved: SavedDownloadFile): SavedFileStatus {
        val uri = Uri.parse(saved.uri)
        return try {
            val columns = arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE, MediaStore.Downloads.IS_PENDING)
            val located = resolver.query(uri, columns, null, null, null)?.use { row ->
                if (!row.moveToFirst()) return SavedFileStatus.MISSING
                val folder = row.getString(0).orEmpty().trimEnd('/') + "/"
                if (folder != saved.folder.trimEnd('/') + "/" || row.getString(1) != saved.name ||
                    row.getLong(2) != saved.bytes || row.getInt(3) != 0) return SavedFileStatus.MISSING
                true
            } ?: return SavedFileStatus.UNAVAILABLE
            if (located) resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize == 0L) SavedFileStatus.MISSING else SavedFileStatus.PRESENT
            } ?: SavedFileStatus.MISSING else SavedFileStatus.MISSING
        } catch (_: FileNotFoundException) { SavedFileStatus.MISSING }
        catch (_: Exception) { SavedFileStatus.UNAVAILABLE }
    }
    /** Capture the actual MediaStore name: Android may rename a conflicting destination. */
    fun describe(uri: Uri, mime: String): SavedDownloadFile {
        val columns = arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE)
        return resolver.query(uri, columns, null, null, null)?.use { row ->
            check(row.moveToFirst())
            SavedDownloadFile(uri.toString(), row.getString(0), row.getString(1), mime, row.getLong(2)).also { check(it.bytes > 0) }
        } ?: throw IllegalStateException("保存したファイルを確認できませんでした。")
    }
}
