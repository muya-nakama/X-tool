package jp.muya.xsaver

/** Classify in memory; never expose or persist raw output containing URLs or cookies. */
object DownloadFailure {
    fun unsupported(error: Exception): Boolean = generateSequence<Throwable>(error) { it.cause }.take(8)
        .any { it.message.orEmpty().let { text -> text.contains("unsupported url", true) || text.contains("no suitable extractor", true) } }
    private fun details(error: Exception): String = generateSequence<Throwable>(error) { it.cause }.take(8)
            .joinToString("\n") { it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
    fun retrySerialHls(info: org.json.JSONObject, selector: String, error: Exception): Boolean {
        val detail = details(error)
        if ("http error 404" !in detail && "fragment not found" !in detail &&
            "unable to download fragment" !in detail) return false
        val selected = selector.split('+').toSet()
        val formats = info.optJSONArray("formats")
        val rows = if (formats == null) listOf(info) else (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
        return rows.any { it.optString("format_id") in selected && it.optString("protocol") == "m3u8_native" }
    }
    fun retryAlternateHls(info: org.json.JSONObject, selector: String, duration: Double, error: Exception): Boolean =
        '+' !in selector && duration.isFinite() && duration > 0 && retrySerialHls(info, selector, error)

    // Allow small playlist/container rounding differences, but reject clearly short outputs.
    fun completeDuration(expectedSeconds: Double, actualMs: Long?): Boolean {
        if (!expectedSeconds.isFinite() || expectedSeconds <= 0 || actualMs == null || actualMs <= 0) return false
        val tolerance = (expectedSeconds * 50).coerceIn(150.0, 2_000.0)
        return actualMs >= expectedSeconds * 1_000 - tolerance
    }
    fun message(error: Exception): String {
        val detail = details(error)
        fun has(vararg words: String) = words.any { detail.contains(it) }
        return when {
            has("timed out", "timeout", "time out") ->
                "通信の応答がなく、待ち時間を超えました。通信状態を確認して、もう一度お試しください。"
            has("http error 429", "too many requests", "rate limit") ->
                "サイト側で取得回数が制限されています。しばらく時間を空けてお試しください。"
            has("sign in", "log in", "login required", "login to", "authentication required", "use --cookies", "cookies-from-browser", "private video") ->
                "ログインが必要な可能性があります。「設定」でログインするか、ログインし直してお試しください。"
            has("http error 403", "http error 401", "forbidden", "unauthorized") ->
                "サイト側に取得を拒否されました。ログイン状態や公開範囲を確認してください。サイト側の制限でも発生します。"
            has("unsupported url", "no suitable extractor") ->
                "このリンクには対応していません。動画のページのURLを確認してください。"
            has("requested format is not available") ->
                "選んだ形式を取得できませんでした。読み込み直して、別の形式を選んでください。"
            has("name or service not known", "temporary failure in name resolution", "network is unreachable", "connection reset", "connection refused", "unable to resolve host") ->
                "サイトに接続できませんでした。Wi-Fiやモバイル通信の状態を確認してください。"
            has("http error 404", "server returned 404", "fragment not found", "unable to download fragment") ->
                "配信元から動画データを取得できませんでした。別の画質があれば選び直してください。"
            has("video unavailable", "has been removed", "not available in your country") ->
                "動画が削除されたか、この地域・アカウントでは取得できません。元のページを確認してください。"
            error is IllegalStateException || error is IllegalArgumentException -> error.message ?: "保存できませんでした。"
            else -> "取得できませんでした。「設定」で取得エンジンを更新して再度お試しください。サイト側の仕様や制限が原因の場合もあります。"
        }
    }
}
