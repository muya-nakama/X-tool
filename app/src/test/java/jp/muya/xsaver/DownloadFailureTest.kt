package jp.muya.xsaver

import org.junit.Assert.*
import org.junit.Test

class DownloadFailureTest {
    @Test fun timeoutRetainsCauseWithoutExposingUrl() {
        val error = RuntimeException("Unable to download https://example.com/private?token=SECRET", java.net.SocketTimeoutException("Read timed out"))
        val message = DownloadFailure.message(error)
        assertTrue(message.contains("待ち時間"))
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains("https://"))
    }
    @Test fun rateLimitIsDistinctFromLogin() {
        val message = DownloadFailure.message(RuntimeException("HTTP Error 429: Too Many Requests"))
        assertTrue(message.contains("取得回数"))
        assertFalse(message.contains("ログイン"))
    }
    @Test fun forbiddenDoesNotAssertLoginWillFixIt() {
        assertTrue(DownloadFailure.message(RuntimeException("HTTP Error 403: Forbidden")).contains("サイト側の制限"))
    }
    @Test fun cookieInstructionGetsLoginGuidance() {
        assertTrue(DownloadFailure.message(RuntimeException("Sign in. Use --cookies-from-browser or --cookies")).contains("ログイン"))
    }
    @Test fun originalValidationMessageIsPreserved() {
        assertEquals("保存先に書き込めませんでした。", DownloadFailure.message(IllegalStateException("保存先に書き込めませんでした。")))
    }
}
