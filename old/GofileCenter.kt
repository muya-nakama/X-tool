package jp.muya.xsaver

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

data class GofileCenterMedia(val id: String, val url: String, val title: String, val duration: Double) {
    fun annotate(info: JSONObject): JSONObject {
        info.put("id", id).put("extractor_key", "GofileCenter").put("extractor", "gofilecenter")
            .put("title", title).put("uploader", "GoFile Center")
        if (info.optDouble("duration", 0.0) <= 0 && duration > 0) info.put("duration", duration)
        return info
    }
}

/** Public landing-page metadata only. No login, tracking requests, media bytes or cache. */
object GofileCenter {
    const val REFERER = "https://gofile.center/"
    fun supports(url: String): Boolean = runCatching { URI(url).host?.lowercase(Locale.ROOT) in setOf("gofile.center", "www.gofile.center") }.getOrDefault(false)
    fun linkId(url: String): String {
        require(supports(url))
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443))
        val id = uri.path.orEmpty().trim('/')
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "GoFile Centerの動画ページのリンクを指定してください。" }
        return if (id.length > 6 && id.startsWith("a")) id.substring(1) else id
    }
    fun resolve(url: String, checkCancel: () -> Unit = {}, read: (String) -> String = { get(it, checkCancel) }): GofileCenterMedia {
        val id = linkId(url)
        checkCancel()
        val pageUrl = REFERER + URI(url).path.orEmpty().trim('/')
        val html = read(pageUrl)
        val script = Regex("<script[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.firstOrNull { it.startsWith("/assets/index-") && it.endsWith(".js") }
            ?: throw IllegalStateException("GoFile Centerのページ構成が変わったため読み込めません。アプリの更新を確認してください。")
        require(script.matches(Regex("/assets/index-[A-Za-z0-9_-]+\\.js")))
        checkCancel()
        val javascript = read(URI(pageUrl).resolve(script).toASCIIString())
        val origin = Regex("https://[A-Za-z0-9.-]+\\.gofile\\.mov(?=[`\"'/])").find(javascript)?.value
            ?: throw IllegalStateException("GoFile Centerの取得先を確認できませんでした。アプリの更新を確認してください。")
        checkCancel()
        val response = JSONObject(read("$origin/app-api/flow/land-page/getInfo?externalLinks=$id&domain=gofile.center"))
        return parse(id, response)
    }
    private fun obj(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }
    fun parse(id: String, response: JSONObject): GofileCenterMedia {
        val code = response.optInt("code", 0)
        check(code == 0) { "GoFile Centerから動画情報を取得できませんでした。元のページで再生できるか確認してください。" }
        val outer = obj(response.opt("data") ?: response)
        val data = obj(outer.opt("data") ?: outer)
        val info = obj(data.opt("info") ?: data)
        val rules = obj(data.opt("rules") ?: outer.opt("rules") ?: info.opt("rules"))
        when (rules.optString("status", "0")) {
            "1", "2", "3" -> throw IllegalStateException("GoFile Centerのリンクが無効・期限切れ、または動画が公開されていません。")
            "4", "5" -> throw IllegalStateException("このGoFile Centerのリンクにはパスワードが必要です。パスワード付きリンクは現在未対応です。")
            "0", "" -> Unit
            else -> throw IllegalStateException("GoFile Centerの公開状態を確認できませんでした。元のページを確認してください。")
        }
        val file = obj(info.opt("netDiskInfo") ?: info.opt("netdiskInfo") ?: info.opt("fileInfo"))
        require(file.optString("isFolder", "false").lowercase(Locale.ROOT) !in setOf("true", "1")) { "GoFile Centerのフォルダーではなく、個別の動画リンクを指定してください。" }
        val target = listOf("mp4Url", "appMp4Url", "videoUrl", "playUrl", "m3u8Url", "fileUrl", "source", "src", "url", "downloadUrl")
            .map { file.optString(it, "").trim() }.firstOrNull { it.isNotEmpty() }
            ?: throw IllegalStateException("GoFile Centerに再生可能な動画が見つかりませんでした。")
        val media = URI(target)
        val host = media.host?.lowercase(Locale.ROOT).orEmpty()
        require(media.scheme == "https" && media.userInfo == null && media.port in setOf(-1, 443) && host.contains('.') &&
            !host.endsWith(".local") && !host.endsWith(".localhost") && host != "localhost" && !host.contains(':') && !host.matches(Regex("[0-9.]+"))) {
            "GoFile Centerの配信先を安全に確認できませんでした。"
        }
        val duration = file.optString("length", "").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val title = file.optString("name").ifBlank { file.optString("title") }.ifBlank { "GoFile Center $id" }.take(300)
        return GofileCenterMedia(id, target, title, duration)
    }
    private fun get(url: String, checkCancel: () -> Unit): String {
        var last: IOException? = null
        repeat(3) { attempt ->
            checkCancel()
            try { return readOnce(url, checkCancel) }
            catch (e: IOException) {
                last = e
                checkCancel()
                if (attempt < 2) Thread.sleep((attempt + 1) * 1000L)
            }
        }
        throw last ?: IOException("GoFile Center connection failed")
    }
    private fun readOnce(url: String, checkCancel: () -> Unit): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443))
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(host in setOf("gofile.center", "www.gofile.center") || host.endsWith(".gofile.mov"))
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 30000; c.readTimeout = 60000; c.instanceFollowRedirects = false; c.useCaches = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.setRequestProperty("Referer", REFERER)
            if (c.responseCode in setOf(401, 403)) throw IllegalStateException("GoFile Centerに取得を拒否されました。元のページの公開範囲や制限を確認してください。")
            if (c.responseCode != 200) throw IOException("GoFile Center HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val buffer = ByteArrayOutputStream(); val chunk = ByteArray(16384)
                while (true) {
                    checkCancel(); val n = input.read(chunk); if (n < 0) break
                    require(buffer.size() + n <= 4 * 1024 * 1024) { "GoFile Centerの応答が大きすぎます。" }
                    buffer.write(chunk, 0, n)
                }
                buffer.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
}
