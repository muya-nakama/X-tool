package jp.muya.xsaver.legacy

import java.net.URI

object XLink {
    private val candidate = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val hosts = setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")
    fun parse(text: String): String? {
        for (match in candidate.findAll(text.trim())) {
            val raw = match.value.trimEnd('.', ',', ')', ']', '。', '）', '」', '、')
            val u = runCatching { URI(raw) }.getOrNull() ?: continue
            if (u.host?.lowercase() !in hosts || u.userInfo != null || u.port != -1) continue
            val path = u.path ?: continue
            if (Regex("/i/spaces/[A-Za-z0-9]+/?").matches(path) ||
                Regex("/(?:[A-Za-z0-9_]+|i/web)/status/[0-9]+(?:/(?:video|photo)/[0-9]+)?/?").matches(path)) {
                return "https://x.com$path"
            }
        }
        return null
    }
    fun isSpace(url: String) = URI(url).path.startsWith("/i/spaces/")
}
