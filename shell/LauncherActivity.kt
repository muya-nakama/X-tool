package jp.muya.xsaver

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class LauncherActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val target = VersionSwitcher.target(this)
        if (target != MainActivity::class.java) StartupUpdates.check(this)
        val next = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (intent.action == Intent.ACTION_SEND) {
            next.action = Intent.ACTION_SEND
            next.type = "text/plain"
            next.putExtra(Intent.EXTRA_TEXT, intent.getStringExtra(Intent.EXTRA_TEXT))
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
        startActivity(next)
        finish()
    }
}
