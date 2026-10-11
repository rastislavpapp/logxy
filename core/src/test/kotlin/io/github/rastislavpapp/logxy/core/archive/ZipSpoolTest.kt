package io.github.rastislavpapp.logxy.core.archive

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class ZipSpoolTest {

    @TempDir
    lateinit var dir: Path

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = 1_790_841_600_000 })
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): Path = Files.write(dir.resolve(name), zipBytes(*entries))

    private fun ZipSpool.names(files: List<Path>) = files.map { root.relativize(it).drop(1).joinToString("/") }

    @Test
    fun `extracts files, also of zips inside the zip, and skips what is no log`() {
        val inner = zipBytes("b.log" to "inner\n".toByteArray())
        val zip = zip(
            "logs.zip",
            "a.log" to "one\n".toByteArray(),
            "sub/" to ByteArray(0),
            "sub/c.log" to "three\n".toByteArray(),
            "old.zip" to inner,
            "__MACOSX/._a.log" to ByteArray(3),
            "sub/.DS_Store" to ByteArray(3),
            "../escape.log" to "no\n".toByteArray(),
        )
        val spool = ZipSpool(dir.resolve("spool"))
        val files = runBlocking { spool.extract(zip) }
        assertEquals(listOf("logs.zip/a.log", "logs.zip/sub/c.log", "logs.zip/old.zip/b.log"), spool.names(files))
        assertEquals("inner\n", Files.readString(files[2]))
        assertFalse(Files.exists(dir.resolve("spool").resolveSibling("escape.log")))
        assertEquals(ZipSpool.Origin(zip.toAbsolutePath(), "old.zip/b.log"), spool.originOf(files[2]))
        assertNull(spool.originOf(zip))
        assertTrue(Files.getLastModifiedTime(files[0]).toMillis() < System.currentTimeMillis() - 60_000)
    }

    @Test
    fun `reuses the files of an unchanged zip and extracts a changed one again`() {
        val zip = zip("logs.zip", "a.log" to "one\n".toByteArray())
        val spool = ZipSpool(dir.resolve("spool"))
        val first = runBlocking { spool.extract(zip) }
        Files.writeString(first.single(), "touched\n")
        assertEquals("touched\n", Files.readString(runBlocking { spool.extract(zip) }.single()))
        zip("logs.zip", "a.log" to "two\n".toByteArray(), "b.log" to "three\n".toByteArray())
        Files.setLastModifiedTime(zip, Files.getLastModifiedTime(zip).let { java.nio.file.attribute.FileTime.fromMillis(it.toMillis() + 5000) })
        val second = runBlocking { spool.extract(zip) }
        assertEquals(listOf("two\n", "three\n"), second.map(Files::readString))
    }

    @Test
    fun `finds an extracted file again from its origin`() {
        val zip = zip("logs.zip", "x/a.log" to "one\n".toByteArray())
        val spool = ZipSpool(dir.resolve("spool"))
        val origin = ZipSpool.parseOrigin("$zip!/x/a.log")!!
        assertEquals("one\n", Files.readString(runBlocking { spool.fileOf(origin) }!!))
        assertNull(runBlocking { spool.fileOf(origin.copy(entry = "missing.log")) })
        assertNull(ZipSpool.parseOrigin("${dir.resolve("none.zip")}!/a.log"))
        assertNull(ZipSpool.parseOrigin(zip.toString()))
    }

    @Test
    fun `tells zips by their content`() {
        assertTrue(ZipSpool.isZip(zip("renamed.log", "a.log" to ByteArray(1))))
        assertTrue(ZipSpool.isZip(zip("empty.zip")))
        assertFalse(ZipSpool.isZip(Files.writeString(dir.resolve("a.zip"), "PK but not a zip")))
        assertFalse(ZipSpool.isZip(dir.resolve("missing.zip")))
    }

    @Test
    fun `cancelling removes what was extracted`() {
        val big = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val zip = zip("big.zip", "a.log" to big, "b.log" to big)
        val spool = ZipSpool(dir.resolve("spool"))
        runBlocking {
            val job = async { spool.extract(zip) { if (it > 0.1) throw CancellationException("stop") } }
            runCatching { job.await() }
        }
        val left = if (Files.exists(spool.root)) Files.walk(spool.root).use { it.filter(Files::isRegularFile).count() } else 0
        assertEquals(0, left)
    }

    @Test
    fun `deletes spools of processes that are gone`() {
        val cache = dir.resolve("cache")
        val stale = Files.createDirectories(cache.resolve("unzipped-999999999/x"))
        val spool = ZipSpool.forThisProcess(cache)
        assertFalse(Files.exists(stale.parent))
        assertEquals("unzipped-${ProcessHandle.current().pid()}", spool.root.fileName.toString())
    }
}
