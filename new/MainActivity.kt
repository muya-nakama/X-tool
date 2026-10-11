package jp.muya.xsaver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var read: Button
    private lateinit var save: Button
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var progress: ProgressBar
    private lateinit var thumbnail: ImageView
    private lateinit var formats: Spinner
    private lateinit var modes: RadioGroup
    private lateinit var videoMode: RadioButton
    private lateinit var audioMode: RadioButton
    private lateinit var cancel: Button
    private lateinit var open: Button
    private var media: MediaInfo? = null
    private var visibleChoices = emptyList<MediaChoice>()
    private var appRemote: AppVersions? = null
    private var engineRemote: String? = null
    private var engineLocal: String? = null
    private var appStatus = "確認中…"
    private var engineStatus = "確認中…"
    private var checking = false
    private var settingsBody: LinearLayout? = null
    private var settingsDialog: AlertDialog? = null
    private val listener: (DownloadState) -> Unit = { render(it) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(label: String, size: Float = 15f) = TextView(this).apply {
        text = label; textSize = size; setTextColor(Color.rgb(231, 239, 249)); setPadding(0, dp(9), 0, dp(9))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(56); setOnClickListener { action() }
    }
    private fun root() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)); setBackgroundColor(Color.rgb(16, 24, 39))
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val body = root()
        body.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(24) + bars.bottom)
            }
            insets
        }
        body.addView(text("MOVIE&AUDIO-TOOL", 25f))
        body.addView(text("リンクから動画・音声を保存", 16f))
        body.addView(button("設定", ::showSettings))
        input = EditText(this).apply {
            hint = "動画・音声のURL"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (MediaLink.parse(s.toString()) != media?.url) clearPreview()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        body.addView(input)
        body.addView(button("リンクを貼り付け") {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) input.setText(clip.getItemAt(0).coerceToText(this))
        })
        read = button("動画・音声を読み込む", ::analyze); body.addView(read)
        thumbnail = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        body.addView(thumbnail, LinearLayout.LayoutParams(-1, dp(180)))
        details = text(""); body.addView(details)
        modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        videoMode = RadioButton(this).apply { id = View.generateViewId(); text = "動画"; setTextColor(Color.WHITE) }
        audioMode = RadioButton(this).apply { id = View.generateViewId(); text = "音声のみ"; setTextColor(Color.WHITE) }
        modes.addView(videoMode); modes.addView(audioMode); body.addView(modes)
        modes.setOnCheckedChangeListener { _, _ -> populateFormats() }
        formats = Spinner(this); body.addView(formats)
        save = button("ダウンロード", ::confirmDownload); body.addView(save)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }; body.addView(progress)
        status = text(""); body.addView(status)
        cancel = button("キャンセル") { startService(Intent(this, DownloadService::class.java).setAction(DownloadService.CANCEL)) }; body.addView(cancel)
        open = button("保存したファイルを開く") {
            StateHub.state.file?.let { uri -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, StateHub.state.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { toast("対応する再生アプリがありません。マイファイルから開いてください。") } }
        }; body.addView(open)
        body.addView(text("保存先：Download / MOVIE&AUDIO-TOOL / サービス / 動画・音声 / 投稿者\n音声は配信元の品質を保って保存します。", 13f))
        body.addView(text("v${BuildConfig.VERSION_NAME}", 12f))
        populateFormats()
        setContentView(ScrollView(this).apply { addView(body) })
        StateHub.state.preview?.let { input.setText(it.url) }
        receiveShare(intent)
        checkUpdates()
    }
    override fun onStart() { super.onStart(); StateHub.listen(listener) }
    override fun onStop() { StateHub.remove(listener); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    private fun receiveShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) { input.setText(MediaLink.parse(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()).orEmpty()); intent.removeExtra(Intent.EXTRA_TEXT) }
    }
    private fun clearPreview() {
        media = null
        if (::details.isInitialized) { details.text = ""; thumbnail.setImageDrawable(null); thumbnail.visibility = View.GONE; populateFormats() }
    }
    private fun selectedAccount(url: String): String? {
        val service = MediaLink.detect(url)?.id ?: return null
        return (getSharedPreferences("service-accounts", MODE_PRIVATE).getString(service, null)
            ?: if (service == "x") getPreferences(MODE_PRIVATE).getString("accountId", null) else null)?.takeIf { it.isNotBlank() }
    }
    private fun analyze() {
        val url = MediaLink.parse(input.text.toString()) ?: run { input.error = "動画・音声のURLを入力してください。"; return }
        clearPreview()
        launch(Intent(this, DownloadService::class.java).setAction(DownloadService.ANALYZE).putExtra("url", url).putExtra("accountId", selectedAccount(url)))
    }
    private fun populateFormats() {
        if (!::formats.isInitialized) return
        visibleChoices = media?.choices?.filter { it.audio == (modes.checkedRadioButtonId == audioMode.id) }.orEmpty()
        formats.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, visibleChoices.map { it.label })
        save.isEnabled = visibleChoices.isNotEmpty() && !StateHub.state.busy
        modes.visibility = if (media == null) View.GONE else View.VISIBLE
        formats.visibility = modes.visibility
    }
    private fun confirmDownload() {
        val info = media ?: return
        val choice = visibleChoices.getOrNull(formats.selectedItemPosition) ?: return
        val warning = NetworkPolicy.needsWarning(this, choice.bytes)
        AlertDialog.Builder(this).setTitle(if (warning) "通信量にご注意ください" else "ダウンロードしますか？")
            .setMessage("${info.title}\n${choice.label}" + if (warning) "\n\nWi-Fi・有線接続ではありません。大きな通信量になる可能性があります。続けますか？" else "")
            .setPositiveButton("はい") { _, _ ->
                launch(Intent(this, DownloadService::class.java).setAction(DownloadService.DOWNLOAD).putExtra("url", info.url)
                    .putExtra("accountId", selectedAccount(info.url)).putExtra("format", choice.selector).putExtra("audio", choice.audio).putExtra("mobileApproved", warning))
            }.setNegativeButton("戻る", null).show()
    }
    private fun launch(job: Intent) {
        if (StateHub.state.busy || jp.muya.xsaver.legacy.StateHub.state.busy || jp.muya.xsaver.UpdateActivity.running) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        runCatching { startForegroundService(job) }.onFailure { toast("開始できませんでした。画面を開いたまま再度お試しください。") }
    }
    private fun render(s: DownloadState) {
        if (s.preview != null && s.preview !== media && MediaLink.parse(input.text.toString()) == s.preview.url) {
            media = s.preview
            val m = s.preview
            details.text = "${m.title}\n${MediaLink.detect(m.url)?.name ?: MediaLink.folder(m.url)} · ${m.duration.toInt() / 60}分${m.duration.toInt() % 60}秒"
            videoMode.isEnabled = m.choices.any { !it.audio }; audioMode.isEnabled = m.choices.any { it.audio }
            modes.check(if (videoMode.isEnabled) videoMode.id else audioMode.id)
            populateFormats(); loadThumbnail(m)
        }
        read.isEnabled = !s.busy; input.isEnabled = !s.busy
        save.isEnabled = !s.busy && visibleChoices.isNotEmpty(); formats.isEnabled = !s.busy
        videoMode.isEnabled = !s.busy && media?.choices?.any { !it.audio } == true
        audioMode.isEnabled = !s.busy && media?.choices?.any { it.audio } == true
        cancel.visibility = if (s.busy && engineStatus != "更新中…") View.VISIBLE else View.GONE
        open.visibility = if (!s.busy && s.file != null) View.VISIBLE else View.GONE
        status.text = s.message
        progress.visibility = if (s.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = s.progress < 0; if (s.progress >= 0) progress.progress = s.progress
        refreshSettings()
    }
    private fun loadThumbnail(info: MediaInfo) {
        thumbnail.setImageDrawable(null); thumbnail.visibility = View.GONE
        val url = info.thumbnail ?: return
        Thread {
            val bitmap = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    c.inputStream.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                        while (true) { val n = stream.read(chunk); if (n < 0) break; require(buffer.size() + n <= 5 * 1024 * 1024); buffer.write(chunk, 0, n) }
                        val bytes = buffer.toByteArray()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000)
                        var sample = 1
                        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                } finally { c.disconnect() }
            }.getOrNull()
            runOnUiThread { if (!isDestroyed && media === info && bitmap != null) { thumbnail.setImageBitmap(bitmap); thumbnail.visibility = View.VISIBLE } }
        }.start()
    }
    private fun showSettings() {
        val body = root(); settingsBody = body
        settingsDialog = AlertDialog.Builder(this).setTitle("設定").setView(ScrollView(this).apply { addView(body) }).setNegativeButton("閉じる", null).create()
        settingsDialog?.setOnDismissListener { settingsBody = null; settingsDialog = null }
        refreshSettings(); settingsDialog?.show()
    }
    private fun refreshSettings() {
        val body = settingsBody ?: return
        body.removeAllViews()
        body.addView(text("ログイン・アカウント切替", 19f))
        val accounts = runCatching { AccountStore(this).list() }.getOrDefault(emptyList())
        MediaLink.sites.forEach { site ->
            val active = getSharedPreferences("service-accounts", MODE_PRIVATE).getString(site.id, null)
                ?: if (site.id == "x") getPreferences(MODE_PRIVATE).getString("accountId", null) else null
            val label = accounts.find { it.id == active && it.service == site.id }?.label ?: "ログインせず使う"
            body.addView(button("${site.name}：$label") { chooseAccount(site) })
        }
        body.addView(text("アプリ本体：$appStatus"))
        appRemote?.takeIf { AppUpdates.newer(it.version, BuildConfig.VERSION_NAME) }?.let { remote ->
            body.addView(button("アプリを更新") {
                AlertDialog.Builder(this).setTitle("v${remote.version}の更新内容").setMessage(remote.notes.ifBlank { "配布ページで更新内容を確認できます。" })
                    .setPositiveButton("更新する") { _, _ -> jp.muya.xsaver.UpdateActivity.open(this) }.setNegativeButton("戻る", null).show()
            })
        }
        body.addView(text("共通エンジン：$engineStatus"))
        if (engineRemote != null && engineLocal != null && engineRemote!! > engineLocal!!) {
            body.addView(button("エンジンを更新") {
                if (StateHub.state.busy) { toast("保存が終わってから更新してください。"); return@button }
                engineStatus = "更新中…"; StateHub.publish(DownloadState(true, "取得エンジンを更新しています…")); refreshSettings()
                Thread {
                    val result = runCatching { synchronized(EngineGate.lock) {
                        YoutubeDL.init(applicationContext)
                        YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                    } }
                    StateHub.publish(DownloadState(message = if (result.isSuccess) "取得エンジンを更新しました。" else "更新できませんでした。今のエンジンで保存できます。"))
                    runOnUiThread { if (!isDestroyed) { checkUpdates() } }
                }.start()
            }.apply { isEnabled = !StateHub.state.busy && engineStatus != "更新中…" })
        }
        body.addView(text("特殊処理：アプリ本体と一緒に更新（X Spaceなど）"))
        body.addView(button(if (checking) "更新を確認中…" else "更新を再確認") { checkUpdates() }.apply { isEnabled = !checking })
        body.addView(button("更新履歴") { AlertDialog.Builder(this).setTitle("更新履歴").setMessage(CHANGELOG).setPositiveButton("閉じる", null).show() })
        body.addView(text("使用する版")); VersionSwitcher.addTo(body, this, VersionSwitcher.CURRENT)
        body.addView(button("大容量警告：${NetworkPolicy.limit(this) / (1024 * 1024)} MB以上") {
            val field = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText((NetworkPolicy.limit(this@MainActivity) / (1024 * 1024)).toString()) }
            AlertDialog.Builder(this).setTitle("警告する容量（MB）").setView(field).setPositiveButton("保存") { _, _ ->
                val n = field.text.toString().toLongOrNull()
                if (n != null && n in 1..100000) { getSharedPreferences("settings", MODE_PRIVATE).edit().putLong("warningMB", n).apply(); refreshSettings() }
                else toast("1〜100000の数値を入力してください。")
            }.setNegativeButton("戻る", null).show()
        })
    }
    private fun chooseAccount(site: MediaSite) {
        if (StateHub.state.busy) { toast("保存が終わってから変更してください。"); return }
        val accounts = runCatching { AccountStore(this).list().filter { it.service == site.id } }.getOrElse { toast("アカウントを読み込めませんでした。"); return }
        val prefs = getSharedPreferences("service-accounts", MODE_PRIVATE)
        val choices = listOf("ログインせず使う") + accounts.map { it.label } + listOf("＋ ログイン", "保存したアカウントを管理")
        AlertDialog.Builder(this).setTitle(site.name).setItems(choices.toTypedArray()) { _, i ->
            when {
                i == 0 -> { prefs.edit().putString(site.id, "").apply(); clearPreview(); refreshSettings() }
                i <= accounts.size -> { prefs.edit().putString(site.id, accounts[i-1].id).apply(); clearPreview(); refreshSettings(); toast("${accounts[i-1].label}に切り替えました。") }
                i == accounts.size + 1 -> login(site, null)
                else -> AlertDialog.Builder(this).setTitle("管理するアカウント").setItems(accounts.map { it.label }.toTypedArray()) { _, index ->
                    val a = accounts[index]
                    AlertDialog.Builder(this).setTitle(a.label).setItems(arrayOf("名前変更", "ログインし直す", "端末から削除")) { _, action ->
                        when (action) {
                            0 -> {
                                val field = EditText(this).apply { setText(a.label); isSaveEnabled = false }
                                AlertDialog.Builder(this).setTitle("表示名").setView(field).setPositiveButton("保存") { _, _ ->
                                    runCatching { AccountStore(this).save(field.text.toString().ifBlank { a.label }, a.cookies, a.id, a.service) }.onSuccess { refreshSettings() }.onFailure { toast("変更できませんでした。") }
                                }.setNegativeButton("戻る", null).show()
                            }
                            1 -> login(site, a)
                            else -> AlertDialog.Builder(this).setTitle("端末からログイン状態を削除しますか？").setPositiveButton("削除") { _, _ ->
                                runCatching { AccountStore(this).remove(a.id) }.onSuccess {
                                    if (prefs.getString(site.id, null) == a.id) prefs.edit().putString(site.id, "").apply()
                                    if (site.id == "x") getPreferences(MODE_PRIVATE).edit().remove("accountId").apply()
                                    clearPreview(); refreshSettings()
                                }.onFailure { toast("削除できませんでした。") }
                            }.setNegativeButton("戻る", null).show()
                        }
                    }.show()
                }.setNegativeButton("戻る", null).show()
            }
        }.setNegativeButton("戻る", null).show()
    }
    private fun login(site: MediaSite, account: SavedAccount?) {
        startActivityForResult(Intent(this, LoginActivity::class.java).putExtra("service", site.id).putExtra("accountId", account?.id).putExtra("label", account?.label), 31)
    }
    @Deprecated("Platform activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 31 && resultCode == RESULT_OK && data != null) {
            val service = data.getStringExtra("service") ?: return
            getSharedPreferences("service-accounts", MODE_PRIVATE).edit().putString(service, data.getStringExtra("accountId")).apply()
            clearPreview(); refreshSettings(); toast("アカウントを保存しました。")
        }
    }
    private fun checkUpdates() {
        if (checking) return
        checking = true; appStatus = "確認中…"; engineStatus = "確認中…"; refreshSettings()
        Thread {
            val app = runCatching { AppUpdates.check() }
            val engine = runCatching {
                val remote = UpdateHttp.get("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest").let { JSONObject(it).getString("tag_name") }
                val local = synchronized(EngineGate.lock) {
                    YoutubeDL.init(applicationContext)
                    YoutubeDL.execute(YoutubeDLRequest(emptyList()).addOption("--version")).out.trim()
                }
                remote to local
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                checking = false
                app.onSuccess { appRemote = it; appStatus = if (AppUpdates.newer(it.version, BuildConfig.VERSION_NAME)) "v${it.version} 更新あり" else "最新版" }
                    .onFailure { appRemote = null; appStatus = "確認できませんでした（保存は利用できます）" }
                engine.onSuccess { (remote, local) -> engineRemote = remote; engineLocal = local; engineStatus = if (remote <= local) "最新版（$local）" else "$local → $remote 更新あり" }
                    .onFailure { engineRemote = null; engineStatus = "確認できませんでした（保存は利用できます）" }
                refreshSettings()
            }
        }.start()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    companion object {
        const val CHANGELOG = "v1.05 · 2026/10/12\n・更新用の署名を固定する仕組み\n・アプリ内でAPKを取得して上書き更新\n・更新前に同じアプリ・署名か確認\n・v1.04以前は署名が異なるため、そのまま上書き不可\n\nv1.04 · 2026/10/12\n・通信の応答待ちを20秒から60秒へ\n・一時的な通信エラーの再試行を改善\n・取得に失敗した理由をわかりやすく表示\n\nv1.03 · 2026/10/11\n・MOVIE&AUDIO-TOOLへ名称変更\n・URLでサービスを自動判別\n・サムネイル、タイトル、長さ、画質と推定容量\n・動画／音声の形式選択、音声品質を保って保存\n・サービス別ログインとアカウント管理\n・起動時に本体・エンジンの更新を確認\n・最新版の項目は更新ボタン非表示\n・大容量保存時の通信警告\n・設定から旧版へ切替\n\nv1.02\n・Xの画面入力ログイン、複数アカウント管理\n・新旧版切替とアプリ更新確認\n\nv1.00\n・X動画、録音済みSpaceの保存"
    }
}
