package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreviewPolicyTest {
    @Test fun modestCombinedStreamWinsOverLargeOrSplitVideo() {
        val raw = JSONObject("""{"formats":[{"url":"https://media.example/big.mp4","height":2160,"vcodec":"avc1","acodec":"aac"},{"url":"https://media.example/split.mp4","height":480,"vcodec":"avc1","acodec":"none"},{"url":"https://media.example/muxed.mp4","height":480,"vcodec":"avc1","acodec":"aac"}]}""")
        val plan = PreviewPolicy.select("https://source.example/watch", raw)!!
        assertEquals("https://media.example/muxed.mp4", plan.video!!.url)
        assertNull(plan.audio)
    }
    @Test fun splitVideoAndAudioArePairedAndHeadersRemainSeparate() {
        val raw = JSONObject("""{"http_headers":{"Referer":"https://source.example/"},"formats":[{"url":"https://video.example/v","height":720,"vcodec":"avc1","acodec":"none","http_headers":{"Referer":"https://video.example/","Cookie":"secret"}},{"url":"https://audio.example/a","vcodec":"none","acodec":"aac","abr":128}]}""")
        val plan = PreviewPolicy.select("https://source.example/watch", raw)!!
        assertEquals("https://video.example/v", plan.video!!.url)
        assertEquals("https://audio.example/a", plan.audio!!.url)
        assertEquals("https://video.example/", plan.video!!.headers["Referer"])
        assertEquals("https://source.example/", plan.audio!!.headers["Referer"])
        assertFalse(plan.video!!.headers.containsKey("Cookie"))
        assertNull(PreviewPolicy.audio("https://source.example/watch", raw)!!.video)
    }
    @Test fun hlsTypeSurvivesExtensionlessSignedUrl() {
        val raw = JSONObject("""{"formats":[{"url":"https://media.example/signed","protocol":"m3u8_native","height":360,"vcodec":"avc1","acodec":"aac"}]}""")
        assertEquals("application/x-mpegURL", PreviewPolicy.select("https://source.example/watch", raw)!!.video!!.mime)
    }
    @Test fun drmFragmentListsAndUnsafeUrlsAreNotOffered() {
        val raw = JSONObject("""{"formats":[{"url":"https://media.example/drm","height":720,"vcodec":"avc1","has_drm":true},{"url":"https://media.example/fragments","protocol":"http_dash_segments","height":720,"vcodec":"avc1","fragments":[{}]},{"url":"file:///private/video.mp4","height":720,"vcodec":"avc1"}]}""")
        assertNull(PreviewPolicy.select("https://source.example/watch", raw))
        assertNull(PreviewPolicy.select("https://source.example/watch", JSONObject("""{"has_drm":true,"url":"https://media.example/v","height":720,"vcodec":"avc1"}""")))
    }
    @Test fun missingAudioIsNotPresentedAsACompleteVideo() {
        assertNull(PreviewPolicy.select("https://source.example/watch", JSONObject("""{"url":"https://media.example/v","height":720,"vcodec":"avc1","acodec":"none"}""")))
        val audio = PreviewPolicy.select("https://source.example/watch", JSONObject("""{"url":"https://media.example/a","vcodec":"none","acodec":"aac"}"""))!!
        assertNull(audio.video); assertNotNull(audio.audio)
    }
}
