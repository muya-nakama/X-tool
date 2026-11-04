package jp.muya.xsaver

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake metadata only. Block the player Activity so this test makes no media requests. */
@RunWith(AndroidJUnit4::class)
class SharePreviewTest {
    @Test fun completedSharedLinkOpensPreviewOnceWithoutReadOrPlayClicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(PreviewActivity::class.java.name, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        val url = "https://source.example/shared"
        var activity: MainActivity? = null
        try {
            StateHub.publish(DownloadState(busy = true, message = "Test queue"))
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Shared text $url")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var result: DownloadState
            instrumentation.runOnMainSync {
                val field = MainActivity::class.java.getDeclaredField("shareFlow").apply { isAccessible = true }
                val flow = field.get(activity) as SharePreviewFlow
                assertEquals(url, flow.pendingUrl)
                // Simulate the queued analysis starting, then return its matching metadata.
                val request = flow.nextLoad()!!
                val track = PreviewTrack("https://media.example/test.mp4", null, emptyMap())
                val info = MediaInfo(url, "Share test", null, 2.0,
                    listOf(MediaChoice("v", "Video", false, null, "mp4")), "Test", preview = PreviewPlan(track, null))
                result = DownloadState(preview = info, requestId = request.id)
                StateHub.publish(result)
            }
            instrumentation.waitForIdleSync()
            assertEquals("Preview should open automatically", 1, monitor.hits)
            StateHub.publish(result)
            instrumentation.waitForIdleSync()
            assertEquals("Returning/replaying state must not reopen preview", 1, monitor.hits)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            StateHub.publish(DownloadState())
        }
    }
}
