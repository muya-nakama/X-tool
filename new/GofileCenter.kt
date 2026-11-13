package jp.muya.xsaver

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

data class GofileCenterMedia(val id: String, val url: String, val title: String, val duration: Double, val referer: String = GofileCenter.REFERER, val service: String = "GoFile Center", val thumbnail: String? = null) {
    fun annotate(info: JSONObject): JSONObject {
        info.put("id", id).put("extractor_key", if (service in setOf("TwFiles", "TwimgFile")) service else if (service.contains('.')) service else "GofileCenter")
            .put("extractor", if (service in setOf("TwFiles", "TwimgFile")) service.lowercase(Locale.ROOT) else if (service.contains('.')) service else "gofilecenter")
            .put("title", title).put("uploader", service)
        if (info.optDouble("duration", 0.0) <= 0 && duration > 0) info.put("duration", duration)
        thumbnail?.let { info.put("thumbnail", it) }
        val headers = info.optJSONObject("http_headers") ?: JSONObject()
        headers.put("Referer", referer)
        info.put("http_headers", headers)
        return info
    }
}

/** Public landing-page metadata only. No login, tracking requests, media bytes or cache. */
object GofileCenter {
    const val REFERER = "https://gofile.center/"
    private val domains = setOf("gofile.center", "gofile.diy", "twfiles.com", "twimgfile.com", "gofile.guru", "gofile.bid", "gofile.meme", "tweetjp.com")
    fun serviceName(url: String): String = when (URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")) {
        "twfiles.com" -> "TwFiles"
        "twimgfile.com" -> "TwimgFile"
        "gofile.guru" -> "gofile.guru"
        "gofile.bid" -> "gofile.bid"
        "gofile.meme" -> "gofile.meme"
        "tweetjp.com" -> "tweetjp.com"
        else -> "GoFile Center"
    }
    fun compatibleCandidate(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.contains(':') && !host.matches(Regex("[0-9.]+")) &&
            uri.path.orEmpty().trim('/').matches(Regex("[A-Za-z0-9_-]{1,120}"))
    }.getOrDefault(false)
    /** Only after the common engine reports an unsupported URL. No login or tracking. */
    fun resolveCompatible(url: String, checkCancel: () -> Unit = {}, read: ((String) -> String)? = null): GofileCenterMedia {
        require(compatibleCandidate(url))
        val root = "https://${URI(url).host.lowercase(Locale.ROOT).removePrefix("www.")}/"
        val id = URI(url).path.trim('/').let { if (it.length > 6 && it.startsWith("a")) it.substring(1) else it }
        return resolvePage(url, id, root, URI(root).host, checkCancel, read ?: { get(it, checkCancel, root) })
    }
    fun referer(url: String): String {
        require(supports(url))
        return "https://${URI(url).host.lowercase(Locale.ROOT).removePrefix("www.")}/"
    }
    fun supports(url: String): Boolean = runCatching { URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") in domains }.getOrDefault(false)
    fun linkId(url: String): String {
        require(supports(url))
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443))
        val tweetjp = uri.host.lowercase(Locale.ROOT).removePrefix("www.") == "tweetjp.com"
        val sharedId = if (tweetjp) {
            val params = uri.rawQuery.orEmpty().split('&').map { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            params.firstOrNull { it.first == "link" && it.second.isNotEmpty() }?.second
                ?: params.firstOrNull { it.first == "externalLinks" && it.second.isNotEmpty() }?.second
        } else null
        val pathId = if (tweetjp) uri.path.orEmpty().trim('/').substringAfterLast('/') else uri.path.orEmpty().trim('/')
        val candidate = sharedId ?: pathId
        val id = if (tweetjp) candidate.replace(Regex("(?<=.)\\.[A-Za-z0-9][A-Za-z0-9_-]{0,31}$"), "") else candidate
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "このサイトの動画ページのリンクを指定してください。" }
        return if (id.length > 6 && id.startsWith("a")) id.substring(1) else id
    }
    fun resolve(url: String, checkCancel: () -> Unit = {}, read: (String) -> String = { get(it, checkCancel, referer(url)) }): GofileCenterMedia {
        return resolvePage(url, linkId(url), referer(url), serviceName(url), checkCancel, read)
    }
    private fun resolvePage(url: String, id: String, pageReferer: String, service: String, checkCancel: () -> Unit, read: (String) -> String): GofileCenterMedia {
        checkCancel()
        val page = URI(url)
        val tweetjp = URI(pageReferer).host == "tweetjp.com"
        val pageUrl = pageReferer + page.rawPath.orEmpty().trim('/') +
            if (tweetjp && page.rawQuery != null) "?" + page.rawQuery else ""
        val html = read(pageUrl)
        val scriptPattern = if (tweetjp) Regex("/assets/app-[A-Za-z0-9_-]+\\.js")
            else Regex("/assets/index-[A-Za-z0-9_-]+\\.js")
        val script = Regex("<script[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.firstOrNull { scriptPattern.matches(it) }
            ?: throw IllegalStateException("このサイトのページ構成が変わったため読み込めません。アプリの更新を確認してください。")
        require(scriptPattern.matches(script))
        checkCancel()
        val javascript = read(URI(pageUrl).resolve(script).toASCIIString())
        val endpoint = metadataEndpoint(javascript, pageReferer, id)
        checkCancel()
        val response = JSONObject(read(endpoint))
        return parse(id, response, service).copy(referer = pageReferer)
    }
    /** Only metadata APIs observed in the public page bundle; never ad or tracking scripts. */
    fun metadataEndpoint(javascript: String, pageReferer: String, id: String): String {
        val page = URI(pageReferer)
        require(page.scheme == "https" && page.userInfo == null && page.port in setOf(-1, 443))
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}")))
        val host = page.host.lowercase(Locale.ROOT)
        if (host == "gofile.bid" && javascript.contains("/api.php") &&
            javascript.contains("endpoint") && javascript.contains("/flow/land-page/getInfo")) {
            return "https://$host/api.php?endpoint=%2Fflow%2Fland-page%2FgetInfo&externalLinks=$id&domain=$host"
        }
        val origin = Regex("https://[A-Za-z0-9.-]+\\.(?:gofile\\.mov|fun800\\.click)(?=[`\"'/])").find(javascript)?.value
            ?: throw IllegalStateException("このサイトの取得先を確認できませんでした。アプリの更新を確認してください。")
        return "$origin/app-api/flow/land-page/getInfo?externalLinks=$id&domain=$host"
    }
    private fun obj(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }
    fun parse(id: String, response: JSONObject, service: String = "GoFile Center"): GofileCenterMedia {
        val code = response.optInt("code", 0)
        check(code == 0) { "サイトから動画情報を取得できませんでした。元のページで再生できるか確認してください。" }
        val outer = obj(response.opt("data") ?: response)
        val data = obj(outer.opt("data") ?: outer)
        val info = obj(data.opt("info") ?: data)
        val rules = obj(data.opt("rules") ?: outer.opt("rules") ?: info.opt("rules"))
        when (rules.optString("status", "0")) {
            "1", "2", "3" -> throw IllegalStateException("このサイトのリンクが無効・期限切れ、または動画が公開されていません。")
            "4", "5" -> throw IllegalStateException("このサイトのリンクにはパスワードが必要です。パスワード付きリンクは現在未対応です。")
            "0", "" -> Unit
            else -> throw IllegalStateException("このサイトの公開状態を確認できませんでした。元のページを確認してください。")
        }
        val file = obj(info.opt("netDiskInfo") ?: info.opt("netdiskInfo") ?: info.opt("fileInfo"))
        require(file.optString("isFolder", "false").lowercase(Locale.ROOT) !in setOf("true", "1")) { "このサイトのフォルダーではなく、個別の動画リンクを指定してください。" }
        val target = listOf("mp4Url", "appMp4Url", "videoUrl", "playUrl", "m3u8Url", "fileUrl", "source", "src", "url", "downloadUrl")
            .map { file.optString(it, "").trim() }.firstOrNull { it.isNotEmpty() }
            ?: throw IllegalStateException("このサイトに再生可能な動画が見つかりませんでした。")
        val media = URI(target)
        val host = media.host?.lowercase(Locale.ROOT).orEmpty()
        require(media.scheme == "https" && media.userInfo == null && media.port in setOf(-1, 443) && host.contains('.') &&
            !host.endsWith(".local") && !host.endsWith(".localhost") && host != "localhost" && !host.contains(':') && !host.matches(Regex("[0-9.]+"))) {
            "このサイトの配信先を安全に確認できませんでした。"
        }
        val duration = durationSeconds(file.optString("length", file.optString("duration", "")))
        val title = file.optString("name").ifBlank { file.optString("title") }.ifBlank { "$service $id" }.take(300)
        val cover = listOf("coverImage", "thumbnail", "cover", "previewUrl").mapNotNull { ThumbnailPolicy.imageUrl(file.optString(it)) }.firstOrNull()
        return GofileCenterMedia(id, target, title, duration, service = service, thumbnail = cover)
    }
    fun durationSeconds(raw: String): Double {
        val text = raw.trim()
        val parts = text.split(':')
        if (parts.size == 1) return text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        if (parts.size !in 2..3) return 0.0
        val values = parts.map { it.toDoubleOrNull() ?: return 0.0 }
        if (values.any { !it.isFinite() || it < 0 } || values.drop(1).any { it >= 60 } ||
            values.dropLast(1).any { it % 1 != 0.0 }) return 0.0
        return values.fold(0.0) { total, value -> total * 60 + value }.takeIf { it.isFinite() } ?: 0.0
    }
    private fun get(url: String, checkCancel: () -> Unit, referer: String): String {
        var last: IOException? = null
        repeat(3) { attempt ->
            checkCancel()
            try { return readOnce(url, checkCancel, referer) }
            catch (e: IOException) {
                last = e
                checkCancel()
                if (attempt < 2) Thread.sleep((attempt + 1) * 1000L)
            }
        }
        throw last ?: IOException("GoFile Center connection failed")
    }
    private fun readOnce(url: String, checkCancel: () -> Unit, referer: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443))
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(host == URI(referer).host || host.removePrefix("www.") in domains || host.endsWith(".gofile.mov") || host.endsWith(".fun800.click"))
        require(InetAddress.getAllByName(host).all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress && !address.isMulticastAddress &&
                !(address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
        }) { "公開サイトのアドレスを指定してください。" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 30000; c.readTimeout = 60000; c.instanceFollowRedirects = false; c.useCaches = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.setRequestProperty("Referer", referer)
            if (c.responseCode in setOf(401, 403)) throw IllegalStateException("このサイトに取得を拒否されました。元のページの公開範囲や制限を確認してください。")
            if (c.responseCode != 200) throw IOException("GoFile Center HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val buffer = ByteArrayOutputStream(); val chunk = ByteArray(16384)
                while (true) {
                    checkCancel(); val n = input.read(chunk); if (n < 0) break
                    require(buffer.size() + n <= 4 * 1024 * 1024) { "このサイトの応答が大きすぎます。" }
                    buffer.write(chunk, 0, n)
                }
                buffer.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
}
