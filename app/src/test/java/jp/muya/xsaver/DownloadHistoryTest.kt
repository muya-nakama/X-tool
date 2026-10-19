package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DownloadHistoryTest {
    private val file = SavedDownloadFile("content://media/external/downloads/42", "Download/MOVIE&AUDIO-TOOL/X/動画/user/", "clip.mp4", "video/mp4", 1024)
    private val key = DownloadIdentity.selection("media", "137+140", false, "mkv")
    private val record = SavedDownload(key, listOf(file), 1000)

    @Test fun noRecordAllowsFirstDownload() {
        assertEquals(DownloadPresence.NEW, DownloadHistory.inspect(null) { error("No file to probe") }.presence)
    }
    @Test fun presentFileStopsDuplicateAndCanBeOpened() {
        val found = DownloadHistory.inspect(record) { SavedFileStatus.PRESENT }
        assertEquals(DownloadPresence.EXISTS, found.presence)
        assertEquals(file, found.file)
    }
    @Test fun missingFileAllowsConfirmedRedownload() {
        assertEquals(DownloadPresence.REMOVED, DownloadHistory.inspect(record) { SavedFileStatus.MISSING }.presence)
    }
    @Test fun providerOrPermissionFailureIsNotMisreportedAsRemoval() {
        assertEquals(DownloadPresence.UNAVAILABLE, DownloadHistory.inspect(record) { SavedFileStatus.UNAVAILABLE }.presence)
    }
    @Test fun anyMissingOutputRequiresRedownload() {
        val multiple = record.copy(files = listOf(file, file.copy(uri = "content://media/external/downloads/43")))
        assertEquals(DownloadPresence.REMOVED, DownloadHistory.inspect(multiple) {
            if (it == file) SavedFileStatus.PRESENT else SavedFileStatus.MISSING
        }.presence)
    }
    @Test fun unknownOutputTakesPrecedenceOverMissingOutput() {
        val multiple = record.copy(files = listOf(file, file.copy(name = "other.mp4")))
        assertEquals(DownloadPresence.UNAVAILABLE, DownloadHistory.inspect(multiple) {
            if (it == file) SavedFileStatus.MISSING else SavedFileStatus.UNAVAILABLE
        }.presence)
    }
    @Test fun sourceIdentityIgnoresLinkAliasesAndExpiringStreamUrls() {
        val a = JSONObject().put("extractor_key", "Twitter").put("id", "123").put("url", "https://cdn.example/a?token=one")
        val b = JSONObject().put("extractor_key", "twitter").put("id", "123").put("url", "https://cdn.example/a?token=two")
        assertEquals(DownloadIdentity.media(a, "https://x.com/user/status/123?s=1"), DownloadIdentity.media(b, "https://twitter.com/user/status/123"))
    }
    @Test fun sourcesAndMediaIdsDoNotCollide() {
        fun id(site: String, item: String) = DownloadIdentity.media(JSONObject().put("extractor_key", site).put("id", item), "https://example.com/video")
        assertNotEquals(id("Twitter", "123"), id("Youtube", "123"))
        assertNotEquals(id("Twitter", "123"), id("Twitter", "124"))
    }
    @Test fun audioAndOtherQualityRemainSeparateDownloads() {
        assertNotEquals(key, DownloadIdentity.selection("media", "137+140", true, "mkv"))
        assertNotEquals(key, DownloadIdentity.selection("media", "136+140", false, "mkv"))
        assertNotEquals(key, DownloadIdentity.selection("media", "137+140", false, "mp4"))
    }
    @Test fun recordsRoundTripAcrossAppRestartAndUpdates() {
        assertEquals(record, SavedDownload.fromJson(JSONObject(record.toJson().toString())))
    }
    @Test fun savedMetadataDoesNotContainNetworkUrlsOrCookies() {
        val json = record.toJson().toString()
        assertFalse(json.contains("https://")); assertFalse(json.contains("cookies")); assertFalse(json.contains("accountId"))
    }
    @Test fun malformedRecordsAreRejected() {
        val invalid = record.toJson().put("key", "bad")
        assertThrows(IllegalArgumentException::class.java) { SavedDownload.fromJson(invalid) }
    }
}
