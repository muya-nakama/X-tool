package jp.muya.xsaver

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

data class GofileCenterMedia(val id: String, val url: String, val title: String, val duration: Double, val referer: String = GofileCenter.REFERER, val service: String = "GoFile Center") {
    fun annotate(info: JSONObject): JSONObject {
        info.put("id", id).put("extractor_key", if (service in setOf("TwFiles", "TwimgFile")) service else if (service.contains('.')) service else "GofileCenter")
            .put("extractor", if (service in setOf("TwFiles", "TwimgFile")) service.lowercase(Locale.ROOT) else if (service.contains('.')) service else "gofilecenter")
            .put("title", title).put("uploader", service)
        if (info.optDouble("duration", 0.0) <= 0 && duration > 0) info.put("duration", duration)
        return info
    }
}

/** Public landing-page metadata only. No login, tracking requests, media bytes or cache. */
object GofileCenter {
    const val REFERER = "https://gofile.center/"
    private val domains = setOf("gofile.center", "gofile.diy", "twfiles.com", "twimgfile.com")
    fun serviceName(url: String): String = when (URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")) {
        "twfiles.com" -> "TwFiles"
        "twimgfile.com" -> "TwimgFile"
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
        val id = uri.path.orEmpty().trim('/')
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "このサイトの動画ページのリンクを指定してください。" }
        return if (id.length > 6 && id.startsWith("a")) id.substring(1) else id
    }
    fun resolve(url: String, checkCancel: () -> Unit = {}, read: (String) -> String = { get(it, checkCancel, referer(url)) }): GofileCenterMedia {
        return resolvePage(url, linkId(url), referer(url), serviceName(url), checkCancel, read)
    }
    private fun resolvePage(url: String, id: String, pageReferer: String, service: String, checkCancel: () -> Unit, read: (String) -> String): GofileCenterMedia {
        checkCancel()
        val pageUrl = pageReferer + URI(url).path.orEmpty().trim('/')
        val html = read(pageUrl)
        val script = Regex("<script[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.firstOrNull { it.startsWith("/assets/index-") && it.endsWith(".js") }
            ?: throw IllegalStateException("このサイトのページ構成が変わったため読み込めません。アプリの更新を確認してください。")
        require(script.matches(Regex("/assets/index-[A-Za-z0-9_-]+\\.js")))
        checkCancel()
        val javascript = read(URI(pageUrl).resolve(script).toASCIIString())
        val origin = Regex("https://[A-Za-z0-9.-]+\\.(?:gofile\\.mov|fun800\\.click)(?=[`\"'/])").find(javascript)?.value
            ?: throw IllegalStateException("このサイトの取得先を確認できませんでした。アプリの更新を確認してください。")
        checkCancel()
        val response = JSONObject(read("$origin/app-api/flow/land-page/getInfo?externalLinks=$id&domain=${URI(pageReferer).host}"))
        return parse(id, response, service).copy(referer = pageReferer)
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
        val duration = file.optString("length", "").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val title = file.optString("name").ifBlank { file.optString("title") }.ifBlank { "$service $id" }.take(300)
        return GofileCenterMedia(id, target, title, duration, service = service)
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
