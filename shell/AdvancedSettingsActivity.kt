package jp.muya.xsaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*

class AdvancedSettingsActivity : Activity() {
    private val access = AdvancedAccess()
    private val dialogs = mutableListOf<AlertDialog>()
    private val passwords by lazy { AdvancedPasswordStore(this) }
    private var checking = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(label: String, size: Float = 16f) = TextView(this).apply {
        text = label; textSize = size; setTextColor(Color.rgb(231, 239, 249)); setPadding(0, dp(9), 0, dp(9))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(56); setOnClickListener { action() }
    }
    private fun page(): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(Color.rgb(16, 24, 39))
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                    view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(24) + bars.bottom)
                }
                insets
            }
        }
        setContentView(ScrollView(this).apply { addView(body) })
        body.addView(text("詳細設定", 24f))
        return body
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onResume() {
        super.onResume(); access.resume()
        if (!access.unlocked) showGate()
    }
    override fun onPause() {
        access.lock(); checking = false
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
        // Do not leave account labels or password fields behind in a returning screen.
        page().addView(text("ロックされています"))
        super.onPause()
    }
    private fun passwordField(label: String) = EditText(this).apply {
        hint = label; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine = true
        isSaveEnabled = false; filters = arrayOf(InputFilter.LengthFilter(256))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private fun showGate() {
        checking = false
        val first = !passwords.configured()
        val body = page()
        body.addView(text(if (first) "初回は、このページを開くパスワードを設定してください。" else "パスワードを入力してください。"))
        val password = passwordField(if (first) "パスワード（4文字以上）" else "パスワード"); body.addView(password)
        val confirmation = if (first) passwordField("パスワード（確認）").also { body.addView(it) } else null
        val message = text(""); body.addView(message)
        val submit = button(if (first) "設定して開く" else "開く") {}
        submit.setOnClickListener {
            if (checking) return@setOnClickListener
            val chars = password.text.toString().toCharArray()
            if (chars.size < 4) { chars.fill('\u0000'); message.text = "4文字以上で入力してください。"; return@setOnClickListener }
            if (confirmation != null && password.text.toString() != confirmation.text.toString()) {
                chars.fill('\u0000'); message.text = "確認用のパスワードが一致しません。"; return@setOnClickListener
            }
            password.text.clear(); confirmation?.text?.clear()
            checking = true; submit.isEnabled = false; message.text = "確認しています…"
            val ticket = access.begin()
            Thread {
                val result = try { runCatching { if (first) { passwords.create(chars); true } else passwords.verify(chars) } }
                    finally { chars.fill('\u0000') }
                runOnUiThread {
                    if (isFinishing || isDestroyed || ticket != access.begin()) return@runOnUiThread
                    checking = false; submit.isEnabled = true
                    result.onSuccess { valid ->
                        if (valid && access.accept(ticket)) showDetails()
                        else message.text = "パスワードが違います。"
                    }.onFailure { message.text = "パスワードを確認・保存できませんでした。" }
                }
            }.start()
        }
        body.addView(submit)
        body.addView(button("戻る") { finish() })
    }
    private fun showDetails() {
        if (!access.unlocked) return
        val body = page()
        body.addView(text("サービス別ログイン・アカウント切替", 18f))
        val site = MediaLink.site("pornhub")
        val active = getSharedPreferences("service-accounts", MODE_PRIVATE).getString(site.id, null)
        val accounts = runCatching { AccountStore(this).list() }
        val label = accounts.getOrNull()?.find { it.service == site.id && it.id == active }?.label ?: "ログインせず使う"
        body.addView(button("${site.name}：$label") { chooseAccount(site) })
        body.addView(text("このページを離れるとロックします。保存したログイン情報は引き続き利用できます。"))
        body.addView(button("閉じてロック") { finish() })
    }
    private fun show(builder: AlertDialog.Builder) {
        if (!access.unlocked || isFinishing) return
        val dialog = builder.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialogs.add(dialog); dialog.show()
    }
    private fun busy() = StateHub.state.busy || jp.muya.xsaver.legacy.StateHub.state.busy || UpdateActivity.running
    private fun chooseAccount(site: MediaSite) {
        if (!access.unlocked) return
        if (busy()) { toast("保存・更新が終わってから変更してください。"); return }
        val accounts = runCatching { AccountStore(this).list().filter { it.service == site.id } }
            .getOrElse { toast("アカウントを読み込めませんでした。"); return }
        val prefs = getSharedPreferences("service-accounts", MODE_PRIVATE)
        val choices = listOf("ログインせず使う") + accounts.map { it.label } + listOf("＋ ログイン", "保存したアカウントを管理")
        show(AlertDialog.Builder(this).setTitle(site.name).setItems(choices.toTypedArray()) { _, i ->
            if (!access.unlocked) return@setItems
            when {
                i == 0 -> { prefs.edit().putString(site.id, "").apply(); showDetails() }
                i <= accounts.size -> { prefs.edit().putString(site.id, accounts[i - 1].id).apply(); showDetails() }
                i == accounts.size + 1 -> login(site, null)
                else -> show(AlertDialog.Builder(this).setTitle("管理するアカウント").setItems(accounts.map { it.label }.toTypedArray()) { _, index ->
                    val account = accounts[index]
                    show(AlertDialog.Builder(this).setTitle(account.label).setItems(arrayOf("名前変更", "ログインし直す", "端末から削除")) { _, action ->
                        when (action) {
                            0 -> {
                                val field = EditText(this).apply { setText(account.label); isSaveEnabled = false }
                                show(AlertDialog.Builder(this).setTitle("表示名").setView(field).setPositiveButton("保存") { _, _ ->
                                    if (access.unlocked) runCatching { AccountStore(this).save(field.text.toString().ifBlank { account.label }, account.cookies, account.id, account.service) }
                                        .onSuccess { showDetails() }.onFailure { toast("変更できませんでした。") }
                                }.setNegativeButton("戻る", null))
                            }
                            1 -> login(site, account)
                            else -> show(AlertDialog.Builder(this).setTitle("端末からログイン状態を削除しますか？").setPositiveButton("削除") { _, _ ->
                                if (access.unlocked) runCatching { AccountStore(this).remove(account.id) }.onSuccess {
                                    if (prefs.getString(site.id, null) == account.id) prefs.edit().putString(site.id, "").apply()
                                    showDetails()
                                }.onFailure { toast("削除できませんでした。") }
                            }.setNegativeButton("戻る", null))
                        }
                    })
                }.setNegativeButton("戻る", null))
            }
        }.setNegativeButton("戻る", null))
    }
    private fun login(site: MediaSite, account: SavedAccount?) {
        if (!access.unlocked || busy()) return
        startActivityForResult(Intent(this, LoginActivity::class.java).putExtra("service", site.id)
            .putExtra("accountId", account?.id).putExtra("label", account?.label), 31)
    }
    @Deprecated("Platform activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 31 && resultCode == RESULT_OK && data?.getStringExtra("service") == "pornhub") {
            getSharedPreferences("service-accounts", MODE_PRIVATE).edit().putString("pornhub", data.getStringExtra("accountId")).apply()
        }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    companion object {
        fun addEntry(body: LinearLayout, activity: Activity) {
            body.addView(Button(activity).apply {
                text = "詳細設定"; isAllCaps = false
                minHeight = (56 * resources.displayMetrics.density).toInt()
                setOnClickListener { activity.startActivity(Intent(activity, AdvancedSettingsActivity::class.java)) }
            })
        }
    }
}
