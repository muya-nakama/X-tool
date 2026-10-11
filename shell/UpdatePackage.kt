package jp.muya.xsaver

import org.json.JSONObject
import java.security.MessageDigest

data class UpdatePackage(val version: String, val versionCode: Long, val url: String, val sha256: String, val bytes: Long) {
    companion object {
        fun parse(version: String, json: JSONObject): UpdatePackage {
            require(version.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){1,2}")))
            val url = json.getString("url")
            require(url == "https://github.com/muya-nakama/X-tool/releases/download/v$version/MOVIE-AUDIO-TOOL-v$version.apk")
            val hash = json.getString("sha256").lowercase(java.util.Locale.ROOT)
            require(hash.matches(Regex("[a-f0-9]{64}")))
            val code = json.getLong("versionCode"); val bytes = json.getLong("bytes")
            require(code > 0 && bytes in 1..(200L * 1024 * 1024))
            return UpdatePackage(version, code, url, hash, bytes)
        }
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun compatible(installedPackage: String, candidatePackage: String, installedCode: Long, candidateCode: Long,
                       installedSigners: Set<String>, candidateSigners: Set<String>): Boolean =
            installedPackage == candidatePackage && candidateCode > installedCode &&
                installedSigners.isNotEmpty() && installedSigners == candidateSigners
    }
}
