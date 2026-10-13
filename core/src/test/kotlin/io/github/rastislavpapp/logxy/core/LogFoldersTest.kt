package io.github.rastislavpapp.logxy.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogFoldersTest {

    @TempDir
    lateinit var dir: Path

    private fun write(name: String, content: ByteArray): Path =
        dir.resolve(name).also { Files.createDirectories(it.parent) }.also { Files.write(it, content) }

    @Test
    fun `finds log files, in subfolders too, leaving out what cannot be a log`() = runBlocking {
        write("b.log", "two\n".toByteArray())
        write("a.log", "one\n".toByteArray())
        write("sub/deep/c.txt", "three\n".toByteArray())
        write("sub/empty.log", ByteArray(0))
        write("sub/image.png", byteArrayOf(-119, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13))
        write(".hidden.log", "no\n".toByteArray())
        write(".git/config", "no\n".toByteArray())
        val zip = dir.resolve("old.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use {
            it.putNextEntry(ZipEntry("x.log"))
            it.write(0)
            it.closeEntry()
        }

        suspend fun names(recursive: Boolean) =
            LogFolders.files(dir, recursive).map { dir.relativize(it).joinToString("/") }
        assertEquals(listOf("a.log", "b.log", "old.zip", "sub/deep/c.txt"), names(recursive = true))
        assertEquals(listOf("a.log", "b.log", "old.zip"), names(recursive = false))
    }

    @Test
    fun `finds linked log files and stops at link loops`() = runBlocking {
        val target = write("pods/app/0.log", "one\n".toByteArray())
        val containers = Files.createDirectories(dir.resolve("containers"))
        // Windows creates links only with a privilege.
        assumeTrue(runCatching { Files.createSymbolicLink(containers.resolve("app.log"), target) }.isSuccess)
        Files.createSymbolicLink(containers.resolve("loop"), containers)
        Files.createSymbolicLink(containers.resolve("gone.log"), dir.resolve("missing.log"))
        assertEquals(listOf(containers.resolve("app.log")), LogFolders.files(containers))
        // The link and its target are found once.
        assertEquals(listOf(containers.resolve("app.log")), LogFolders.files(dir))
    }

    @Test
    fun `a cancelled walk stops at the next file`() = runBlocking {
        repeat(300) { write("sub$it/$it.log", "line\n".toByteArray()) }
        var counted = 0
        val walk = async(Dispatchers.IO) {
            val self = coroutineContext.job
            LogFolders.files(dir) { found ->
                counted = found
                if (found == 1) self.cancel()
            }
        }
        assertFailsWith<CancellationException> { walk.await() }
        assertEquals(1, counted)
    }
}
