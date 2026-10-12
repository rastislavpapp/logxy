package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.PatternFormat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShownLogTest {

    @TempDir
    lateinit var dir: Path

    /** Records with these times, [Records.NO_TIMESTAMP] for none; each one's text is "r" and its number. */
    private class TimedRecords(private val times: LongArray) : Records {
        override val schema = Schema.RAW
        override val recordCount get() = times.size
        override val isRaw = true
        override fun level(record: Int): Level? = null
        override fun timestampNanos(record: Int) = times[record]
        override fun rowValues(record: Int, maxBytes: Int) = arrayOf("r$record")
        override fun fieldText(record: Int, field: Int) = "r$record"
        override fun recordText(record: Int, maxBytes: Int) = "r$record"
        override fun sourceText(record: Int) = "r$record\n"
    }

    private val none = Records.NO_TIMESTAMP

    private fun shown(vararg times: Long) = ShownLog(TimedRecords(times))

    private fun ShownLog.records() = List(size) { recordAt(it) }

    @Test
    fun `all records are shown in order until a filter's result is`() {
        val shown = shown(10, 20, 30, 40)
        assertFalse(shown.isFiltered)
        assertEquals(listOf(0, 1, 2, 3), shown.records())
        assertEquals(2, shown.rowOf(2))
        assertEquals(-1, shown.rowOf(4))

        shown.showRows(intArrayOf(1, 3))
        assertTrue(shown.isFiltered)
        assertEquals(listOf(1, 3), shown.records())
        assertEquals(1, shown.rowOf(3))
        // A record the filter hides: the row of the next one shown.
        assertEquals(1, shown.rowOf(2))
        assertEquals(-1, shown.rowOf(4))

        shown.showRows(null)
        assertEquals(4, shown.size)
    }

    @Test
    fun `replacing rows from a record tells the first row that changed`() {
        val shown = shown(*LongArray(8) { it * 10L })
        shown.showRows(intArrayOf(1, 3, 5))
        assertEquals(2, shown.replaceRowsFrom(5, intArrayOf(5, 6, 7)))
        assertEquals(listOf(1, 3, 5, 6, 7), shown.records())
    }

    @Test
    fun `the first row at a time skips rows without one`() {
        val shown = shown(none, 10, none, none, 20, 30, none)
        assertEquals(0, shown.firstRowAt(5))
        assertEquals(0, shown.firstRowAt(10))
        assertEquals(2, shown.firstRowAt(15))
        assertEquals(5, shown.firstRowAt(30))
        // Later than all: the last row.
        assertEquals(6, shown.firstRowAt(99))
        assertEquals(10, shown.timeFrom(0))
        assertEquals(20, shown.timeFrom(2))
        assertEquals(none, shown.timeFrom(6))
        assertEquals(30, shown.timeUntil(6))
        assertEquals(none, shown.timeUntil(0))

        shown.showRows(intArrayOf(2, 3, 5))
        assertEquals(30, shown.timeFrom(0))
        assertEquals(0, shown.firstRowAt(25))
        assertEquals(2, shown.firstRowAt(31))
        assertEquals(-1, shown(*LongArray(0)).firstRowAt(10))
    }

    @Test
    fun `an ended shown log shows nothing`() {
        val shown = shown(10, 20)
        shown.end()
        assertTrue(shown.ended)
        assertEquals(0, shown.size)
        assertEquals(-1, shown.rowOf(0))
        assertEquals(-1, shown.firstRowAt(10))
        assertEquals("", shown.fieldText(0, 0))
        assertEquals("", shown.recordText(0, 100))
        assertEquals("", shown.sourceText(0))
        assertEquals(listOf(""), shown.rowValues(0, 100).toList())
        assertEquals(null, shown.timestamp(0))
        assertEquals(CopyResult.FileChanged, runBlocking { shown.copyColumn(0) })
    }

    private val format = PatternFormat("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p %c - %m%n", ZoneOffset.UTC)

    private fun parse(name: String, content: String): ParsedLog {
        val file = Files.writeString(dir.resolve(name), content)
        return runBlocking { ParsedLog.build(file, format, IndexProgress()) }
    }

    private fun Records.field(name: String) = schema.fields.indexOfFirst { it.name == name }

    @Test
    fun `copies a column of the rows shown or of given records`() {
        parse("a.log", "2026-10-01 10:00:01.000 INFO  a.A - one\n2026-10-01 10:00:02.000 WARN  a.B - two\nat x.y\n2026-10-01 10:00:03.000 INFO  a.A - three\n").use { log ->
            val shown = ShownLog(log)
            val message = log.field("Message")
            assertEquals(CopyResult.Copied("one\ntwo\nat x.y\nthree\n"), runBlocking { shown.copyColumn(message) })
            shown.showRows(intArrayOf(0, 2))
            assertEquals(CopyResult.Copied("a.A\na.A\n"), runBlocking { shown.copyColumn(log.field("Logger")) })
            assertEquals(CopyResult.Copied("WARN\n"), runBlocking { shown.copyColumn(log.field("Level"), intArrayOf(1)) })
        }
    }

    @Test
    fun `copies the records there were while more are appended`() {
        val file = Files.writeString(dir.resolve("live.log"), (1..1000).joinToString("") { "2026-10-01 10:00:01.000 INFO  a.A - m$it\n" })
        runBlocking { ParsedLog.build(file, format, IndexProgress()) }.use { log ->
            val shown = ShownLog(log)
            val result = runBlocking {
                // Runs up to reading on another thread, as from the UI thread.
                val copy = async(start = CoroutineStart.UNDISPATCHED) { shown.copyColumn(log.field("Message")) }
                Files.writeString(file, "more\n2026-10-01 10:00:02.000 INFO  a.A - m1001\n", StandardOpenOption.APPEND)
                log.append(log.readAppended(Files.size(file)))
                copy.await()
            }
            assertEquals(CopyResult.Copied((1..1000).joinToString("") { "m$it\n" }), result)
        }
    }

    @Test
    fun `copies a column of a merge`() {
        val a = parse("a.log", "2026-10-01 10:00:01.000 INFO  a.A - A1\n2026-10-01 10:00:03.000 INFO  a.A - A2\n")
        val b = parse("b.log", "2026-10-01 10:00:02.000 WARN  b.B - B1\n")
        val merged = runBlocking { MergedLog.build(listOf(a, b)) }
        a.use { b.use {
            val shown = ShownLog(merged)
            assertEquals(CopyResult.Copied("A1\nB1\nA2\n"), runBlocking { shown.copyColumn(merged.field("Message")) })
            assertEquals(CopyResult.Copied("b.log\na.log\n"), runBlocking { shown.copyColumn(merged.field("Source"), intArrayOf(1, 2)) })
        } }
    }
}
