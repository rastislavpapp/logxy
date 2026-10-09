package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.PatternFormat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FilterEngineTest {

    @TempDir
    lateinit var dir: Path

    private val format = PatternFormat("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n", ZoneOffset.UTC)

    private val sample = """
        banner before any record
        2026-10-01 08:00:00.000 [main] INFO  app.Main - Started žluťoučký kůň
        2026-10-01 08:00:01.000 [worker-1] ERROR app.Db - Query failed
        java.sql.SQLException: boom
        	at app.Db.run(Db.java:42)
        2026-10-01 08:00:02.000 [worker-2] WARN  app.Http - Slow request /orders
        2026-10-01 08:00:03.000 [main] DEBUG app.Main - Tick
        2026-10-01 08:00:04.000 [worker-1] ERROR app.Http - Timeout calling BILLING
    """.trimIndent() + "\n"

    private fun parse(content: String, options: IndexOptions = IndexOptions()): ParsedLog {
        val file = Files.writeString(dir.resolve("f-${content.hashCode()}-${options.hashCode()}.log"), content)
        return runBlocking { ParsedLog.build(file, format, IndexProgress(), options) }
    }

    private fun FilterEngine.rows(filter: Filter) = runBlocking { prepare(filter).run() }.rows.toList()

    private fun nanos(text: String) = LocalDateTime.parse(text).toEpochSecond(ZoneOffset.UTC) * 1_000_000_000

    @Test
    fun `conditions on each kind of field`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(2, 3, 5), engine.rows(LevelCondition.atLeast("Level", Level.WARN)))
            assertEquals(listOf(1, 4), engine.rows(TextCondition("Thread", TextOp.EQUALS, "main")))
            assertEquals(listOf(3, 5), engine.rows(TextCondition("Logger", TextOp.STARTS_WITH, "app.H")))
            assertEquals(listOf(2), engine.rows(TimeCondition("Timestamp", nanos("2026-10-01T08:00:01"), nanos("2026-10-01T08:00:02") - 1)))
            assertEquals(listOf(1, 2, 3, 4, 5), engine.rows(PresenceCondition("Timestamp")))
            assertEquals(listOf(0), engine.rows(PresenceCondition("Thread", present = false)))
            assertEquals(listOf(3), engine.rows(TextCondition("Timestamp", TextOp.CONTAINS, ":02.")))
        }
    }

    @Test
    fun `message text includes continuation lines`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(2), engine.rows(TextCondition("Message", TextOp.CONTAINS, "SQLException")))
            assertEquals(listOf(5), engine.rows(TextCondition("Message", TextOp.CONTAINS, "billing", ignoreCase = true)))
            assertEquals(listOf(), engine.rows(TextCondition("Message", TextOp.CONTAINS, "billing")))
            assertEquals(listOf(1), engine.rows(TextCondition("Message", TextOp.CONTAINS, "ŽLUŤOUČKÝ", ignoreCase = true)))
            assertEquals(listOf(3, 5), engine.rows(TextCondition("Message", TextOp.MATCHES, "(?i)slow|timeout")))
            assertEquals(listOf(0), engine.rows(TextCondition("Message", TextOp.EQUALS, "banner before any record")))
        }
    }

    @Test
    fun `combinations and absent values`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            val errors = LevelCondition("Level", setOf(Level.ERROR))
            val http = TextCondition("Logger", TextOp.EQUALS, "app.Http")
            assertEquals(listOf(5), engine.rows(Filter.And(listOf(errors, http))))
            assertEquals(listOf(2, 3, 5), engine.rows(Filter.Or(listOf(errors, http))))
            assertEquals(listOf(0, 1, 3, 4), engine.rows(Filter.Not(errors)))
            // The banner has no thread, so it is "not main".
            assertEquals(listOf(0, 2, 3, 5), engine.rows(TextCondition("Thread", TextOp.NOT_EQUALS, "main")))
            assertEquals(listOf(), engine.rows(TextCondition("Nope", TextOp.EQUALS, "x")))
            assertEquals(listOf(0, 1, 2, 3, 4, 5), engine.rows(TextCondition("Nope", TextOp.NOT_EQUALS, "x")))
            assertEquals(listOf(0, 1, 2, 3, 4, 5), engine.rows(Filter.And(emptyList())))
            assertEquals(listOf(), engine.rows(Filter.Or(emptyList())))
        }
    }

    @Test
    fun `invalid regex is reported when preparing`() {
        parse(sample).use { log ->
            assertFailsWith<IllegalArgumentException> { FilterEngine(log).prepare(TextCondition("Message", TextOp.MATCHES, "(")) }
        }
    }

    @Test
    fun `raw logs filter on the line`() {
        val file = Files.writeString(dir.resolve("raw.log"), "alpha\r\nbeta\ngamma alpha\nlast")
        runBlocking { LineIndex.build(file) }.use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(0, 2), engine.rows(TextCondition("Line", TextOp.CONTAINS, "alpha")))
            assertEquals(listOf(0), engine.rows(TextCondition("Line", TextOp.EQUALS, "alpha")))
            assertEquals(listOf(3), engine.rows(TextCondition("Line", TextOp.EQUALS, "last")))
            assertEquals(listOf(), engine.rows(LevelCondition.atLeast("Level", Level.INFO)))
        }
    }

    /** A larger random log, compared against a straightforward record-by-record evaluation. */
    @Test
    fun `parallel cached and incremental evaluation agree with a direct one`() {
        val random = Random(3)
        val levels = listOf("TRACE", "DEBUG", "INFO ", "WARN ", "ERROR")
        fun records(from: Int, count: Int) = buildString {
            for (i in from..<from + count) {
                append("2026-10-01 %02d:%02d:%02d.000 [t-%d] %s log.L%d - message %d %s\n"
                    .format(i / 3600 % 24, i / 60 % 60, i % 60, random.nextInt(9), levels[random.nextInt(5)], random.nextInt(4), i,
                        if (random.nextInt(10) == 0) "needle" else "hay"))
                if (random.nextInt(20) == 0) append("java.lang.Exception: deep needle\n\tat x.Y(Y.java:1)\n")
            }
        }
        val file = Files.writeString(dir.resolve("random.log"), records(0, 20_000))
        val filters = listOf(
            Filter.And(listOf(LevelCondition.atLeast("Level", Level.WARN), TextCondition("Message", TextOp.CONTAINS, "needle"))),
            Filter.Or(listOf(TextCondition("Thread", TextOp.EQUALS, "t-3"), Filter.Not(TextCondition("Logger", TextOp.EQUALS, "log.L1")))),
            Filter.And(listOf(TextCondition("Message", TextOp.CONTAINS, "NEEDLE", ignoreCase = true), TimeCondition("Timestamp", from = nanos("2026-10-01T02:00:00")))),
        )
        fun expected(log: LogData, filter: Filter): List<Int> = (0..<log.recordCount).filter { matches(log as ParsedLog, it, filter) }

        runBlocking { ParsedLog.build(file, format, IndexProgress(), IndexOptions(parallelism = 6, minRangeSize = 1)) }.use { log ->
            val engine = FilterEngine(log)
            for (filter in filters) assertEquals(expected(log, filter), engine.rows(filter))
            // Cached leaves are reused, and only appended records are computed afresh.
            Files.writeString(file, records(20_000, 3_000), StandardOpenOption.APPEND)
            log.append(log.readAppended(Files.size(file)))
            for (filter in filters) {
                val incremental = engine.rows(filter)
                assertEquals(expected(log, filter), incremental)
                assertContentEquals(FilterEngine(log).rows(filter), incremental)
            }
        }
    }

    // Record-by-record reference, using what the table shows plus the full record text for messages.
    private fun matches(log: ParsedLog, record: Int, filter: Filter): Boolean = when (filter) {
        is Filter.And -> filter.parts.all { matches(log, record, it) }
        is Filter.Or -> filter.parts.any { matches(log, record, it) }
        is Filter.Not -> !matches(log, record, filter.part)
        is LevelCondition -> log.level(record) in filter.levels
        is TimeCondition -> {
            val text = log.rowValues(record, 1000)[0]
            text.isNotEmpty() && nanos(text.replace(' ', 'T')) >= (filter.from ?: Long.MIN_VALUE)
        }
        is TextCondition -> {
            val field = log.schema.fields.indexOfFirst { it.name == filter.field }
            val text = if (filter.field == "Message") {
                log.recordText(record, 100_000).substringAfter(" - ")
            } else {
                log.rowValues(record, 1000)[field]
            }
            when (filter.op) {
                TextOp.EQUALS -> text.equals(filter.value, filter.ignoreCase)
                TextOp.CONTAINS -> text.contains(filter.value, filter.ignoreCase)
                else -> error("unused")
            }
        }
        is PresenceCondition -> error("unused")
    }
}
