package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThumbnailPolicyTest {
    @Test fun choosesPrimaryThenLargerFallbacksWithoutStoryboardsOrDuplicates() {
        val info = JSONObject("""{"thumbnail":"https://images.example/main.jpg","thumbnails":[{"url":"https://images.example/small.jpg","width":100,"height":100},{"url":"https://images.example/sheet.jpg","rows":10,"columns":10,"width":10000,"height":10000},{"url":"https://images.example/main.jpg"},{"url":"//images.example/large.jpg","width":1280,"height":720}]}""")
        assertEquals(listOf("https://images.example/main.jpg", "https://images.example/large.jpg", "https://images.example/small.jpg"), ThumbnailPolicy.sources(info))
    }
    @Test fun thumbnailArraysWorkWhenPrimaryIsMissingAndRejectUnsafeUrls() {
        assertEquals(listOf("https://images.example/cover.jpg"), ThumbnailPolicy.sources(JSONObject("""{"thumbnails":[{"url":"file:///private/cover.jpg"},{"url":"https://images.example/cover.jpg"}]}""")))
        for (url in listOf("http://images.example/x", "https://user:pass@images.example/x", "https://127.0.0.1/x", "https://host.local/x", "data:image/png;base64,abc")) assertNull(ThumbnailPolicy.imageUrl(url))
        assertTrue(ThumbnailPolicy.sources(JSONObject()).isEmpty())
    }
    @Test fun imageHeadersPreserveRefererButNeverAuthentication() {
        val info = JSONObject("""{"http_headers":{"User-Agent":"Source browser","Referer":"https://source.example/","Cookie":"secret","Authorization":"secret","Origin":"https://source.example/"}}""")
        val headers = ThumbnailPolicy.headers(info, "https://fallback.example/watch")
        assertEquals("https://source.example/", headers["Referer"])
        assertEquals("Source browser", headers["User-Agent"])
        assertFalse(headers.containsKey("Cookie")); assertFalse(headers.containsKey("Authorization"))
        assertEquals("https://fallback.example/watch", ThumbnailPolicy.headers(JSONObject(), "https://fallback.example/watch")["Referer"])
        assertEquals("Mozilla/5.0", ThumbnailPolicy.headers(JSONObject("""{"http_headers":{"User-Agent":"bad\r\nheader"}}"""), "https://source.example/")["User-Agent"])
    }
    @Test fun gofileCoverAndRefererReachTheDisplayedMedia() {
        val response = JSONObject("""{"code":0,"data":{"rules":{"status":"0"},"info":{"netDiskInfo":{"fileUrl":"https://media.example/video.m3u8","coverImage":"https://images.example/cover.jpg","length":"60"}}}}""")
        val cover = GofileCenter.parse("test123", response, "TwFiles").copy(referer = "https://twfiles.com/")
        val raw = JSONObject("""{"formats":[{"format_id":"v","height":720,"vcodec":"avc1","acodec":"aac","url":"https://media.example/video.m3u8"}]}""")
        val media = MediaFormats.parse("https://twfiles.com/test123", cover.annotate(raw))
        assertEquals("https://images.example/cover.jpg", media.thumbnail)
        assertEquals(listOf(media.thumbnail), media.thumbnails)
        assertEquals("https://twfiles.com/", media.thumbnailHeaders["Referer"])
        assertEquals("TwFiles", media.uploader)
    }
    @Test fun absentCoverDoesNotRemoveEngineThumbnailOrBlockFormats() {
        val cover = GofileCenterMedia("test123", "https://media.example/v", "Test", 60.0)
        val info = cover.annotate(JSONObject("""{"thumbnail":"https://images.example/engine.jpg"}"""))
        assertEquals("https://images.example/engine.jpg", ThumbnailPolicy.sources(info).first())
    }
}
