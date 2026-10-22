package jp.muya.xsaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*

class LoginActivity : Activity() {
    private var web: WebView? = null
    private lateinit var save: Button
    private lateinit var note: TextView
    private var finishing = false
    private var accountDialog: AlertDialog? = null
    private lateinit var site: MediaSite
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        site = MediaLink.sites.find { it.id == intent.getStringExtra("service") } ?: MediaLink.site("x")
        // This activity has its own process and WebView directory; no external browser data is read.
        runCatching { WebView.setDataDirectorySuffix("x-login") }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // A private login screen must never be restored past the password gate.
        if (site.id == "pornhub" && state != null) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        note = TextView(this).apply {
            text = "${site.name}の公式画面でログインし、下のボタンで保存してください。サービス側がアプリ内ログインを許可しない場合は利用できません。"
            setPadding(20, 16, 20, 16)
        }
        root.addView(note)
        val view = WebView(this)
        web = view
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.isSaveEnabled = false
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        // Do not add a JavaScript bridge, inject scripts, or inspect login form fields.
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val allowed = uri.scheme == "https" && uri.host?.lowercase()?.let { site.owns(it) } == true
                if (!allowed) note.text = "このログイン方法はアプリ内で続行できません。サービスの通常のログイン方法を使ってください。"
                return !allowed
            }
            override fun onPageFinished(web: WebView, url: String) {
                if (session() != null) note.text = "ログイン状態を確認しました。下の「このアカウントを保存」を押してください。"
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        save = Button(this).apply {
            text = "このアカウントを保存"; isAllCaps = false
            setOnClickListener { saveAccount() }
        }
        root.addView(save)
        root.addView(Button(this).apply { text = "保存せず戻る"; setOnClickListener { leave() } })
        setContentView(root)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
            removeAllCookies {
                flush()
                WebStorage.getInstance().deleteAllData()
                view.clearCache(true)
                if (!isFinishing && !isDestroyed) view.loadUrl(site.loginUrl)
            }
        }
    }
    private fun session(): String? = runCatching {
        val rows = site.domains.flatMap { domain ->
            val header = CookieManager.getInstance().getCookie("https://$domain/").orEmpty()
            if (header.isBlank()) emptyList() else SessionCookies.fromHeader(header, site.id, domain, false).lineSequence().filter { !it.startsWith("#") && it.isNotBlank() }.toList()
        }.distinct()
        val names = rows.mapNotNull { it.split('\t').getOrNull(5) }.toSet()
        require(if (site.id == "x") names.containsAll(site.authCookies) else names.any { it in site.authCookies })
        "# Netscape HTTP Cookie File\n" + rows.joinToString("\n") + "\n"
    }.getOrNull()
    private fun saveAccount() {
        if (finishing) return
        val cookies = session() ?: run { note.text = "ログインを完了してから保存してください。"; return }
        val label = EditText(this).apply {
            hint = "例：メイン、ゲーム用、@ユーザー名"
            setText(intent.getStringExtra("label").orEmpty())
            isSingleLine = true; isSaveEnabled = false
        }
        val dialog = AlertDialog.Builder(this).setTitle("アカウントの表示名")
            .setMessage("自分が見分けやすい名前で保存できます。")
            .setView(label).setPositiveButton("保存", null).setNegativeButton("戻る", null).create()
        accountDialog = dialog
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { accountDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (finishing) return@setOnClickListener
                if (label.text.toString().isBlank()) { label.error = "表示名を入力してください。"; return@setOnClickListener }
                runCatching { AccountStore(this).save(label.text.toString(), cookies, intent.getStringExtra("accountId"), site.id) }
                    .onSuccess { id ->
                        dialog.dismiss()
                        setResult(RESULT_OK, Intent().putExtra("accountId", id).putExtra("service", site.id))
                        leave()
                    }.onFailure { note.text = "ログイン状態を保存できませんでした。登録は最大20件です。"; dialog.dismiss() }
            }
        }
        dialog.show()
    }
    private fun leave() {
        if (finishing) return
        finishing = true
        web?.stopLoading()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            finish()
        }
    }
    @Deprecated("Platform back navigation")
    override fun onBackPressed() { leave() }
    override fun onPause() {
        if (::site.isInitialized && site.id == "pornhub" && !isFinishing) {
            web?.visibility = View.GONE
            accountDialog?.dismiss()
            leave()
            finish()
        }
        super.onPause()
    }
    override fun onDestroy() {
        web?.apply { stopLoading(); clearHistory(); clearCache(true); removeAllViews(); destroy() }
        web = null
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}
