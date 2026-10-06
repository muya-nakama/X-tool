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
    private lateinit var loginFile: Button
    private var sessionCookies: String? = null
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
        root.addView(text("保存先\nDownload / X保存 / 動画 または Space / ホストID", 14f, true))
        root.addView(text("保存履歴は残しません。一時ファイルは処理後に削除します。", 13f, true))
        loginFile = button("ログインが必要な場合").apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("今回だけ使うログイン情報")
                    .setMessage("XのCookieファイル（Netscape形式）を選べます。端末内の今回の取得にだけ使用し、処理後に削除します。ファイルの内容を人に送らないでください。")
                    .setPositiveButton("ファイルを選ぶ") { _, _ ->
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                        }, 21)
                    }.setNegativeButton("戻る", null).show()
            }
        }
        root.addView(loginFile)
        update = button("取得エンジンを更新").apply {
            setOnClickListener { launchService(Intent(this@MainActivity, DownloadService::class.java).setAction(DownloadService.UPDATE)) }
        }
        root.addView(update)
        root.addView(text("v1.01 · 仲間六夜 feat. ChatGPT\n公開動画・録音済みスペース用。Xがログインを要求した場合は、今回だけ使うログイン情報を読み込めます。", 12f, true))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        receiveShare(intent)
    }

    override fun onStart() { super.onStart(); StateHub.listen(listener) }
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
                sessionCookies?.let { job.putExtra("session", it) }
                sessionCookies = null
                loginFile.text = "ログインが必要な場合"
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
        loginFile.isEnabled = !s.busy
        cancel.visibility = if (s.busy) View.VISIBLE else View.GONE
        open.visibility = if (!s.busy && s.file != null) View.VISIBLE else View.GONE
        status.text = s.message
        progress.visibility = if (s.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = s.progress < 0
        if (s.progress >= 0) progress.progress = s.progress
        if (s.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    @Deprecated("Uses platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        Thread {
            val cookies = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        require(buffer.size() + n <= 256 * 1024)
                        buffer.write(chunk, 0, n)
                    }
                    val bytes = buffer.toByteArray()
                    require(bytes.size <= 256 * 1024)
                    SessionCookies.filter(bytes.toString(Charsets.UTF_8))
                } ?: throw IllegalArgumentException()
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sessionCookies = cookies
                loginFile.text = if (cookies == null) "ログインが必要な場合" else "ログイン情報を読み込みました（今回だけ）"
                if (cookies == null) Toast.makeText(this, "XのCookieファイル（Netscape形式）を選んでください。", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    override fun onDestroy() { sessionCookies = null; super.onDestroy() }
}
