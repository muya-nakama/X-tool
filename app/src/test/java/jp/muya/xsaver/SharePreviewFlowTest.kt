package jp.muya.xsaver

import org.junit.Assert.*
import org.junit.Test

class SharePreviewFlowTest {
    @Test fun oneShareLoadsOnceAndPreviewsOnce() {
        val flow = SharePreviewFlow(); flow.accept("https://source.example/a")
        val request = flow.nextLoad()!!
        assertNull(flow.nextLoad())
        assertFalse(flow.takePreview(request.url))
        flow.complete(request.id, request.url, true)
        assertTrue(flow.takePreview(request.url))
        assertFalse(flow.takePreview(request.url))
        assertNull(flow.nextLoad())
    }
    @Test fun newestShareIgnoresOldResultsIncludingTheSameUrl() {
        val flow = SharePreviewFlow(); val url = "https://source.example/a"
        flow.accept(url); val old = flow.nextLoad()!!
        flow.accept(url); val current = flow.nextLoad()!!
        assertNotEquals(old.id, current.id)
        assertFalse(flow.acceptsResult(old.id))
        flow.complete(old.id, url, true)
        assertFalse(flow.takePreview(url))
        flow.complete(current.id, url, true)
        assertTrue(flow.takePreview(url))
    }
    @Test fun failureUnsupportedAndManualEditCancelAutomaticPlayback() {
        for (mode in 0..2) {
            val flow = SharePreviewFlow(); flow.accept("https://source.example/a"); val request = flow.nextLoad()!!
            when (mode) {
                0 -> flow.complete(request.id, null, false)
                1 -> flow.complete(request.id, request.url, false)
                else -> flow.cancel()
            }
            assertNull(flow.pendingUrl); assertFalse(flow.takePreview(request.url)); assertNull(flow.nextLoad())
        }
    }
    @Test fun unrelatedJobDoesNotConsumeQueuedShare() {
        val flow = SharePreviewFlow(); flow.accept("https://source.example/b")
        flow.complete("other-job", "https://source.example/a", true)
        assertEquals("https://source.example/b", flow.nextLoad()!!.url)
    }
}
