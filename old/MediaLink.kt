package jp.muya.xsaver

import java.net.URI

data class MediaSite(val id: String, val name: String, val domains: Set<String>, val loginUrl: String, val authCookies: Set<String>) {
    fun owns(host: String) = domains.any { host == it || host.endsWith(".$it") }
}
object MediaLink {
    val sites = listOf(
        MediaSite("x", "X", setOf("x.com", "twitter.com", "t.co"), "https://x.com/i/flow/login", setOf("auth_token", "ct0")),
        MediaSite("youtube", "YouTube", setOf("youtube.com", "youtu.be", "google.com"), "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/", setOf("SID", "__Secure-1PSID", "__Secure-3PSID")),
        MediaSite("instagram", "Instagram", setOf("instagram.com"), "https://www.instagram.com/accounts/login/", setOf("sessionid")),
        MediaSite("tiktok", "TikTok", setOf("tiktok.com"), "https://www.tiktok.com/login", setOf("sessionid", "sessionid_ss", "sid_tt")),
        MediaSite("facebook", "Facebook", setOf("facebook.com", "fb.watch"), "https://www.facebook.com/login/", setOf("c_user", "xs")),
        MediaSite("twitch", "Twitch", setOf("twitch.tv"), "https://www.twitch.tv/login", setOf("auth-token")),
        MediaSite("niconico", "ニコニコ動画", setOf("nicovideo.jp", "niconico.jp"), "https://account.nicovideo.jp/login", setOf("user_session")),
        MediaSite("pornhub", "Pornhub", setOf("pornhub.com", "pornhub.org"), "https://www.pornhub.com/login", setOf("auth", "auth_token", "username", "access_token"))
    )
    fun site(id: String) = sites.first { it.id == id }
    fun detect(url: String): MediaSite? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        return sites.firstOrNull { it.owns(host) && !(it.id == "youtube" && host.endsWith("google.com")) }
    }
    fun parse(text: String): String? {
        for (match in Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text.trim())) {
            val raw = match.value.trimEnd('.', ',', ')', ']', '。', '）', '」', '、')
            val uri = runCatching { URI(raw) }.getOrNull() ?: continue
            val host = uri.host?.lowercase() ?: continue
            if (uri.userInfo != null || uri.port !in setOf(-1, 80, 443) || !host.contains('.') ||
                host == "localhost" || host.endsWith(".local") || host.contains(':') ||
                host.matches(Regex("[0-9.]+"))) continue
            return raw
        }
        return null
    }
    fun folder(url: String): String = detect(url)?.name ?: URI(url).host.orEmpty().replace(Regex("[^A-Za-z0-9.-]"), "_")
}
