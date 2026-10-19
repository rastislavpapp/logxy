package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.parse.CompiledJson
import io.github.rastislavpapp.logxy.core.parse.CompiledPattern
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.core.template.Templates
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
import kotlin.test.assertTrue

class FilterEngineTest {

    @TempDir
    lateinit var dir: Path

    private val parser = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n", ZoneOffset.UTC)

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
        return runBlocking { ParsedLog.build(file, parser, IndexProgress(), options) }
    }

    private fun FilterEngine.rows(filter: Filter) = runBlocking { prepare(filter).run() }.rows.toList()

    private fun message(op: TextOp, value: String, ignoreCase: Boolean = false) =
        TextCondition("Message", op, value, ignoreCase)

    private fun nanos(text: String) = LocalDateTime.parse(text).toEpochSecond(ZoneOffset.UTC) * 1_000_000_000

    @Test
    fun `searches the whole text of records, across fields and lines`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            fun find(search: TextSearch) = engine.rows(search.condition())
            assertEquals(listOf(2), find(TextSearch("sqlexception: BOOM")))
            assertEquals(emptyList(), find(TextSearch("sqlexception", matchCase = true)))
            assertEquals(listOf(2, 5), find(TextSearch("[worker-1] error")))
            assertEquals(listOf(1), find(TextSearch("ŽLUŤOUČKÝ")))
            assertEquals(listOf(0), find(TextSearch("banner")))
            // Wildcards are text like any other.
            assertEquals(emptyList(), find(TextSearch("Db.java:4*")))
            assertEquals(listOf(4), find(TextSearch("tick", wholeWords = true)))
            assertEquals(emptyList(), find(TextSearch("tic", wholeWords = true)))
            assertEquals(listOf(2), find(TextSearch("sqlexception", wholeWords = true)))
            assertEquals(emptyList(), find(TextSearch("SQLExcept", wholeWords = true)))
            assertEquals(emptyList(), find(TextSearch("timeout", wholeWords = true, matchCase = true)))
            assertEquals(listOf(3), find(TextSearch("worker-\\d] WARN", regex = true)))
            assertFailsWith<QueryException> { TextSearch("(", regex = true).condition() }
        }
    }

    @Test
    fun `a variable condition tests the word at its variable in records of its template`() {
        val log = parse("""
            2026-10-01 08:00:00.000 [main] INFO  app.Http - Request 17 done with 200 in 12 ms
            2026-10-01 08:00:01.000 [main] INFO  app.Http - Request 18 done with 404 in 7 ms
            2026-10-01 08:00:02.000 [main] INFO  app.Http - Request 19 done with 500 in 1500 ms
            2026-10-01 08:00:03.000 [main] INFO  app.Shop - Order 404 failed
            2026-10-01 08:00:04.000 [main] INFO  app.Http - Request 20 done with 404 in 30 ms
        """.trimIndent() + "\n")
        log.use {
            val engine = FilterEngine(log)
            fun variable(n: Int, test: Condition) =
                VariableCondition("Message", "Request {} done with {} in {} ms", n, test)
            fun status(op: TextOp, value: String) = engine.rows(variable(1, message(op, value, ignoreCase = true)))
            assertEquals(listOf(1, 4), status(TextOp.EQUALS, "404"))
            assertEquals(listOf(2), status(TextOp.EQUALS, "5*"))
            assertEquals(listOf(1, 4), status(TextOp.MATCHES, "^4"))
            // Only records of the template count, also for the negated ops.
            assertEquals(listOf(0, 2), status(TextOp.NOT_EQUALS, "404"))
            assertEquals(listOf(2), engine.rows(variable(2, NumberCondition("Message", from = 100.0))))
            assertEquals(listOf(0, 2, 3), engine.rows(Filter.Not(variable(1, message(TextOp.EQUALS, "404")))))
            val other = VariableCondition("Message", "Order {} failed", 0, message(TextOp.EQUALS, "404"))
            assertEquals(listOf(3), engine.rows(other))
        }
    }

    @Test
    fun `a variable condition reads JSON messages unescaped`() {
        val file = Files.writeString(dir.resolve("a.json"), """
            {"@timestamp":"2026-10-01T08:00:00Z","message":"User \"bob\" waited 120 ms"}
            {"@timestamp":"2026-10-01T08:00:01Z","message":"User \"bob\" waited 80 ms"}
        """.trimIndent() + "\n")
        runBlocking { ParsedLog.build(file, CompiledJson(zone = ZoneOffset.UTC), IndexProgress()) }.use { log ->
            val message = log.schema.fields.first { it.role == Role.MESSAGE }.name
            val test = TextCondition(message, TextOp.EQUALS, "80")
            val waited = VariableCondition(message, "User \"bob\" waited {} ms", 0, test)
            assertEquals(listOf(1), FilterEngine(log).rows(waited))
        }
    }

    @Test
    fun `conditions on each kind of field`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(2, 3, 5), engine.rows(LevelCondition.atLeast("Level", Level.WARN)))
            assertEquals(listOf(1, 4), engine.rows(TextCondition("Thread", TextOp.EQUALS, "main")))
            assertEquals(listOf(3, 5), engine.rows(TextCondition("Logger", TextOp.EQUALS, "app.H*")))
            val second = TimeCondition("Timestamp", nanos("2026-10-01T08:00:01"), nanos("2026-10-01T08:00:02") - 1)
            assertEquals(listOf(2), engine.rows(second))
            assertEquals(listOf(1, 2, 3, 4, 5), engine.rows(PresenceCondition("Timestamp")))
            assertEquals(listOf(0), engine.rows(PresenceCondition("Thread", present = false)))
            assertEquals(listOf(3), engine.rows(TextCondition("Timestamp", TextOp.EQUALS, "*:02.*")))
        }
    }

    @Test
    fun `message text includes continuation lines`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(2), engine.rows(message(TextOp.EQUALS, "*SQLException*")))
            assertEquals(listOf(5), engine.rows(message(TextOp.EQUALS, "*billing*", ignoreCase = true)))
            assertEquals(listOf(), engine.rows(message(TextOp.EQUALS, "*billing*")))
            assertEquals(listOf(1), engine.rows(message(TextOp.EQUALS, "*ŽLUŤOUČKÝ*", ignoreCase = true)))
            assertEquals(listOf(3, 5), engine.rows(message(TextOp.MATCHES, "(?i)slow|timeout")))
            assertEquals(listOf(0), engine.rows(message(TextOp.EQUALS, "banner before any record")))
        }
    }

    @Test
    fun `progress ends at one, also when conditions leave nothing to read or are cached`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            val message = TextCondition("Message", TextOp.EQUALS, "*Slow*")
            val filters = listOf(
                message,
                // No FATAL records: the message is never read.
                Filter.And(listOf(LevelCondition.atLeast("Level", Level.FATAL), message)),
                Filter.Or(listOf(message, TextCondition("Message", TextOp.EQUALS, "*Tick*"))),
                TextCondition("Thread", TextOp.EQUALS, "main"),
                message,
            )
            for (filter in filters) {
                val evaluation = engine.prepare(filter)
                assertEquals(0.0, evaluation.progress)
                runBlocking { evaluation.run() }
                assertEquals(1.0, evaluation.progress, "$filter")
            }
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
    fun `wildcards and regexes`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(2), engine.rows(message(TextOp.EQUALS, "query*boom*", ignoreCase = true)))
            assertEquals(listOf(1, 4), engine.rows(TextCondition("Logger", TextOp.EQUALS, "app.M??n")))
            assertEquals(listOf(2, 3, 5), engine.rows(TextCondition("Thread", TextOp.EQUALS, "worker-?")))
            assertEquals(listOf(0, 1, 4), engine.rows(TextCondition("Thread", TextOp.NOT_EQUALS, "worker-*")))
            assertEquals(listOf(4), engine.rows(message(TextOp.EQUALS, "*ick")))
            assertEquals(listOf(), engine.rows(message(TextOp.EQUALS, "*ICK")))
            assertEquals(listOf(1), engine.rows(message(TextOp.EQUALS, "*KůŇ", ignoreCase = true)))
            val neither = message(TextOp.NOT_MATCHES, "slow|timeout", ignoreCase = true)
            assertEquals(listOf(0, 1, 2, 4), engine.rows(neither))
            assertEquals(listOf(3), engine.rows(message(TextOp.MATCHES, "/orders$")))
            // Regexes whose fixed text a byte search looks for first.
            assertEquals(listOf(5), engine.rows(message(TextOp.MATCHES, "timeout calling \\w+", ignoreCase = true)))
            assertEquals(listOf(), engine.rows(message(TextOp.MATCHES, "timeout after \\d+ ms", ignoreCase = true)))
            assertEquals(listOf(0, 1, 2, 3, 4, 5), engine.rows(message(TextOp.NOT_MATCHES, "never \\d+ here")))
            assertEquals(listOf(5), engine.rows(message(TextOp.MATCHES, "calling (?-i)BILLING", ignoreCase = true)))
            assertEquals(listOf(), engine.rows(message(TextOp.MATCHES, "calling (?-i)billing", ignoreCase = true)))
        }
        val file = Files.writeString(dir.resolve("stars.log"), "a*b\naxb\n")
        runBlocking { LineIndex.build(file) }.use { log ->
            assertEquals(listOf(0), FilterEngine(log).rows(TextCondition("Line", TextOp.EQUALS, "a\\*b")))
            assertEquals(listOf(0, 1), FilterEngine(log).rows(TextCondition("Line", TextOp.EQUALS, "a*b")))
        }
    }

    @Test
    fun `numbers in dictionary, span and map fields`() {
        val mdc = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c %X - %m%n", ZoneOffset.UTC)
        val content = """
            2026-10-01 08:00:00.000 [7] INFO  app.Http {status=200, ms=15} - 12
            2026-10-01 08:00:01.000 [12] INFO  app.Http {status=503, ms=2400} - 1.5e3
            2026-10-01 08:00:02.000 [main] INFO  app.Http {status=, ms=x} - slow
        """.trimIndent() + "\n"
        val file = Files.writeString(dir.resolve("numbers.log"), content)
        runBlocking { ParsedLog.build(file, mdc, IndexProgress()) }.use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(1), engine.rows(NumberCondition("Thread", from = 10.0)))
            assertEquals(listOf(0, 1), engine.rows(NumberCondition("Thread", to = 12.0)))
            assertEquals(listOf(1), engine.rows(NumberCondition("Message", from = 100.0)))
            assertEquals(listOf(0, 1), engine.rows(NumberCondition("Message")))
            assertEquals(listOf(1), engine.rows(NumberCondition("MDC[status]", from = 500.0)))
            assertEquals(listOf(0), engine.rows(NumberCondition("MDC[ms]", to = 2000.0)))
            assertEquals(listOf(), engine.rows(NumberCondition("Timestamp", from = 0.0)))
        }
    }

    @Test
    fun `invalid regex is reported when preparing`() {
        parse(sample).use { log ->
            assertFailsWith<IllegalArgumentException> { FilterEngine(log).prepare(message(TextOp.MATCHES, "(")) }
        }
    }

    @Test
    fun `raw logs filter on the line`() {
        val file = Files.writeString(dir.resolve("raw.log"), "alpha\r\nbeta\ngamma alpha\nlast")
        runBlocking { LineIndex.build(file) }.use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(0, 2), engine.rows(TextCondition("Line", TextOp.EQUALS, "*alpha*")))
            assertEquals(listOf(0), engine.rows(TextCondition("Line", TextOp.EQUALS, "alpha")))
            assertEquals(listOf(3), engine.rows(TextCondition("Line", TextOp.EQUALS, "last")))
            assertEquals(listOf(), engine.rows(LevelCondition.atLeast("Level", Level.INFO)))
        }
    }

    @Test
    fun `a changed level mapping applies to cached level conditions`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            val warnOrWorse = LevelCondition.atLeast("Level", Level.WARN)
            assertEquals(listOf(2, 3, 5), engine.rows(warnOrWorse))
            log.levelMapping = LevelMapping(Level.BUILT_IN_NAMES + ("INFO" to Level.ERROR))
            assertEquals(listOf(1, 2, 3, 5), engine.rows(warnOrWorse))
        }
    }

    @Test
    fun `the cache stays within its budget and dropped conditions are computed again`() {
        parse(sample).use { log ->
            // Six records: one word of bits and one of coverage, 16 bytes per condition.
            val engine = FilterEngine(log, maxCacheBytes = 40)
            val threads = listOf("main", "worker-1", "worker-2", "nobody")
            val expected = listOf(listOf(1, 4), listOf(2, 5), listOf(3), listOf())
            repeat(2) {
                for ((thread, rows) in threads.zip(expected)) {
                    assertEquals(rows, engine.rows(TextCondition("Thread", TextOp.EQUALS, thread)))
                    assertTrue(engine.cachedConditions <= 2)
                }
            }
        }
    }

    @Test
    fun `evaluation from a record on matches the full result from there`() {
        val levels = listOf("DEBUG", "INFO ", "WARN ", "ERROR")
        val content = buildString {
            for (i in 0..<300) {
                val needle = if (i % 7 == 0) " needle" else ""
                append("2026-10-01 08:00:00.000 [t-${i % 5}] ${levels[i % 4]} app.L - message $i$needle\n")
            }
        }
        parse(content).use { log ->
            val filters = listOf(
                LevelCondition.atLeast("Level", Level.WARN),
                message(TextOp.EQUALS, "*needle*"),
                Filter.Or(listOf(TextCondition("Thread", TextOp.EQUALS, "t-1"), message(TextOp.EQUALS, "*needle*"))),
                Filter.Not(TextCondition("Thread", TextOp.EQUALS, "t-2")),
            )
            val cached = FilterEngine(log)
            for (filter in filters) {
                val full = cached.rows(filter)
                for (from in listOf(0, 1, 63, 64, 65, 128, 299, 300, 400)) {
                    val expected = full.filter { it >= from }
                    for (engine in listOf(cached, FilterEngine(log))) {
                        val result = runBlocking { engine.prepare(filter, from).run() }
                        assertEquals(expected, result.rows.toList(), "$filter from $from")
                        assertEquals(minOf(from, 300), result.from)
                    }
                }
            }
        }
    }

    private fun grow(log: LogData, file: Path, text: String) {
        Files.writeString(file, text, StandardOpenOption.APPEND)
        log.append(log.readAppended(Files.size(file)))
    }

    @Test
    fun `cached results follow the last record as it grows`() {
        val file =
            Files.writeString(dir.resolve("grow.log"), "2026-10-01 08:00:01.000 [w] ERROR app.Db - Query failed\n")
        runBlocking { ParsedLog.build(file, parser) }.use { log ->
            val engine = FilterEngine(log)
            val npe = TextCondition("Message", TextOp.EQUALS, "*NullPointer*", ignoreCase = true)
            val endsFailed = TextCondition("Message", TextOp.EQUALS, "*failed")
            assertEquals(listOf(), engine.rows(npe))
            assertEquals(listOf(0), engine.rows(endsFailed))
            grow(log, file, "java.lang.NullPointerException: x\n\tat a.B(B.java:1)\n")
            assertEquals(listOf(0), engine.rows(npe))
            assertEquals(listOf(), engine.rows(endsFailed))
        }
    }

    @Test
    fun `cached results follow a raw line being completed`() {
        val file = Files.writeString(dir.resolve("partial.log"), "hello wor")
        runBlocking { LineIndex.build(file) }.use { log ->
            val engine = FilterEngine(log)
            val world = TextCondition("Line", TextOp.EQUALS, "*world*", ignoreCase = true)
            assertEquals(listOf(), engine.rows(world))
            grow(log, file, "ld\n")
            assertEquals(listOf(0), engine.rows(world))
        }
    }

    /** A larger random log, compared against a straightforward record-by-record evaluation. */
    @Test
    fun `parallel cached and incremental evaluation agree with a direct one`() {
        val random = Random(3)
        val levels = listOf("TRACE", "DEBUG", "INFO ", "WARN ", "ERROR")
        fun records(from: Int, count: Int) = buildString {
            for (i in from..<from + count) {
                val time = "%02d:%02d:%02d".format(i / 3600 % 24, i / 60 % 60, i % 60)
                val thread = random.nextInt(9)
                val level = levels[random.nextInt(5)]
                val logger = random.nextInt(4)
                val word = if (random.nextInt(10) == 0) "needle" else "hay"
                append("2026-10-01 $time.000 [t-$thread] $level log.L$logger - message $i $word\n")
                if (random.nextInt(20) == 0) append("java.lang.Exception: deep needle\n\tat x.Y(Y.java:1)\n")
            }
        }
        val file = Files.writeString(dir.resolve("random.log"), records(0, 20_000))
        val filters = listOf(
            Filter.And(listOf(LevelCondition.atLeast("Level", Level.WARN), message(TextOp.EQUALS, "*needle*"))),
            Filter.Or(
                listOf(
                    TextCondition("Thread", TextOp.EQUALS, "t-3"),
                    Filter.Not(TextCondition("Logger", TextOp.EQUALS, "log.L1")),
                ),
            ),
            Filter.And(
                listOf(
                    message(TextOp.EQUALS, "*NEEDLE*", ignoreCase = true),
                    TimeCondition("Timestamp", from = nanos("2026-10-01T02:00:00")),
                ),
            ),
            Filter.And(
                listOf(
                    LevelCondition.atLeast("Level", Level.INFO),
                    Filter.Not(TemplateCondition("Message", setOf("message {} hay"))),
                ),
            ),
            VariableCondition("Message", "message {} needle", 0, NumberCondition("Message", from = 5_000.0)),
        )
        fun expected(log: LogData, filter: Filter): List<Int> =
            (0..<log.recordCount).filter { matches(log as ParsedLog, it, filter) }

        val options = IndexOptions(parallelism = 6, minRangeSize = 1)
        runBlocking { ParsedLog.build(file, parser, IndexProgress(), options) }.use { log ->
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
        is NumberCondition, is RecordTextCondition -> error("unused")
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
                TextOp.EQUALS -> if (filter.value.startsWith("*") && filter.value.endsWith("*")) {
                    text.contains(filter.value.trim('*'), filter.ignoreCase)
                } else {
                    text.equals(filter.value, filter.ignoreCase)
                }
                else -> error("unused")
            }
        }
        is TemplateCondition -> {
            val field = log.schema.fields.indexOfFirst { it.name == filter.field }
            Templates.mask(log.rowValues(record, 1000)[field]) in filter.templates
        }
        is VariableCondition -> {
            val field = log.schema.fields.indexOfFirst { it.name == filter.field }
            val message = log.rowValues(record, 1000)[field]
            val word = Templates.variableWord(message, filter.variable)
            val test = filter.test as NumberCondition
            Templates.mask(message) == filter.template && word != null && test.matches(word.toDouble())
        }
        is PresenceCondition -> error("unused")
    }
}
