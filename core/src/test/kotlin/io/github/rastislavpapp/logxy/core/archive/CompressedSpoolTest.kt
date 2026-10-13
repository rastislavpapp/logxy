package io.github.rastislavpapp.logxy.core.archive

import com.github.luben.zstd.Zstd
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressedSpoolTest {

    @TempDir
    lateinit var dir: Path

    private fun gzip(name: String, content: String): Path {
        val file = dir.resolve(name)
        GZIPOutputStream(Files.newOutputStream(file)).use { it.write(content.toByteArray()) }
        return file
    }

    private fun zstd(name: String, content: String): Path =
        Files.write(dir.resolve(name), Zstd.compress(content.toByteArray()))

    private fun CompressedSpool.decompress(file: Path) =
        runBlocking { decompress(file, CompressedSpool.formatOf(file)!!) }

    @Test
    fun `decompresses gzip and zstd files`() {
        val spool = CompressedSpool(dir.resolve("spool"))
        val gz = spool.decompress(gzip("a.log.gz", "gzip\n"))
        val zst = spool.decompress(zstd("b.log.zst", "zstd\n"))
        assertEquals("a.log", gz.fileName.toString())
        assertEquals("gzip\n", Files.readString(gz))
        assertEquals("b.log", zst.fileName.toString())
        assertEquals("zstd\n", Files.readString(zst))
    }

    @Test
    fun `reuses the file of an unchanged source and decompresses a changed one again`() {
        val spool = CompressedSpool(dir.resolve("spool"))
        val source = gzip("app.log.gz", "v1\n")
        val first = spool.decompress(source)
        assertEquals(first, spool.decompress(source))

        gzip("app.log.gz", "v2 longer\n")
        Files.setLastModifiedTime(source, FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 2000))
        assertEquals("v2 longer\n", Files.readString(spool.decompress(source)))
    }

    @Test
    fun `keeps the source's modification time`() {
        val source = gzip("app.log.gz", "data\n")
        val time = FileTime.fromMillis(1_600_000_000_000)
        Files.setLastModifiedTime(source, time)
        assertEquals(time, Files.getLastModifiedTime(CompressedSpool(dir.resolve("spool")).decompress(source)))
    }

    @Test
    fun `tells the format by the first bytes`() {
        assertEquals(CompressedSpool.Format.GZIP, CompressedSpool.formatOf(gzip("x.bin", "a")))
        assertEquals(CompressedSpool.Format.ZSTD, CompressedSpool.formatOf(zstd("y.bin", "a")))
        assertNull(CompressedSpool.formatOf(Files.writeString(dir.resolve("plain.gz"), "not compressed\n")))
        assertNull(CompressedSpool.formatOf(dir.resolve("missing.gz")))
    }

    @Test
    fun `names a decompressed file by its source`() {
        val spool = CompressedSpool(dir.resolve("spool"))
        val source = gzip("app.log.gz", "data\n").toAbsolutePath().normalize()
        val file = spool.decompress(source)
        val origin = spool.originOf(file)!!
        assertEquals(source, origin.container)
        assertEquals("", origin.entry)
        // Reopening the saved text decompresses the source again; it must not look like a zip entry.
        assertEquals(source.toString(), origin.toString())
        assertNull(ZipSpool.parseOrigin(origin.toString()))
        assertEquals(listOf("app.log.gz"), MergedLog.sourceNames(listOf(file), spool::originOf))
    }

    @Test
    fun `deletes spools of processes that are gone`() {
        val cache = dir.resolve("cache")
        val stale = Files.createDirectories(cache.resolve("decompressed-999999999/x"))
        val spool = CompressedSpool.forThisProcess(cache)
        assertTrue(Files.notExists(stale.parent))
        assertEquals("decompressed-${ProcessHandle.current().pid()}", spool.root.fileName.toString())
    }
}
