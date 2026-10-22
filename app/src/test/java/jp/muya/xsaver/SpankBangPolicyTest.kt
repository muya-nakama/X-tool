package jp.muya.xsaver

import org.junit.Assert.*
import org.junit.Test

class SpankBangPolicyTest {
    @Test fun isolatesTransportToServiceHttpsHosts() {
        assertTrue(SpankBangPolicy.supports("https://jp.spankbang.com/example/video"))
        assertTrue(SpankBangPolicy.supports("https://spankbang.com/example/video"))
        listOf(null, "https://spankbang.com.evil.test/example", "https://spankbang.com@evil.test/example",
            "http://spankbang.com/example", "https://gofile.center/example", "https://spankbang.com:444/example").forEach {
            assertFalse(SpankBangPolicy.supports(it))
        }
    }
    @Test fun readsProgressWithoutExposingRawLines() {
        assertEquals(12.5f, SpankBangPolicy.percent("[download]  12.5% of 4MiB")!!, 0f)
        assertEquals(100f, SpankBangPolicy.percent("[download] 100% of 4MiB")!!, 0f)
        assertNull(SpankBangPolicy.percent("ERROR https://example.test/?secret=hidden"))
    }
}
