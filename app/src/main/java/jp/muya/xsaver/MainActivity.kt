package jp.muya.xsaver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var save: Button
    private lateinit var cancel: Button
    private lateinit var open: Button
    private lateinit var update: Button
    private lateinit var accountsButton: Button
    private lateinit var appUpdate: Button
    private var checkingUpdate = false
    private var selectedAccount: String? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val listener: (DownloadState) -> Unit = { render(it) }
    private val ink = Color.rgb(233, 241, 252)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(16, 24, 39))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            else null
            if (bars != null) v.setPadding(dp(22), dp(16) + bars.top, dp(22), dp(24) + bars.bottom)
            insets
        }
        fun text(label: String, size: Float, muted: Boolean = false): TextView = TextView(this).apply {
            text = label; textSize = size; setTextColor(if (muted) Color.rgb(159, 180, 207) else ink)
            setPadding(0, dp(8), 0, dp(8))
        }
        VersionSwitcher.addTo(root, this, VersionSwitcher.CURRENT)
        root.addView(text("X保存", 30f).apply { setTypeface(null, Typeface.BOLD) })
        root.addView(text("動画・録音済みスペースを、自動で判別。", 15f, true))
        root.addView(text("保存するリンク", 14f))
        input = EditText(this).apply {
            hint = "https://x.com/…"; textSize = 16f; setTextColor(ink)
            setHintTextColor(Color.rgb(137, 156, 182))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(Color.rgb(28, 40, 59)); cornerRadius = dp(12).toFloat() }
            isSaveEnabled = false // Do not put the URL in saved activity state.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        fun button(label: String): Button = Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f; minHeight = dp(56)
        }
        root.addView(button("リンクを貼り付け").apply {
            setOnClickListener {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                if (clip != null && clip.itemCount > 0) input.setText(clip.getItemAt(0).coerceToText(this@MainActivity))
            }
        })
        save = button("保存する").apply { setOnClickListener { confirmDownload() } }
        root.addView(save)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        status = text("", 16f)
        root.addView(status)
        cancel = button("キャンセル").apply {
            setOnClickListener { startService(Intent(this@MainActivity, DownloadService::class.java).setAction(DownloadService.CANCEL)) }
        }
        root.addView(cancel)
        open = button("保存したファイルを開く").apply {
            setOnClickListener {
                val s = StateHub.state
                s.file?.let { uri ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, s.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        .onFailure { Toast.makeText(this@MainActivity, "対応する再生アプリがありません。マイファイルから開いてください。", Toast.LENGTH_LONG).show() }
                }
            }
        }
        root.addView(open)
        root.addView(text("保存先\nDownload / X保存 / 動画 または スペース", 14f, true))
        root.addView(text("保存履歴は残しません。一時ファイルは処理後に削除します。", 13f, true))
        selectedAccount = getPreferences(MODE_PRIVATE).getString("accountId", null)
        accountsButton = button("アカウントを選ぶ").apply { setOnClickListener { chooseAccount() } }
        root.addView(accountsButton)
        appUpdate = button("アプリの更新を確認").apply { setOnClickListener { checkAppUpdate() } }
        root.addView(appUpdate)
        update = button("取得エンジンを更新").apply {
            setOnClickListener { launchService(Intent(this@MainActivity, DownloadService::class.java).setAction(DownloadService.UPDATE)) }
        }
        root.addView(update)
        root.addView(text("v1.02 · 仲間六夜 feat. ChatGPT\n公開動画・録音済みスペース用。アカウントは端末内に暗号化して保存します。ダウンロード履歴は残しません。", 12f, true))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        receiveShare(intent)
    }

    override fun onStart() { super.onStart(); refreshAccount(); StateHub.listen(listener) }
    override fun onStop() { StateHub.remove(listener); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    private fun receiveShare(incoming: Intent) {
        if (incoming.action == Intent.ACTION_SEND) {
            input.setText(XLink.parse(incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()).orEmpty())
            incoming.removeExtra(Intent.EXTRA_TEXT)
        }
    }
    private fun confirmDownload() {
        if (StateHub.state.busy) return
        val url = XLink.parse(input.text.toString())
        if (url == null) { input.error = "Xの動画投稿かスペースのURLを入力してください。"; return }
        AlertDialog.Builder(this).setTitle("ダウンロードしますか？")
            .setMessage(if (XLink.isSpace(url)) "録音済みスペースを音声で保存します。" else "内容を確認して、動画または音声で保存します。")
            .setPositiveButton("はい") { _, _ ->
                input.text.clear()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                val job = Intent(this, DownloadService::class.java).setAction(DownloadService.DOWNLOAD).putExtra("url", url)
                selectedAccount?.let { job.putExtra("accountId", it) }
                launchService(job)
            }.setNegativeButton("戻る", null).show()
    }
    private fun launchService(job: Intent) {
        if (StateHub.state.busy) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        }
        runCatching { startForegroundService(job) }.onFailure {
            StateHub.publish(DownloadState(message = "処理を開始できませんでした。画面を開いたまま、もう一度お試しください。"))
        }
    }
    private fun render(s: DownloadState) {
        save.isEnabled = !s.busy; update.isEnabled = !s.busy; input.isEnabled = !s.busy
        accountsButton.isEnabled = !s.busy
        appUpdate.isEnabled = !checkingUpdate
        cancel.visibility = if (s.busy) View.VISIBLE else View.GONE
        open.visibility = if (!s.busy && s.file != null) View.VISIBLE else View.GONE
        status.text = s.message
        progress.visibility = if (s.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = s.progress < 0
        if (s.progress >= 0) progress.progress = s.progress
        if (s.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun storedAccounts(): List<SavedAccount>? = runCatching { AccountStore(this).list() }
        .onFailure { Toast.makeText(this, "保存したアカウントを読み込めませんでした。", Toast.LENGTH_LONG).show() }.getOrNull()
    private fun selectAccount(id: String?) {
        selectedAccount = id
        getPreferences(MODE_PRIVATE).edit().apply {
            if (id == null) remove("accountId") else putString("accountId", id)
        }.apply()
        refreshAccount()
    }
    private fun refreshAccount() {
        val accounts = storedAccounts() ?: return
        val account = accounts.find { it.id == selectedAccount }
        if (account == null && selectedAccount != null) {
            selectedAccount = null
            getPreferences(MODE_PRIVATE).edit().remove("accountId").apply()
        }
        accountsButton.text = account?.let { "アカウント：${it.label}" } ?: "アカウント：ログインせず使う"
    }
    private fun chooseAccount() {
        val accounts = storedAccounts() ?: return
        val choices = listOf("ログインせず使う") + accounts.map { it.label } +
            listOf("＋ 新しいアカウントでログイン", "保存したアカウントを管理")
        AlertDialog.Builder(this).setTitle("使用するアカウント")
            .setItems(choices.toTypedArray()) { _, index ->
                when {
                    index == 0 -> selectAccount(null)
                    index <= accounts.size -> selectAccount(accounts[index - 1].id)
                    index == accounts.size + 1 -> login(null)
                    else -> manageAccounts(accounts)
                }
            }.setNegativeButton("戻る", null).show()
    }
    private fun login(account: SavedAccount?) {
        startActivityForResult(Intent(this, LoginActivity::class.java).apply {
            account?.let { putExtra("accountId", it.id); putExtra("label", it.label) }
        }, 31)
    }
    private fun manageAccounts(accounts: List<SavedAccount>) {
        if (accounts.isEmpty()) { Toast.makeText(this, "保存したアカウントはありません。", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("管理するアカウント")
            .setItems(accounts.map { it.label }.toTypedArray()) { _, i ->
                val account = accounts[i]
                AlertDialog.Builder(this).setTitle(account.label)
                    .setItems(arrayOf("名前を変更", "ログインし直す", "この端末から削除")) { _, action ->
                        when (action) {
                            0 -> renameAccount(account)
                            1 -> login(account)
                            else -> AlertDialog.Builder(this).setTitle("${account.label}を削除しますか？")
                                .setMessage("このアプリに保存したログイン状態を削除します。Xのアカウント自体は削除しません。")
                                .setPositiveButton("削除") { _, _ ->
                                    runCatching { AccountStore(this).remove(account.id) }
                                        .onSuccess { refreshAccount() }
                                        .onFailure { Toast.makeText(this, "削除できませんでした。", Toast.LENGTH_LONG).show() }
                                }.setNegativeButton("戻る", null).show()
                        }
                    }.setNegativeButton("戻る", null).show()
            }.setNegativeButton("戻る", null).show()
    }
    private fun renameAccount(account: SavedAccount) {
        val field = EditText(this).apply { setText(account.label); isSingleLine = true; isSaveEnabled = false }
        AlertDialog.Builder(this).setTitle("表示名を変更").setView(field)
            .setPositiveButton("保存") { _, _ ->
                if (field.text.toString().isNotBlank()) runCatching {
                    AccountStore(this).save(field.text.toString(), account.cookies, account.id)
                }.onSuccess { refreshAccount() }
                 .onFailure { Toast.makeText(this, "変更できませんでした。", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("戻る", null).show()
    }
    private fun checkAppUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true; appUpdate.isEnabled = false; appUpdate.text = "更新を確認しています…"
        Thread {
            val result = runCatching { AppUpdates.check() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                checkingUpdate = false; appUpdate.isEnabled = true; appUpdate.text = "アプリの更新を確認"
                result.onSuccess { info ->
                    val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                    val newer = AppUpdates.newer(info.version, installed)
                    val dialog = AlertDialog.Builder(this).setTitle(if (newer) "新しいバージョンがあります" else "アプリのバージョン")
                        .setMessage("使用中：v$installed\n配布版：v${info.version}" + if (newer) "" else "\n新しい更新はありません。")
                        .setNegativeButton("閉じる", null)
                    if (newer) dialog.setPositiveButton("更新ページを開く") { _, _ -> openRelease(info.url) }
                    dialog.show()
                }.onFailure { Toast.makeText(this, "更新を確認できませんでした。通信状態を確認して再度お試しください。", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
    private fun openRelease(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "ブラウザを開けませんでした。", Toast.LENGTH_LONG).show() }
    }
    @Deprecated("Platform activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 31 && resultCode == RESULT_OK) selectAccount(data?.getStringExtra("accountId"))
    }
}
