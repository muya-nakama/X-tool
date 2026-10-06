package jp.muya.xsaver

import org.junit.Assert.*
import org.junit.Test

class WebSessionTest {
    @Test fun headerBecomesDownloaderCookiesWithoutSplittingEquals() {
        val result = SessionCookies.fromHeader("auth_token=test=value; ct0=fake; unrelated=ok")
        assertTrue(result.contains("\tauth_token\ttest=value\n"))
        assertEquals(result, SessionCookies.filter(result))
    }
    @Test(expected = IllegalArgumentException::class) fun loggedOutSessionIsRejected() {
        SessionCookies.fromHeader("guest_id=fake; ct0=fake")
    }
    @Test(expected = IllegalArgumentException::class) fun missingCsrfIsRejected() {
        SessionCookies.fromHeader("auth_token=fake")
    }
    @Test fun controlCharactersDoNotCreateExtraCookieRows() {
        val result = SessionCookies.fromHeader("auth_token=fake; ct0=fake; unsafe=bad\nrow")
        assertFalse(result.contains("unsafe"))
    }
    @Test fun versionsAreNumericAndEqualVersionsAreNotUpdates() {
        assertTrue(AppUpdates.newer("1.10", "1.9"))
        assertFalse(AppUpdates.newer("1.02", "1.02"))
        assertFalse(AppUpdates.newer("1.00", "1.02"))
        assertTrue(AppUpdates.newer("2.0", "1.99"))
    }
}
