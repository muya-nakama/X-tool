package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaFormatsTest {
    @Test fun xUnknownCodecsStillExposeAudioAndMuxedHttpVideo() {
        val info = JSONObject("""{"duration":8,"formats":[{"format_id":"hls-audio-128000-Audio","vcodec":"none","ext":"mp4","url":"https://example.test/audio","tbr":128},{"format_id":"http-2176","height":1280,"ext":"mp4","url":"https://example.test/muxed","tbr":2176},{"format_id":"hls-2273","height":1280,"vcodec":"avc1","acodec":"none","ext":"mp4","url":"https://example.test/video","tbr":2273}]}""")
        val result = MediaFormats.parse("https://x.com/user/status/123", info)
        assertTrue(result.choices.any { it.audio && it.selector == "hls-audio-128000-Audio" })
        assertTrue(result.choices.any { !it.audio && it.selector == "http-2176" })
        assertTrue(result.choices.any { !it.audio && it.selector == "hls-2273+hls-audio-128000-Audio" })
    }

    @Test fun mergedVideoIncludesAudioSizeAndKeepsFormatIds() {
        val info = JSONObject("""{"duration":10,"formats":[{"format_id":"137","vcodec":"avc1","acodec":"none","height":1080,"ext":"mp4","url":"https://example.test/v","filesize":1000},{"format_id":"140","vcodec":"none","acodec":"aac","abr":128,"ext":"m4a","url":"https://example.test/a","filesize":200}]}""")
        val result = MediaFormats.parse("https://youtube.com/watch?v=test", info)
        val video = result.choices.first { !it.audio }
        assertEquals("137+140", video.selector); assertEquals(1200L, video.bytes); assertEquals("mkv", video.extension)
        assertEquals("140", result.choices.first { it.audio }.selector)
    }
    @Test fun unknownAudioSizeMakesMergedSizeUnknown() {
        val info = JSONObject("""{"formats":[{"format_id":"v","vcodec":"avc1","acodec":"none","url":"https://example.test/v","filesize":1000},{"format_id":"a","vcodec":"none","acodec":"opus","url":"https://example.test/a"}]}""")
        assertNull(MediaFormats.parse("https://example.test/video", info).choices.first { !it.audio }.bytes)
    }
    @Test fun bitrateEstimatesBytesAndDoesNotInventHiResOrSpatialAudio() {
        val stereo = JSONObject("""{"tbr":128,"acodec":"aac","asr":48000,"audio_channels":2}""")
        assertEquals(160000L, MediaFormats.size(stereo, 10.0))
        assertFalse(MediaFormats.audioLabel(stereo).contains("ハイレゾ"))
        val surround = JSONObject("""{"acodec":"eac3","asr":48000,"audio_channels":6}""")
        assertTrue(MediaFormats.audioLabel(surround).contains("6ch"))
        assertFalse(MediaFormats.audioLabel(surround).contains("立体音響"))
        val hi = JSONObject("""{"acodec":"flac","asr":96000,"audio_bits_per_sample":24}""")
        assertTrue(MediaFormats.audioLabel(hi).contains("ハイレゾ"))
        val atmos = JSONObject("""{"acodec":"eac3","format_note":"Dolby Atmos"}""")
        assertTrue(MediaFormats.audioLabel(atmos).contains("立体音響"))
    }
    @Test fun drmFormatsAreExcludedAndLiveSourcesRejected() {
        val info = JSONObject("""{"formats":[{"format_id":"a","vcodec":"none","acodec":"aac","has_drm":true,"url":"https://example.test/a"}]}""")
        assertTrue(runCatching { MediaFormats.parse("https://example.test/v", info) }.isFailure)
        assertTrue(runCatching { MediaFormats.parse("https://example.test/v", JSONObject("""{"is_live":true}""")) }.isFailure)
    }
    @Test fun serviceDetectionAndSessionsStaySeparated() {
        assertEquals("youtube", MediaLink.detect("https://youtu.be/123")?.id)
        assertEquals("tiktok", MediaLink.detect("https://vm.tiktok.com/123")?.id)
        assertEquals("pornhub", MediaLink.detect("https://www.pornhub.com/view_video.php?viewkey=test")?.id)
        assertNull(MediaLink.detect("https://x.com.evil.test/v"))
        assertNull(MediaLink.parse("https://user:pass@youtube.com/v"))
        assertNull(MediaLink.parse("https://127.0.0.1/video"))
        val cookies = "# Netscape HTTP Cookie File\n.instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tfake\n.x.com\tTRUE\t/\tTRUE\t0\tauth_token\tfake\n"
        assertFalse(SessionCookies.filter(cookies, "instagram").contains("auth_token"))
        assertFalse(SessionCookies.filter(cookies, "x").contains("sessionid"))
    }
}
