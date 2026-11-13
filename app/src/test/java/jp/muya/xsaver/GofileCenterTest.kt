package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GofileCenterTest {
    private fun response(status: String = "0", folder: Boolean = false, url: String = "https://media.example.test/master.m3u8") =
        JSONObject().put("code", 0).put("data", JSONObject().put("rules", JSONObject().put("status", status))
            .put("info", JSONObject().put("netDiskInfo", JSONObject().put("name", "Test video").put("length", "473")
                .put("fileUrl", url).put("isFolder", folder))))
    @Test fun recognizesOnlyExactServiceHosts() {
        assertTrue(GofileCenter.supports("https://gofile.center/abc123"))
        assertTrue(GofileCenter.supports("https://www.gofile.center/abc123"))
        assertFalse(GofileCenter.supports("https://gofile.center.evil.test/abc123"))
        assertFalse(GofileCenter.supports("https://gofile.io/d/abc123"))
        assertTrue(GofileCenter.supports("https://gofile.diy/abc123"))
        assertTrue(GofileCenter.supports("https://www.gofile.diy/abc123"))
        assertFalse(GofileCenter.supports("https://gofile.diy.evil.test/abc123"))
        assertEquals("GoFile Center", MediaLink.folder("https://gofile.center/abc123"))
    }
    @Test fun discoversBackendAndRequestsOnlyPublicMetadata() {
        val calls = mutableListOf<String>()
        val media = GofileCenter.resolve("https://gofile.center/abc123", read = { url ->
            calls.add(url)
            when (calls.size) {
                1 -> "<script type=\"module\" src=\"/assets/index-test.js\"></script>"
                2 -> "function origin(){return `https://example.gofile.mov`;}"
                else -> response().toString()
            }
        })
        assertEquals("https://example.gofile.mov/app-api/flow/land-page/getInfo?externalLinks=abc123&domain=gofile.center", calls.last())
        assertEquals(3, calls.size)
        assertEquals(473.0, media.duration, 0.0)
        assertEquals("https://media.example.test/master.m3u8", media.url)
    }
    @Test fun diyUsesItsOwnPageDomainAndReferer() {
        val calls = mutableListOf<String>()
        val media = GofileCenter.resolve("https://www.gofile.diy/abc123", read = { url ->
            calls.add(url)
            when (calls.size) {
                1 -> "<script src='/assets/index-test.js'></script>"
                2 -> "const origin = 'https://example.gofile.mov';"
                else -> response().toString()
            }
        })
        assertEquals("https://gofile.diy/abc123", calls[0])
        assertEquals("https://gofile.diy/assets/index-test.js", calls[1])
        assertTrue(calls[2].endsWith("&domain=gofile.diy"))
        assertEquals("https://gofile.diy/", media.referer)
        assertEquals("GoFile Center", MediaLink.folder("https://gofile.diy/abc123"))
        assertEquals("https://gofile.center/", GofileCenter.referer("https://gofile.center/abc123"))
    }
    @Test fun twfilesUsesItsOwnBackendAndIdentity() {
        val calls = mutableListOf<String>()
        val media = GofileCenter.resolve("https://twfiles.com/xyz123", read = { url ->
            calls.add(url)
            when (calls.size) {
                1 -> "<script src='/assets/index-test.js'></script>"
                2 -> "function origin(){return validate('https://example.fun800.click', window.location.origin)}"
                else -> response().toString()
            }
        })
        assertEquals("https://twfiles.com/xyz123", calls[0])
        assertEquals("https://example.fun800.click/app-api/flow/land-page/getInfo?externalLinks=xyz123&domain=twfiles.com", calls[2])
        assertEquals("https://twfiles.com/", media.referer)
        assertEquals("TwFiles", MediaLink.folder("https://twfiles.com/xyz123"))
        assertEquals("TwFiles", media.annotate(JSONObject()).getString("extractor_key"))
        assertEquals("TwFiles", media.annotate(JSONObject()).getString("uploader"))
        assertTrue(GofileCenter.supports("https://www.twfiles.com/xyz123"))
        assertFalse(GofileCenter.supports("https://twifiles.com/xyz123"))
        assertFalse(GofileCenter.supports("https://twfiles.com.evil.test/xyz123"))
    }
    @Test fun twimgfileUsesItsOwnBackendAndIdentity() {
        val calls = mutableListOf<String>()
        val media = GofileCenter.resolve("https://twimgfile.com/xyz123", read = { url ->
            calls.add(url)
            when (calls.size) {
                1 -> "<script src='/assets/index-test.js'></script>"
                2 -> "function origin(){return validate('https://example.fun800.click', window.location.origin)}"
                else -> response().toString()
            }
        })
        assertEquals("https://twimgfile.com/xyz123", calls[0])
        assertEquals("https://example.fun800.click/app-api/flow/land-page/getInfo?externalLinks=xyz123&domain=twimgfile.com", calls[2])
        assertEquals("https://twimgfile.com/", media.referer)
        assertEquals("TwimgFile", MediaLink.folder("https://twimgfile.com/xyz123"))
        assertEquals("TwimgFile", media.annotate(JSONObject()).getString("extractor_key"))
        assertEquals("TwimgFile", media.annotate(JSONObject()).getString("uploader"))
        assertTrue(GofileCenter.supports("https://www.twimgfile.com/xyz123"))
        assertFalse(GofileCenter.supports("https://twimgfiles.com/xyz123"))
        assertFalse(GofileCenter.supports("https://twimgfile.com.evil.test/xyz123"))
    }
    @Test fun newGofileDomainsKeepTheirOwnRefererAndFolder() {
        for (domain in listOf("gofile.guru", "gofile.meme")) {
            val calls = mutableListOf<String>()
            val media = GofileCenter.resolve("https://www.$domain/xyz123", read = { url ->
                calls.add(url)
                when (calls.size) {
                    1 -> "<script src='/assets/index-current.js'></script>"
                    2 -> "return `https://mpnxcr.gofile.mov`;"
                    else -> response().toString()
                }
            })
            assertEquals("https://$domain/", media.referer)
            assertEquals(domain, MediaLink.folder("https://$domain/xyz123"))
            assertEquals(domain, media.annotate(JSONObject()).getString("extractor_key"))
            assertEquals("https://mpnxcr.gofile.mov/app-api/flow/land-page/getInfo?externalLinks=xyz123&domain=$domain", calls.last())
            assertFalse(GofileCenter.supports("https://$domain.evil.test/xyz123"))
        }
    }
    @Test fun bidUsesSameOriginMetadataProxy() {
        val calls = mutableListOf<String>()
        val media = GofileCenter.resolve("https://gofile.bid/xyz123", read = { url ->
            calls.add(url)
            when (calls.size) {
                1 -> "<script src='/assets/index-current.js'></script>"
                2 -> "var base=`/api.php`; params.set(`endpoint`,`/flow/land-page/getInfo`);"
                else -> response().toString()
            }
        })
        assertEquals("https://gofile.bid/api.php?endpoint=%2Fflow%2Fland-page%2FgetInfo&externalLinks=xyz123&domain=gofile.bid", calls.last())
        assertEquals("https://gofile.bid/", media.referer)
        assertEquals("gofile.bid", MediaLink.folder("https://gofile.bid/xyz123"))
        assertEquals("gofile.bid", media.annotate(JSONObject()).getString("extractor_key"))
        assertFalse(GofileCenter.supports("https://gofile.bid.evil.test/xyz123"))
    }
    @Test(expected = IllegalStateException::class) fun unknownSitesCannotSelectBidProxy() {
        GofileCenter.metadataEndpoint("/api.php endpoint /flow/land-page/getInfo", "https://unknown.example/", "xyz123")
    }
    @Test fun tweetjpReadsOnlyItsApplicationBundleAndMetadata() {
        val calls = mutableListOf<String>()
        val media = GofileCenter.resolve("https://www.tweetjp.com/?link=axyz123.html", read = { url ->
            calls.add(url)
            when (calls.size) {
                1 -> "<script src='/assets/popunder-rotation-test.js'></script><script src='/assets/ad-runtime-test.js'></script><script src='/assets/app-test.js'></script>"
                2 -> """const origin = "https://rwzugqnp.fun800.click";"""
                else -> response().also { it.getJSONObject("data").getJSONObject("info").getJSONObject("netDiskInfo").put("length", "07:53") }.toString()
            }
        })
        assertEquals(listOf(
            "https://tweetjp.com/?link=axyz123.html",
            "https://tweetjp.com/assets/app-test.js",
            "https://rwzugqnp.fun800.click/app-api/flow/land-page/getInfo?externalLinks=xyz123&domain=tweetjp.com"
        ), calls)
        assertEquals("https://tweetjp.com/", media.referer)
        assertEquals("tweetjp.com", MediaLink.folder("https://tweetjp.com/xyz123"))
        assertEquals("tweetjp.com", media.annotate(JSONObject()).getString("extractor_key"))
        assertEquals(473.0, media.duration, 0.0)
    }
    @Test fun tweetjpAcceptsThePageLinkForms() {
        for (link in listOf("xyz123", "axyz123.html", "video/xyz123.html", "?link=xyz123", "?externalLinks=xyz123")) {
            assertEquals("xyz123", GofileCenter.linkId("https://tweetjp.com/$link"))
        }
        assertFalse(GofileCenter.supports("https://tweetjp.com.evil.test/xyz123"))
        assertFalse(GofileCenter.supports("https://not-tweetjp.com/xyz123"))
    }
    @Test(expected = IllegalArgumentException::class) fun tweetjpRejectsInjectedLinkParameter() {
        GofileCenter.linkId("https://tweetjp.com/?link=xyz123%26domain%3Devil.test")
    }
    @Test(expected = IllegalArgumentException::class) fun tweetjpRequiresAVideoLink() {
        GofileCenter.linkId("https://tweetjp.com/")
    }
    @Test fun durationAcceptsClockNotationWithoutNonFiniteValues() {
        assertEquals(473.5, GofileCenter.durationSeconds("07:53.5"), 0.0)
        assertEquals(3723.0, GofileCenter.durationSeconds("01:02:03"), 0.0)
        for (bad in listOf("NaN", "Infinity", "01:60", "1:NaN", "-1:10")) {
            assertEquals(0.0, GofileCenter.durationSeconds(bad), 0.0)
        }
    }
    @Test fun compatibleSiteUsesOnlyItsOwnPageAndRecognizedBackend() {
        val calls = mutableListOf<String>()
        val media = GofileCenter.resolveCompatible("https://public.example/xyz123", read = { url ->
            calls.add(url)
            when (calls.size) {
                1 -> "<script src='/assets/index-test.js'></script>"
                2 -> "const origin = 'https://example.fun800.click';"
                else -> response().toString()
            }
        })
        assertEquals(3, calls.size)
        assertEquals("https://public.example/xyz123", calls[0])
        assertTrue(calls[2].endsWith("&domain=public.example"))
        assertEquals("https://public.example/", media.referer)
        assertEquals("public.example", media.annotate(JSONObject()).getString("extractor_key"))
        assertFalse(GofileCenter.compatibleCandidate("https://127.0.0.1/xyz123"))
        assertFalse(GofileCenter.compatibleCandidate("https://server.local/xyz123"))
        assertFalse(GofileCenter.compatibleCandidate("http://public.example/xyz123"))
        assertFalse(GofileCenter.compatibleCandidate("https://user:pass@public.example/xyz123"))
    }
    @Test(expected = IllegalStateException::class) fun compatibleSiteRejectsUnrecognizedBackend() {
        GofileCenter.resolveCompatible("https://public.example/xyz123", read = { url ->
            if (url.endsWith(".js")) "const origin = 'https://unknown.example';"
            else "<script src='/assets/index-test.js'></script>"
        })
    }
    @Test fun fallbackIsLimitedToUnsupportedErrors() {
        assertTrue(DownloadFailure.unsupported(Exception("ERROR: Unsupported URL")))
        assertFalse(DownloadFailure.unsupported(Exception("HTTP Error 403")))
        assertFalse(DownloadFailure.unsupported(Exception("Login required")))
        assertFalse(DownloadFailure.unsupported(Exception("Connection timed out")))
    }
    @Test fun supportsStringEncodedObjects() {
        val r = response()
        val data = r.getJSONObject("data")
        val info = data.getJSONObject("info")
        info.put("netDiskInfo", info.getJSONObject("netDiskInfo").toString())
        data.put("info", info.toString()); r.put("data", data.toString())
        assertEquals("Test video", GofileCenter.parse("abc123", r).title)
    }
    @Test(expected = IllegalStateException::class) fun rejectsPasswordProtectedLink() { GofileCenter.parse("abc123", response("4")) }
    @Test(expected = IllegalStateException::class) fun rejectsExpiredLink() { GofileCenter.parse("abc123", response("3")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsFolder() { GofileCenter.parse("abc123", response(folder = true)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsLocalMediaUrl() { GofileCenter.parse("abc123", response(url = "https://127.0.0.1/file")) }
    @Test(expected = IllegalStateException::class) fun rejectsApiFailure() { GofileCenter.parse("abc123", response().put("code", 401)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathInjection() { GofileCenter.linkId("https://gofile.center/a/b") }
    @Test fun stableIdentitySurvivesChangingMediaUrl() {
        val a = JSONObject().put("id", "changing-a").put("url", "https://media.example.test/a")
        val b = JSONObject().put("id", "changing-b").put("url", "https://media.example.test/b")
        val media = GofileCenter.parse("abc123", response())
        assertEquals(DownloadIdentity.media(media.annotate(a), "https://gofile.center/abc123"), DownloadIdentity.media(media.annotate(b), "https://gofile.center/abc123"))
    }
    @Test fun parsedHlsMetadataProvidesVideoAndAudioChoices() {
        val raw = JSONObject("""{"formats":[{"format_id":"701","ext":"mp4","height":360,"vcodec":"avc1.64001e","acodec":"mp4a.40.2","url":"https://media.example.test/stream.m3u8","tbr":701}]}""")
        val media = GofileCenter.parse("abc123", response()).annotate(raw)
        val result = MediaFormats.parse("https://gofile.center/abc123", media)
        assertTrue(result.choices.any { !it.audio })
        assertTrue(result.choices.any { it.audio })
        assertEquals(473.0, result.duration, 0.0)
        assertEquals("GoFile Center", result.uploader)
    }
}
