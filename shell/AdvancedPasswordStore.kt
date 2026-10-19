package jp.muya.xsaver

import android.content.Context
import android.util.AtomicFile
import java.io.File

class AdvancedPasswordStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "advanced-password-v1.json"))
    companion object { private val lock = Any() }
    fun configured(): Boolean = synchronized(lock) { file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() }
    fun verify(password: CharArray): Boolean = synchronized(lock) {
        AdvancedPassword.verify(password, file.readFully().toString(Charsets.UTF_8))
    }
    fun create(password: CharArray) = synchronized(lock) {
        check(!configured())
        val record = AdvancedPassword.create(password)
        val stream = file.startWrite()
        try { stream.write(record.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
