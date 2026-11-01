package jp.muya.xsaver

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateEntryTest {
    private fun buttons(view: View): List<Button> = (if (view is Button) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList())
    @Test fun updateButtonRemainsAvailableBeforeRemoteVersionIsKnown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            try {
                val settings = buttons(activity.window.decorView).first { it.text.toString() == "設定" }
                settings.performClick()
                val field = MainActivity::class.java.getDeclaredField("settingsDialog").apply { isAccessible = true }
                val dialog = field.get(activity) as AlertDialog
                val update = buttons(dialog.window!!.decorView).find { it.text.toString() == "アプリを更新" }
                assertNotNull("Update entry must remain visible even while checking or on the current version", update)
                assertTrue(update!!.isEnabled)
                dialog.dismiss()
            } finally { activity.finish() }
        }
    }
}
