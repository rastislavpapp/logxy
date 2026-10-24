package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.AppendedData
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.RowText
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.CompiledPattern
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
    private class TimedRecords(
        private val times: LongArray,
        private val levels: List<Level?> = List(times.size) { null },
    ) : Records {
        override val schema = Schema.RAW
        override val recordCount get() = times.size
        override val isRaw = true
        override fun level(record: Int): Level? = levels[record]
        override fun timestampNanos(record: Int) = times[record]
        override fun row(record: Int, maxBytes: Int, countBytes: Int) = RowText(arrayOf("r$record"), 0)
        override fun fieldText(record: Int, field: Int) = "r$record"
        override fun recordText(record: Int, maxBytes: Int) = "r$record"
        override fun laterLines(record: Int, maxBytes: Int, cutMark: String) = ""
        override fun sourceText(record: Int) = "r$record\n"
    }

    /** Loaded [records] that only time lookups and reads use. */
    private class Timed(override val records: Records) : LoadedLog {
        override val parts get() = throw UnsupportedOperationException()
        override val origin get() = throw UnsupportedOperationException()
        override fun newFilter() = throw UnsupportedOperationException()
        override fun firstRecordFrom(part: Int, record: Int) = throw UnsupportedOperationException()
        override fun templateOf(record: Int) = throw UnsupportedOperationException()
        override fun fieldReader(field: Int, records: IntArray?, count: Int) = throw UnsupportedOperationException()
        override fun sourceReader() = throw UnsupportedOperationException()
        override fun append(part: Int, data: AppendedData) = throw UnsupportedOperationException()
        override suspend fun settle() = throw UnsupportedOperationException()
    }

    private val none = Records.NO_TIMESTAMP

    private fun shown(vararg times: Long) = ShownLog(Timed(TimedRecords(times)))

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
        // For a record the filter hides, rowOf gives the row of the next record shown.
        assertEquals(1, shown.rowOf(2))
        assertEquals(-1, shown.rowOf(4))

        shown.showRows(null)
        assertEquals(4, shown.size)
    }

    @Test
    fun `replacing rows from a record tells the first row that changed`() {
        val shown = shown(*LongArray(8) { it * 10L })
        shown.showRows(intArrayOf(1, 3, 5))
        // Record 5 is still shown: rows change only after it.
        assertEquals(3, shown.replaceRowsFrom(5, intArrayOf(5, 6, 7)))
        assertEquals(listOf(1, 3, 5, 6, 7), shown.records())
        assertEquals(2, shown.replaceRowsFrom(5, intArrayOf(6, 7)))
        assertEquals(listOf(1, 3, 6, 7), shown.records())
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
    fun `the first row in a time range at a level`() {
        val times = longArrayOf(10, 20, none, 30, 40, 50)
        val levels = listOf(Level.ERROR, Level.INFO, Level.FATAL, Level.WARN, Level.ERROR, Level.WARN)
        val shown = ShownLog(Timed(TimedRecords(times, levels)))
        assertEquals(1, shown.firstRowIn(20, 50, TimeHistogram.OTHER))
        // A row without a time between rows in the range counts, and fatal counts as an error.
        assertEquals(2, shown.firstRowIn(20, 50, TimeHistogram.ERROR))
        assertEquals(3, shown.firstRowIn(20, 50, TimeHistogram.WARN))
        // The end is exclusive.
        assertEquals(-1, shown.firstRowIn(40, 50, TimeHistogram.WARN))

        shown.showRows(intArrayOf(0, 1, 3, 4))
        assertEquals(3, shown.firstRowIn(20, 50, TimeHistogram.ERROR))
        assertEquals(-1, shown(*LongArray(0)).firstRowIn(0, 99, TimeHistogram.OTHER))
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
        assertEquals(CopyResult.FileChanged, runBlocking { shown.copyColumn(0, limit = 100).run() })
        assertEquals(CopyResult.FileChanged, runBlocking { shown.copyRows(RowRanges(), limit = 100).run() })
    }

    private val parser = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p %c - %m%n", ZoneOffset.UTC)

    private fun parse(name: String, content: String): ParsedLog {
        val file = Files.writeString(dir.resolve(name), content)
        return runBlocking { ParsedLog.build(file, parser, IndexProgress()) }
    }

    private fun Records.field(name: String) = schema.fields.indexOfFirst { it.name == name }

    @Test
    fun `copies a column of the rows shown or of given rows`() {
        val content = "2026-10-01 10:00:01.000 INFO  a.A - one\n" +
            "2026-10-01 10:00:02.000 WARN  a.B - two\nat x.y\n" +
            "2026-10-01 10:00:03.000 DEBUG a.A - three\n"
        parse("a.log", content).use { log ->
            val shown = ShownLog(SingleLoad(log))
            val message = shown.copyColumn(log.field("Message"), limit = 1000)
            assertEquals(CopyResult.Copied("one\ntwo\nat x.y\nthree\n"), runBlocking { message.run() })
            assertEquals(1.0, message.progress)
            shown.showRows(intArrayOf(0, 2))
            val logger = runBlocking { shown.copyColumn(log.field("Logger"), limit = 1000).run() }
            assertEquals(CopyResult.Copied("a.A\na.A\n"), logger)
            val level = runBlocking { shown.copyColumn(log.field("Level"), limit = 1000, rows(1..1)).run() }
            assertEquals(CopyResult.Copied("DEBUG\n"), level)
        }
    }

    @Test
    fun `stops copying a column once it takes more than the limit`() {
        val content = (1..100).joinToString("") { "2026-10-01 10:00:01.000 INFO  a.A - m${it % 10}\n" }
        parse("a.log", content).use { log ->
            val shown = ShownLog(SingleLoad(log))
            // Each value is "m" and a digit, and a line end.
            val result = runBlocking { shown.copyColumn(log.field("Message"), limit = 299).run() }
            assertEquals(CopyResult.TooLarge(rows = 100, bytes = 300), result)
            val fits = runBlocking { shown.copyColumn(log.field("Message"), limit = 300).run() }
            assertTrue(fits is CopyResult.Copied)
        }
    }

    private fun rows(vararg ranges: IntRange) = RowRanges().apply { ranges.forEach { add(it.first, it.last + 1) } }

    @Test
    fun `copies rows as written in the file, shown or filtered`() {
        val content = "2026-10-01 10:00:01.000 INFO  a.A - one\n" +
            "2026-10-01 10:00:02.000 WARN  a.B - two\nat x.y\n" +
            "2026-10-01 10:00:03.000 INFO  a.A - three\n" +
            "2026-10-01 10:00:04.000 INFO  a.A - four"
        parse("a.log", content).use { log ->
            val shown = ShownLog(SingleLoad(log))
            val copy = shown.copyRows(rows(1..1, 3..3), limit = 1000)
            // The last line has no line end in the file; the copy ends it.
            val expected = "2026-10-01 10:00:02.000 WARN  a.B - two\nat x.y\n2026-10-01 10:00:04.000 INFO  a.A - four\n"
            assertEquals(CopyResult.Copied(expected), runBlocking { copy.run() })
            assertEquals(1.0, copy.progress)
            shown.showRows(intArrayOf(0, 2, 3))
            val filtered = runBlocking { shown.copyRows(rows(0..1), limit = 1000).run() }
            val both = "2026-10-01 10:00:01.000 INFO  a.A - one\n2026-10-01 10:00:03.000 INFO  a.A - three\n"
            assertEquals(CopyResult.Copied(both), filtered)
        }
    }

    @Test
    fun `refuses rows larger than the limit without reading them`() {
        val content = (1..100).joinToString("") { "2026-10-01 10:00:01.000 INFO  a.A - m${it % 10}\n" }
        parse("a.log", content).use { log ->
            val shown = ShownLog(SingleLoad(log))
            val lineBytes = 39L
            val copy = shown.copyRows(rows(0..9, 50..59), limit = 20 * lineBytes - 1)
            // Closed: reading would fail, so the answer must come from the index alone.
            log.close()
            assertEquals(CopyResult.TooLarge(rows = 20, bytes = 20 * lineBytes), runBlocking { copy.run() })
        }
    }

    @Test
    fun `copies merged rows in merged order`() {
        val a = parse("a.log", "2026-10-01 10:00:01.000 INFO  a.A - A1\n2026-10-01 10:00:03.000 INFO  a.A - A2\n")
        val b = parse("b.log", "2026-10-01 10:00:02.000 WARN  b.B - B1\n")
        val merged = runBlocking { MergedLog.build(listOf(a, b)) }
        a.use {
            b.use {
                val shown = ShownLog(MergedLoad(merged))
                val result = runBlocking { shown.copyRows(rows(0..2), limit = 1000).run() }
                val expected = "2026-10-01 10:00:01.000 INFO  a.A - A1\n2026-10-01 10:00:02.000 WARN  b.B - B1\n" +
                    "2026-10-01 10:00:03.000 INFO  a.A - A2\n"
                assertEquals(CopyResult.Copied(expected), result)
            }
        }
    }

    @Test
    fun `a copy of rows tells that the file changed when the load ended meanwhile`() {
        parse("a.log", "2026-10-01 10:00:01.000 INFO  a.A - one\n").use { log ->
            val shown = ShownLog(SingleLoad(log))
            val copy = shown.copyRows(rows(0..0), limit = 1000)
            shown.end()
            log.close()
            assertEquals(CopyResult.FileChanged, runBlocking { copy.run() })
        }
    }

    @Test
    fun `copies the records there were while more are appended`() {
        val content = (1..1000).joinToString("") { "2026-10-01 10:00:01.000 INFO  a.A - m$it\n" }
        val file = Files.writeString(dir.resolve("live.log"), content)
        runBlocking { ParsedLog.build(file, parser, IndexProgress()) }.use { log ->
            val shown = ShownLog(SingleLoad(log))
            val result = runBlocking {
                // Runs up to reading on another thread, as from the UI thread.
                val copy = async(start = CoroutineStart.UNDISPATCHED) {
                    shown.copyColumn(log.field("Message"), limit = Long.MAX_VALUE).run()
                }
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
        a.use {
            b.use {
                val shown = ShownLog(MergedLoad(merged))
                val messages = runBlocking { shown.copyColumn(merged.field("Message"), limit = 1000).run() }
                assertEquals(CopyResult.Copied("A1\nB1\nA2\n"), messages)
                val sources = runBlocking { shown.copyColumn(merged.field("Source"), limit = 1000, rows(1..2)).run() }
                assertEquals(CopyResult.Copied("b.log\na.log\n"), sources)
            }
        }
    }
}
