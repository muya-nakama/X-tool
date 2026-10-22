package jp.muya.xsaver

import java.io.File

/** Some upstream ZIP entries contain Unix link targets. Android's ZIP reader extracts them as text. */
object RuntimeLibraries {
    private val library = Regex("lib[A-Za-z0-9_+.-]+\\.so(?:\\.[0-9]+)*")
    fun restore(directory: File) {
        fun resolve(file: File, depth: Int): File {
            check(depth < 12) { "通信部品の参照が循環しています。" }
            check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) { "通信部品の参照先を確認できません。" }
            if (file.length() >= 256) return file
            val target = file.readText().trim()
            check(library.matches(target)) { "通信部品の参照先が不正です。" }
            return resolve(File(directory, target), depth + 1)
        }
        directory.listFiles().orEmpty().filter { it.isFile && library.matches(it.name) && it.length() < 256 }.forEach { alias ->
            val target = resolve(alias, 0)
            target.copyTo(alias, overwrite = true)
        }
    }
}
