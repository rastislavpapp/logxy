package io.github.rastislavpapp.logxy.core.index

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LineIndexTest {

    @TempDir
    lateinit var dir: Path

    private fun index(content: String, options: IndexOptions = IndexOptions()): LineIndex =
        index(content.toByteArray(), options)

    private fun index(content: ByteArray, options: IndexOptions = IndexOptions()): LineIndex {
        val file = Files.write(dir.resolve("test-${content.size}-${options.hashCode()}.log"), content)
        return runBlocking { LineIndex.build(file, options = options) }
    }

    private fun LineIndex.lines() = List(lineCount) { lineText(it) }

    @Test
    fun `source text is the line as written, line end included`() {
        index("one\r\ntwo").use { index ->
            assertEquals("one\r\n", index.sourceText(0))
            assertEquals("two", index.sourceText(1))
        }
    }

    @Test
    fun `empty file has no lines`() {
        index("").use { assertEquals(0, it.lineCount) }
    }

    @Test
    fun `trailing newline does not add an empty line`() {
        index("a\nbb\n").use { assertEquals(listOf("a", "bb"), it.lines()) }
    }

    @Test
    fun `last line without newline is included`() {
        index("a\nbb").use { assertEquals(listOf("a", "bb"), it.lines()) }
    }

    @Test
    fun `empty lines and CRLF are handled`() {
        index("\r\n\nx\r\ny\r\n").use { assertEquals(listOf("", "", "x", "y"), it.lines()) }
    }

    @Test
    fun `utf8 text is decoded`() {
        index("žluťoučký kůň\n日本語\n").use { assertEquals(listOf("žluťoučký kůň", "日本語"), it.lines()) }
    }

    @Test
    fun `long lines are cut at a character boundary`() {
        index("abcdef\nžžž\nab\r\n").use {
            assertEquals("abcd…", it.lineText(0, maxBytes = 4))
            assertEquals("žž…", it.lineText(1, maxBytes = 5))
            assertEquals("ab", it.lineText(2, maxBytes = 2))
        }
    }

    @Test
    fun `parallel scan with tiny ranges and buffers matches a single range`() {
        val random = Random(7)
        val content = ByteArray(10_000) { if (random.nextInt(10) == 0) '\n'.code.toByte() else 'x'.code.toByte() }
        val single = index(content, IndexOptions(parallelism = 1))
        val parallel = index(content, IndexOptions(parallelism = 7, minRangeSize = 1, bufferSize = 13))
        single.use {
            parallel.use {
                assertEquals(single.lineCount, parallel.lineCount)
                for (i in 0..<single.lineCount) assertEquals(single.lineStart(i), parallel.lineStart(i))
            }
        }
    }

    @Test
    fun `cancelling stops the scan`() = runBlocking {
        val file = Files.write(dir.resolve("big.log"), ByteArray(4 * 1024 * 1024) { '\n'.code.toByte() })
        val progress = IndexProgress()
        // A tiny buffer makes the scan slow enough to cancel midway.
        val job = launch(Dispatchers.Default) { LineIndex.build(file, progress, IndexOptions(bufferSize = 8)).close() }
        while (progress.bytesDone == 0L) yield()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(progress.bytesDone < progress.totalBytes)
    }

    private fun LineIndex.appendFrom(file: Path, more: String) {
        Files.write(file, more.toByteArray(), StandardOpenOption.APPEND)
        append(readAppended(Files.size(file)))
    }

    private fun growing(content: String): Pair<Path, LineIndex> {
        val file = Files.write(dir.resolve("growing.log"), content.toByteArray())
        return file to runBlocking { LineIndex.build(file) }
    }

    @Test
    fun `appending after a trailing newline adds lines`() {
        val (file, index) = growing("a\n")
        index.use {
            it.appendFrom(file, "b\nc\n")
            assertEquals(listOf("a", "b", "c"), it.lines())
            assertEquals(Files.size(file), it.indexedSize)
        }
    }

    @Test
    fun `appending completes a partial last line`() {
        val (file, index) = growing("a\nhal")
        index.use {
            assertEquals(listOf("a", "hal"), it.lines())
            it.appendFrom(file, "f\r")
            assertEquals(listOf("a", "half"), it.lines())
            it.appendFrom(file, "\nnext")
            assertEquals(listOf("a", "half", "next"), it.lines())
        }
    }

    @Test
    fun `appending to an empty file`() {
        val (file, index) = growing("")
        index.use {
            it.appendFrom(file, "first")
            assertEquals(listOf("first"), it.lines())
        }
    }

    @Test
    fun `appending only a newline does not add an empty line`() {
        val (file, index) = growing("a")
        index.use {
            it.appendFrom(file, "\n")
            assertEquals(listOf("a"), it.lines())
            it.appendFrom(file, "\n")
            assertEquals(listOf("a", ""), it.lines())
        }
    }

    @Test
    fun `appended lines must continue the index`() {
        val (file, index) = growing("a\n")
        index.use {
            Files.write(file, "b\n".toByteArray(), StandardOpenOption.APPEND)
            val first = it.readAppended(Files.size(file))
            it.append(first)
            assertFailsWith<IllegalArgumentException> { it.append(first) }
        }
    }

    @Test
    fun `newline exactly at a range boundary`() {
        // 4 ranges of 3 bytes: newlines at the last byte of each range.
        index("ab\ncd\nef\ngh\n", IndexOptions(parallelism = 4, minRangeSize = 1, bufferSize = 8)).use {
            assertEquals(listOf("ab", "cd", "ef", "gh"), it.lines())
        }
    }
}
