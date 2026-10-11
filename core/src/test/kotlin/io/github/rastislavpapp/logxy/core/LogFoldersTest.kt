package io.github.rastislavpapp.logxy.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class LogFoldersTest {

    @TempDir
    lateinit var dir: Path

    private fun write(name: String, content: ByteArray): Path =
        dir.resolve(name).also { Files.createDirectories(it.parent) }.also { Files.write(it, content) }

    @Test
    fun `finds log files, in subfolders too, leaving out what cannot be a log`() {
        write("b.log", "two\n".toByteArray())
        write("a.log", "one\n".toByteArray())
        write("sub/deep/c.txt", "three\n".toByteArray())
        write("sub/empty.log", ByteArray(0))
        write("sub/image.png", byteArrayOf(-119, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13))
        write(".hidden.log", "no\n".toByteArray())
        write(".git/config", "no\n".toByteArray())
        val zip = dir.resolve("old.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { it.putNextEntry(ZipEntry("x.log")); it.write(0); it.closeEntry() }

        fun names(recursive: Boolean) = LogFolders.files(dir, recursive).map { dir.relativize(it).joinToString("/") }
        assertEquals(listOf("a.log", "b.log", "old.zip", "sub/deep/c.txt"), names(recursive = true))
        assertEquals(listOf("a.log", "b.log", "old.zip"), names(recursive = false))
    }
}
