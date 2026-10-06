package jp.muya.xsaver

import org.junit.Assert.*
import org.junit.Test

class SessionCookiesTest {
    @Test fun onlyXCookiesArePassedToDownloader() {
        val input = "# Netscape HTTP Cookie File\n" +
            ".x.com\tTRUE\t/\tTRUE\t0\tauth_token\tfictional-test-token\n" +
            ".unrelated.test\tTRUE\t/\tTRUE\t0\tsecret\tdiscard-this\n" +
            "#HttpOnly_.twitter.com\tTRUE\t/\tTRUE\t0\tct0\tfictional-test-token\n"
        val result = SessionCookies.filter(input)
        assertTrue(result.contains("auth_token"))
        assertTrue(result.contains("#HttpOnly_.twitter.com"))
        assertFalse(result.contains("unrelated.test"))
        assertFalse(result.contains("discard-this"))
    }
    @Test(expected = IllegalArgumentException::class) fun arbitraryTextIsRejected() {
        SessionCookies.filter("not a cookie export")
    }
}
