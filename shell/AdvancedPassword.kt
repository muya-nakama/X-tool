package jp.muya.xsaver

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Only a salted, deliberately slow verifier is persisted. */
object AdvancedPassword {
    private const val ITERATIONS = 210_000
    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun create(password: CharArray): String {
        require(password.size in 4..256)
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return JSONObject().put("version", 1).put("salt", Base64.getEncoder().encodeToString(salt))
            .put("verifier", Base64.getEncoder().encodeToString(derive(password, salt))).toString()
    }
    fun verify(password: CharArray, encoded: String): Boolean {
        val record = JSONObject(encoded)
        require(record.getInt("version") == 1)
        val salt = Base64.getDecoder().decode(record.getString("salt"))
        val expected = Base64.getDecoder().decode(record.getString("verifier"))
        require(salt.size == 32 && expected.size == 32)
        return password.size in 4..256 && MessageDigest.isEqual(expected, derive(password, salt))
    }
}

/** Never serialized: leaving the page invalidates even an in-flight password check. */
class AdvancedAccess {
    private var generation = 0
    private var active = false
    var unlocked = false
        private set
    fun resume() { active = true }
    fun lock() { active = false; unlocked = false; generation++ }
    fun begin(): Int = generation
    fun accept(ticket: Int): Boolean {
        if (!active || ticket != generation) return false
        unlocked = true
        return true
    }
}
