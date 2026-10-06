package jp.muya.xsaver

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedAccount(val id: String, val label: String, val cookies: String)

class AccountStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, ".ログイン情報.txt"))
    private val alias = "xsaver.accounts.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun list(): List<SavedAccount> {
        if (!file.baseFile.exists()) return emptyList()
        val text = file.readFully().toString(Charsets.UTF_8)
        require(text.startsWith("XSAVER_LOGIN_V1\n"))
        val data = Base64.decode(text.substringAfter('\n'), Base64.DEFAULT)
        require(data.size in 29..(2 * 1024 * 1024) && data[0].toInt() == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
        cipher.updateAAD("xsaver.accounts.v1".toByteArray())
        val rows = JSONArray(cipher.doFinal(data.copyOfRange(13, data.size)).toString(Charsets.UTF_8))
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            SavedAccount(it.getString("id"), it.getString("label"), SessionCookies.filter(it.getString("cookies")))
        } }
    }
    fun save(label: String, cookies: String, existingId: String? = null): String {
        val id = existingId ?: UUID.randomUUID().toString()
        val accounts = list().filterNot { it.id == id } + SavedAccount(id, label.trim().take(60), SessionCookies.filter(cookies))
        require(accounts.size <= 20)
        write(accounts)
        return id
    }
    fun remove(id: String) { write(list().filterNot { it.id == id }) }
    private fun write(accounts: List<SavedAccount>) {
        if (accounts.isEmpty()) { file.delete(); return }
        val rows = JSONArray().apply { accounts.forEach { a ->
            put(JSONObject().put("id", a.id).put("label", a.label).put("cookies", a.cookies))
        } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("xsaver.accounts.v1".toByteArray())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(rows.toString().toByteArray())
        require(encrypted.size <= 2 * 1024 * 1024) { "Saved sessions exceed storage limit" }
        val stream = file.startWrite()
        val text = "XSAVER_LOGIN_V1\n" + Base64.encodeToString(encrypted, Base64.NO_WRAP) + "\n"
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
