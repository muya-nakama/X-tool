package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatePackageTest {
    private fun data() = JSONObject().put("url", "https://github.com/muya-nakama/X-tool/releases/download/v1.06/MOVIE-AUDIO-TOOL-v1.06.apk")
        .put("sha256", "a".repeat(64)).put("bytes", 60000000L).put("versionCode", 7)
    @Test fun acceptsExpectedAsset() { assertEquals(7L, UpdatePackage.parse("1.06", data()).versionCode) }
    @Test(expected = IllegalArgumentException::class) fun rejectsOtherRepository() { UpdatePackage.parse("1.06", data().put("url", "https://example.com/update.apk")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsExcessiveFile() { UpdatePackage.parse("1.06", data().put("bytes", 201L * 1024 * 1024)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidHash() { UpdatePackage.parse("1.06", data().put("sha256", "wrong")) }
    @Test fun acceptsOnlyNewerSamePackageAndSigner() {
        assertTrue(UpdatePackage.compatible("app", "app", 6, 7, setOf("one"), setOf("one")))
        assertFalse(UpdatePackage.compatible("app", "other", 6, 7, setOf("one"), setOf("one")))
        assertFalse(UpdatePackage.compatible("app", "app", 6, 7, setOf("one"), setOf("two")))
        assertFalse(UpdatePackage.compatible("app", "app", 7, 7, setOf("one"), setOf("one")))
        assertFalse(UpdatePackage.compatible("app", "app", 6, 7, emptySet(), emptySet()))
    }
    @Test fun computesKnownDigest() { assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdatePackage.hash(byteArrayOf())) }
}
