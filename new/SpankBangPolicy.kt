package jp.muya.xsaver

import java.net.URI
import java.util.Locale

object SpankBangPolicy {
    fun supports(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            (host == "spankbang.com" || host.endsWith(".spankbang.com"))
    }.getOrDefault(false)
    fun percent(line: String): Float? = Regex("^\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%")
        .find(line)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 100f)
}
