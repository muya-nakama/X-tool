package jp.muya.xsaver

import org.junit.Assert.*
import org.junit.Test

class XLinkTest {
    @Test fun shareTextAndTrackingAreNormalized() {
        assertEquals("https://x.com/user/status/123", XLink.parse("動画はこちら https://twitter.com/user/status/123?s=20&t=abc"))
        assertEquals("https://x.com/i/spaces/1AbCd", XLink.parse("https://x.com/i/spaces/1AbCd。"))
        assertTrue(XLink.isSpace("https://x.com/i/spaces/1AbCd"))
    }
    @Test fun unrelatedAndDeceptiveUrlsAreRejected() {
        listOf("https://x.com.evil.test/user/status/123", "https://x.com@evil.test/user/status/123",
            "https://evil.test@x.com/user/status/123", "https://x.com:8443/user/status/123",
            "https://x.com/user", "https://x.com/i/spaces/", "https://t.co/abc", "hello").forEach { assertNull(XLink.parse(it)) }
    }
    @Test fun sharedMediaAndWebStatusWork() {
        assertEquals("https://x.com/u/status/123/video/1", XLink.parse("https://x.com/u/status/123/video/1"))
        assertEquals("https://x.com/i/web/status/123", XLink.parse("https://mobile.twitter.com/i/web/status/123"))
    }
}
