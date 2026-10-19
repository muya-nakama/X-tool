package jp.muya.xsaver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdvancedPasswordTest {
    @Test fun passwordVerifiesAfterPersistingOnlyVerifier() {
        val password = "test-pass-123".toCharArray()
        val record = AdvancedPassword.create(password)
        assertTrue(AdvancedPassword.verify(password, JSONObject(record).toString()))
        assertFalse(AdvancedPassword.verify("wrong-pass".toCharArray(), record))
        assertFalse(record.contains(String(password)))
    }
    @Test fun randomSaltsPreventIdenticalStoredVerifiers() {
        val password = "same-pass".toCharArray()
        assertNotEquals(AdvancedPassword.create(password), AdvancedPassword.create(password))
    }
    @Test fun malformedVerifierAndUnsupportedVersionFailClosed() {
        val record = JSONObject(AdvancedPassword.create("test-pass".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedPassword.verify("test-pass".toCharArray(), record.put("version", 2).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedPassword.verify("test-pass".toCharArray(), record.put("version", 1).put("salt", "").toString())
        }
    }
    @Test fun emptyOrShortPasswordsCannotBeRegistered() {
        assertThrows(IllegalArgumentException::class.java) { AdvancedPassword.create(charArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedPassword.create("abc".toCharArray()) }
    }
    @Test fun pageStartsLockedAndRequiresActiveScreen() {
        val access = AdvancedAccess()
        assertFalse(access.unlocked)
        assertFalse(access.accept(access.begin()))
        access.resume()
        assertTrue(access.accept(access.begin()))
        assertTrue(access.unlocked)
    }
    @Test fun leavingScreenLocksAndInvalidatesPendingVerification() {
        val access = AdvancedAccess()
        access.resume()
        val ticket = access.begin()
        access.accept(ticket)
        access.lock()
        assertFalse(access.unlocked)
        assertFalse(access.accept(ticket))
        access.resume()
        assertFalse(access.accept(ticket))
        assertFalse(access.unlocked)
        assertTrue(access.accept(access.begin()))
    }
    @Test fun recreatedPageCannotRestoreUnlockedState() {
        val previous = AdvancedAccess().apply { resume(); accept(begin()) }
        assertTrue(previous.unlocked)
        val recreated = AdvancedAccess().apply { resume() }
        assertFalse(recreated.unlocked)
    }
}
