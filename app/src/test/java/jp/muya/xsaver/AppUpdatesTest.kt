package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun manifest(version: String): String = JSONObject().put("version", version)
        .put("url", "https://github.com/muya-nakama/X-tool/releases/tag/v$version").toString()
    @Test fun repeatedChecksFetchAgainWithDifferentCacheKeys() {
        val requests = mutableListOf<String>()
        val read: (String) -> String = { url -> requests.add(url); manifest(if (requests.size == 1) "1.13" else "1.14") }
        assertEquals("1.13", AppUpdates.check(read).version)
        assertEquals("1.14", AppUpdates.check(read).version)
        assertEquals(2, requests.size)
        assertNotEquals(requests[0], requests[1])
        requests.forEach { assertTrue(it.startsWith("https://raw.githubusercontent.com/muya-nakama/X-tool/main/app-update.json?check=")) }
    }
    @Test fun staleFeedIsNotLabeledLatest() {
        assertTrue(AppUpdates.status("1.14", "1.13").contains("更新できます"))
        assertTrue(AppUpdates.status("1.14", "1.14").contains("最新版"))
        assertFalse(AppUpdates.status("1.13", "1.14").contains("最新版"))
        assertTrue(AppUpdates.status("1.13", "1.14").contains("もう一度確認"))
    }
    @Test(expected = IllegalArgumentException::class) fun freshRequestsStillRejectForeignReleaseLinks() {
        AppUpdates.check { JSONObject(manifest("1.14")).put("url", "https://evil.example/download").toString() }
    }
}
