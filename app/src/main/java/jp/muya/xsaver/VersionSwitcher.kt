package jp.muya.xsaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

object VersionSwitcher {
    const val CURRENT = "1.02"
    const val PREVIOUS = "1.00"
    fun target(context: Context): Class<out Activity> {
        // Keep a running download attached to the screen and service that started it.
        val previous = if (StateHub.state.busy) false
            else if (jp.muya.xsaver.legacy.StateHub.state.busy) true
            else context.getSharedPreferences("app-version", Context.MODE_PRIVATE).getBoolean("previous", false)
        return if (previous) jp.muya.xsaver.legacy.MainActivity::class.java else MainActivity::class.java
    }
    fun addTo(root: LinearLayout, activity: Activity, version: String) {
        root.addView(Button(activity).apply {
            text = "使用中：v$version" + if (version == CURRENT) "（最新版）・切り替える" else "（一世代前）・切り替える"
            isAllCaps = false
            setOnClickListener { show(activity, version) }
        })
    }
    private fun show(activity: Activity, version: String) {
        if (StateHub.state.busy || jp.muya.xsaver.legacy.StateHub.state.busy) {
            Toast.makeText(activity, "保存・更新が終わってから切り替えてください。", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("使用する版を選ぶ")
            .setSingleChoiceItems(arrayOf("最新版 v$CURRENT", "一世代前 v$PREVIOUS"), if (version == CURRENT) 0 else 1) { dialog, choice ->
                val previous = choice == 1
                activity.getSharedPreferences("app-version", Context.MODE_PRIVATE).edit().putBoolean("previous", previous).apply()
                dialog.dismiss()
                val target = if (previous) jp.muya.xsaver.legacy.MainActivity::class.java else MainActivity::class.java
                if (activity.javaClass != target) {
                    activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    activity.finish()
                }
            }.setNegativeButton("戻る", null).show()
    }
}
