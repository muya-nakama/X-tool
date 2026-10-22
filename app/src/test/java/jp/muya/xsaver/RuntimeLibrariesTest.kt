package jp.muya.xsaver

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeLibrariesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun restoresLibraryAliasesAndChains() {
        val dir = temp.newFolder()
        val data = ByteArray(512) { (it % 127).toByte() }
        java.io.File(dir, "libz.so.1.3.2").writeBytes(data)
        java.io.File(dir, "libz.so.1").writeText("libz.so.1.3.2")
        java.io.File(dir, "libz.so").writeText("libz.so.1")
        RuntimeLibraries.restore(dir)
        assertArrayEquals(data, java.io.File(dir, "libz.so").readBytes())
        assertArrayEquals(data, java.io.File(dir, "libz.so.1").readBytes())
    }
    @Test(expected = IllegalStateException::class) fun rejectsPathOutsideLibraryDirectory() {
        val dir = temp.newFolder()
        java.io.File(dir, "libz.so.1").writeText("../outside")
        RuntimeLibraries.restore(dir)
    }
    @Test(expected = IllegalStateException::class) fun rejectsCycles() {
        val dir = temp.newFolder()
        java.io.File(dir, "liba.so").writeText("libb.so")
        java.io.File(dir, "libb.so").writeText("liba.so")
        RuntimeLibraries.restore(dir)
    }
}
