package jp.muya.xsaver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpankBangRuntimeTest {
    @Test fun nativeBrowserTransportAndExistingEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = SpankBangEngine.selfCheck(context)
        assertTrue(output.contains("SPANKBANG_TRANSPORT_OK"))
        YoutubeDL.init(context)
        val request = YoutubeDLRequest(emptyList()).addOption("--list-impersonate-targets")
        val targets = SpankBangEngine.execute(context, request, "transport-targets", {})
        assertTrue(targets.lowercase().contains("chrome"))
        val existing = YoutubeDL.execute(YoutubeDLRequest(emptyList()).addOption("--version"), "existing-engine", null)
        assertTrue(existing.out.trim().isNotEmpty())
    }
}
