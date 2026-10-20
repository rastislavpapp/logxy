package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.PresenceCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.parse.CompiledParser
import io.github.rastislavpapp.logxy.core.parse.CompiledPattern
import io.github.rastislavpapp.logxy.core.parse.CompiledRegex
import io.github.rastislavpapp.logxy.core.parse.MdcMap
import io.github.rastislavpapp.logxy.core.parse.ParserMismatchException
import io.github.rastislavpapp.logxy.core.query.QueryCompiler
import io.github.rastislavpapp.logxy.core.query.QueryParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParsedLogTest {

    @TempDir
    lateinit var dir: Path

    private val parser = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n", ZoneOffset.UTC)

    private fun parse(
        content: String,
        parser: CompiledParser = this.parser,
        options: IndexOptions = IndexOptions(),
    ): ParsedLog {
        val file = Files.writeString(dir.resolve("test-${content.hashCode()}-${options.hashCode()}.log"), content)
        return runBlocking { ParsedLog.build(file, parser, IndexProgress(), options) }
    }

    private fun ParsedLog.rows() = List(recordCount) { rowValues(it, 1000).toList() }

    private val sample = """
        banner line before any record
        2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started
        2026-10-01 08:00:00.002 [pool-1 thread] ERROR com.example.Db - Failed - retrying
        java.lang.IllegalStateException: boom
        	at com.example.Db.query(Db.java:10)
        2026-10-01 08:00:01.000 [main] WARN  com.example.App - Slow
    """.trimIndent() + "\n"

    @Test
    fun `parses records with continuation lines`() {
        parse(sample).use { log ->
            assertEquals(4, log.recordCount)
            assertEquals(listOf("", "", "", "", "banner line before any record"), log.rows()[0])
            assertEquals(listOf("2026-10-01 08:00:00.001", "main", "INFO", "com.example.App", "Started"), log.rows()[1])
            assertEquals(
                listOf("2026-10-01 08:00:00.002", "pool-1 thread", "ERROR", "com.example.Db", "Failed - retrying"),
                log.rows()[2],
            )
            assertEquals(
                "2026-10-01 08:00:00.002 [pool-1 thread] ERROR com.example.Db - Failed - retrying\n" +
                    "java.lang.IllegalStateException: boom\n\tat com.example.Db.query(Db.java:10)",
                log.recordText(2, 10_000),
            )
            assertEquals(listOf(null, Level.INFO, Level.ERROR, Level.WARN), List(4) { log.level(it) })
            assertTrue(log.timestampsSorted)
        }
    }

    @Test
    fun `source text is the record as written, line ends included`() {
        val first = "2026-10-01 08:00:00.001 [main] ERROR a.B - Failed\r\n" +
            "java.lang.IllegalStateException: boom\r\n\tat a.B.run(B.java:1)\r\n"
        val last = "2026-10-01 08:00:00.002 [main] INFO  a.B - {\"json\": 1}"
        parse(first + last).use { log ->
            assertEquals(first, log.sourceText(0))
            assertEquals(last, log.sourceText(1))
        }
    }

    @Test
    fun `field text is the whole value`() {
        val long = "x".repeat(5000)
        val content = "2026-10-01 08:00:00.001 [main] ERROR a.B - Failed $long\r\n" +
            "java.lang.IllegalStateException: boom\n\tat a.B.run(B.java:1)\n"
        parse(content).use { log ->
            val fields = log.schema.fields.map { it.name }
            assertEquals("main", log.fieldText(0, fields.indexOf("Thread")))
            assertEquals("ERROR", log.fieldText(0, fields.indexOf("Level")))
            assertEquals(
                "Failed $long\r\njava.lang.IllegalStateException: boom\n\tat a.B.run(B.java:1)",
                log.fieldText(0, fields.indexOf("Message")),
            )
        }
    }

    @Test
    fun `a row tells how many lines its record has after the first`() {
        parse(sample).use { log ->
            assertEquals(listOf(0, 0, 2, 0), List(4) { log.row(it, 1000, 10_000).moreLines })
            assertEquals("Failed - retrying", log.row(2, 1000, 10_000).values[4])
        }
    }

    @Test
    fun `lines are counted in the bytes asked for only`() {
        val first = "2026-10-01 08:00:00.001 [main] ERROR a.B - Failed\r\n"
        parse(first + "at x\r\n".repeat(100)).use { log ->
            assertEquals(100, log.row(0, 1000, 10_000).moreLines)
            // Ten further lines of 6 bytes, the last without its \n.
            assertEquals(10, log.row(0, 1000, first.length + 6 * 10 - 1).moreLines)
        }
    }

    @Test
    fun `later lines are the record after its first line, as written`() {
        parse(sample).use { log ->
            assertEquals(
                "java.lang.IllegalStateException: boom\n\tat com.example.Db.query(Db.java:10)",
                log.laterLines(2, 10_000, "…"),
            )
            assertEquals("", log.laterLines(1, 10_000, "…"))
        }
    }

    @Test
    fun `later lines are cut with the mark`() {
        val first = "2026-10-01 08:00:00.001 [main] ERROR a.B - Failed\n"
        parse(first + "abcdefghij\nklm\n").use { log ->
            assertEquals("abcde (cut)", log.laterLines(0, first.length + 5, " (cut)"))
            assertEquals("abcdefghij\nklm", log.laterLines(0, first.length + 14, " (cut)"))
        }
    }

    @Test
    fun `long values are cut`() {
        parse("2026-10-01 08:00:00.001 [main] INFO  a.B - ${"x".repeat(50)}\n").use { log ->
            assertEquals("x".repeat(10) + "…", log.rowValues(0, 10)[4])
        }
    }

    @Test
    fun `parallel parsing matches a single range`() {
        val content = buildString {
            repeat(300) { i ->
                val level = if (i % 5 == 0) "ERROR" else "INFO "
                append("2026-10-01 08:00:%02d.%03d [t-${i % 7}] $level log.L${i % 3} - message $i\n".format(i % 60, i))
                if (i % 5 == 0) append("java.lang.RuntimeException: $i\n\tat x.Y.z(Y.java:$i)\n")
            }
        }
        val single = parse(content, options = IndexOptions(parallelism = 1))
        val parallel = parse(content, options = IndexOptions(parallelism = 8, minRangeSize = 1))
        single.use {
            parallel.use {
                assertEquals(300, single.recordCount)
                assertEquals(single.rows(), parallel.rows())
                assertEquals(List(300) { single.recordText(it, 10_000) }, List(300) { parallel.recordText(it, 10_000) })
                assertFalse(parallel.timestampsSorted)
            }
        }
    }

    @Test
    fun `appending continues records and reparses an incomplete last line`() {
        val file = Files.writeString(dir.resolve("live.log"), "2026-10-01 08:00:00.001 [main] INFO  a.B - one\n")
        runBlocking { ParsedLog.build(file, parser) }.use { log ->
            fun write(text: String) {
                Files.writeString(file, text, StandardOpenOption.APPEND)
                log.append(log.readAppended(Files.size(file)))
            }
            write("java.lang.Exception: x\n")
            assertEquals(1, log.recordCount)
            assertEquals(
                "2026-10-01 08:00:00.001 [main] INFO  a.B - one\njava.lang.Exception: x",
                log.recordText(0, 1000),
            )

            // Incomplete, it does not match the layout yet, so it continues the previous record.
            write("2026-10-01 08:00:00.002 [main] WA")
            assertEquals(1, log.recordCount)
            write("RN  a.B - two\n")
            assertEquals(2, log.recordCount)
            val values = log.rowValues(1, 1000).toList()
            assertEquals(listOf("2026-10-01 08:00:00.002", "main", "WARN", "a.B", "two"), values)
            assertEquals(Level.WARN, log.level(1))
        }
    }

    @Test
    fun `appending to an empty file`() {
        val file = Files.writeString(dir.resolve("empty.log"), "")
        runBlocking { ParsedLog.build(file, parser) }.use { log ->
            assertEquals(0, log.recordCount)
            Files.writeString(file, "2026-10-01 08:00:00.001 [main] INFO  a.B - one\n", StandardOpenOption.APPEND)
            log.append(log.readAppended(Files.size(file)))
            assertEquals("one", log.rowValues(0, 100)[4])
        }
    }

    private fun assertMismatch(content: String) {
        val file = Files.writeString(dir.resolve("mismatch-${content.hashCode()}.log"), content)
        assertFailsWith<ParserMismatchException> { runBlocking { ParsedLog.build(file, parser) } }
    }

    @Test
    fun `a file whose first entries do not match is refused`() {
        assertMismatch((1..50).joinToString("\n") { "12:00:0$it.000 INFO some other layout $it" })
        assertMismatch("only\nthree\nlines\n")
        assertMismatch("\tat x.Y.z(Y.java:1)\n\tat x.Y.z(Y.java:2)\n")
    }

    @Test
    fun `nine non-matching entries are tolerated`() {
        val content = (1..9).joinToString("") { "garbage $it\n" } + "2026-10-01 08:00:00.001 [main] INFO  a.B - one\n"
        // The leading lines form one message-only record.
        parse(content).use { assertEquals(2, it.recordCount) }
    }

    @Test
    fun `a file starting inside a stack trace is accepted`() {
        val content = "java.lang.IllegalStateException: boom\n\tat x.Y.z(Y.java:1)\n" +
            "Caused by: java.io.IOException\n\n" +
            "2026-10-01 08:00:00.001 [main] INFO  a.B - one\n"
        parse(content).use { assertEquals(2, it.recordCount) }
    }

    @Test
    fun `one record followed by a huge dump is accepted`() {
        val content = "2026-10-01 08:00:00.001 [main] INFO  a.B - payload:\n" +
            (1..20_000).joinToString("") { "<item id=\"$it\">value</item>\n" }
        parse(content).use { assertEquals(1, it.recordCount) }
    }

    @Test
    fun `an empty file is accepted`() {
        parse("").use { assertEquals(0, it.recordCount) }
    }

    @Test
    fun `regex parser with named groups`() {
        val pattern = """(?<timestamp>\S+ \S+) (?<level>\w+) \[(?<thread>[^]]*)] (?<message>.*)"""
        val regex = CompiledRegex(pattern, zone = ZoneOffset.UTC)
        parse("2026-10-01 08:00:00.001 INFO [main] hello world\n\tmore\n", regex).use { log ->
            assertEquals(listOf("2026-10-01 08:00:00.001", "INFO", "main", "hello world"), log.rows()[0])
            assertEquals("2026-10-01 08:00:00.001 INFO [main] hello world\n\tmore", log.recordText(0, 1000))
        }
    }

    @Test
    fun `a field with mostly distinct values is stored as a span`() {
        val content = (0..<30_000).joinToString("") { i ->
            "2026-10-01 08:00:00.000 [worker-${i % 7}] INFO  app.Logger$i - Message $i\n"
        }
        for (options in listOf(IndexOptions(parallelism = 1), IndexOptions(parallelism = 2, minRangeSize = 1))) {
            val progress = IndexProgress()
            val file = Files.writeString(dir.resolve("distinct-${options.parallelism}.log"), content)
            runBlocking { ParsedLog.build(file, parser, progress, options) }.use { log ->
                assertEquals(ColumnType.SPAN, log.schema.fields[3].type)
                assertEquals(ColumnType.DICT, log.schema.fields[1].type)
                assertEquals(30_000, log.recordCount)
                assertEquals(
                    listOf("2026-10-01 08:00:00.000", "worker-4", "INFO", "app.Logger12345", "Message 12345"),
                    log.rows()[12345],
                )
                val last = TextCondition("Logger", TextOp.EQUALS, "app.Logger2999?")
                val rows = runBlocking { FilterEngine(log).prepare(last).run() }.rows
                assertEquals((29_990..<30_000).toList(), rows.toList())
                assertEquals(Files.size(file), progress.bytesDone)
            }
        }
    }

    @Test
    fun `a named MDC field shows only its value, also when the whole map was written there`() {
        val mdc = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c %X{breadcrumbId} - %m%n", ZoneOffset.UTC)
        val content = """
            2026-10-01 08:00:00.001 [main] INFO  a.B {breadcrumbId=ID-1, tenant=acme} - map with the key
            2026-10-01 08:00:00.002 [main] INFO  a.B {tenant=acme, breadcrumbId=ID-2} - key not first
            2026-10-01 08:00:00.003 [main] INFO  a.B {tenant=acme} - map without the key
            2026-10-01 08:00:00.004 [main] INFO  a.B {} - empty map
            2026-10-01 08:00:00.005 [main] INFO  a.B ID-9 - just the value
            2026-10-01 08:00:00.006 [main] INFO  a.B  - no value
        """.trimIndent() + "\n"
        parse(content, mdc).use { log ->
            assertEquals(listOf("ID-1", "ID-2", "", "", "ID-9", ""), log.rows().map { it[4] })
            assertEquals(listOf("map with the key", "key not first"), log.rows().take(2).map { it[5] })
        }
    }

    private val mdc = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c %X - %m%n", ZoneOffset.UTC)

    @Test
    fun `an MDC map is split into a column per key`() {
        val content = """
            2026-10-01 08:00:00.001 [main] INFO  a.B {} - nothing
            2026-10-01 08:00:00.002 [http-1] INFO  a.B {breadcrumbId=ID-1, tenant=acme} - both
            2026-10-01 08:00:00.003 [http-2] INFO  a.B {tenant=globex} - one
            java.lang.IllegalStateException: boom
        """.trimIndent() + "\n"
        for (options in listOf(IndexOptions(parallelism = 1), IndexOptions(parallelism = 3, minRangeSize = 1))) {
            parse(content, mdc, options).use { log ->
                val visible = log.schema.fields.filter { !it.hidden }.map { it.name }
                assertEquals(
                    listOf("Timestamp", "Thread", "Level", "Logger", "Message", "MDC[breadcrumbId]", "MDC[tenant]"),
                    visible,
                )
                val names = log.schema.fields.map { it.name }
                val breadcrumb = names.indexOf("MDC[breadcrumbId]")
                val tenant = names.indexOf("MDC[tenant]")
                val rows = log.rows().map { row -> listOf(row[breadcrumb], row[tenant]) }
                assertEquals(listOf(listOf("", ""), listOf("ID-1", "acme"), listOf("", "globex")), rows)

                fun matching(condition: io.github.rastislavpapp.logxy.core.filter.Condition) =
                    runBlocking { FilterEngine(log).prepare(condition).run() }.rows.toList()
                assertEquals(listOf(1, 2), matching(TextCondition("MDC[tenant]", TextOp.EQUALS, "*")))
                assertEquals(listOf(2), matching(TextCondition("MDC[tenant]", TextOp.MATCHES, "^glo")))
                assertEquals(listOf(0, 2), matching(TextCondition("MDC[breadcrumbId]", TextOp.NOT_EQUALS, "ID-1")))
                assertEquals(listOf(1), matching(PresenceCondition("MDC[breadcrumbId]")))
                // Empty: the key missing from a map, or no map keys at all.
                val notEmpty = QueryCompiler(log.schema).compile(QueryParser.parse("""MDC[tenant] != """""))
                assertEquals(listOf(1, 2), runBlocking { FilterEngine(log).prepare(notEmpty).run() }.rows.toList())
            }
        }
    }

    @Test
    fun `an MDC field stays one column if a value is no map or there are too many keys`() {
        val notMap = "2026-10-01 08:00:00.001 [main] INFO  a.B {a=1} - map\n" +
            "2026-10-01 08:00:00.002 [main] INFO  a.B plain - not a map\n"
        parse(notMap, mdc).use { log ->
            assertEquals(listOf("MDC"), log.schema.fields.filter { it.name == "MDC" && !it.hidden }.map { it.name })
        }
        val manyKeys =
            (0..MdcMap.maxKeys).joinToString("") { "2026-10-01 08:00:00.001 [main] INFO  a.B {key$it=1} - x\n" }
        parse(manyKeys, mdc).use { log ->
            assertEquals(6, log.schema.fields.size)
            assertFalse(log.schema.fields.any { it.hidden })
        }
    }

    private fun ParsedLog.fieldTexts() =
        List(recordCount) { record -> List(schema.fields.size) { fieldText(record, it) } }

    @Test
    fun `a snapshot reads field texts like the log`() {
        val content = buildString {
            repeat(100) { i ->
                val level = if (i % 5 == 0) "ERROR" else "INFO "
                val time = "08:00:%02d.%03d".format(i % 60, i)
                append("2026-10-01 $time [t-${i % 7}] $level log.L${i % 3} {tenant=t$i} - message $i\n")
                if (i % 5 == 0) append("java.lang.RuntimeException: $i\r\n\tat x.Y.z(Y.java:$i)\n")
            }
        }
        for (parser in listOf(parser, mdc)) {
            parse(content, parser, IndexOptions(parallelism = 4, minRangeSize = 1)).use { log ->
                val snapshot = log.filterSnapshot()
                val snapshotTexts = List(log.recordCount) { record ->
                    List(log.schema.fields.size) { snapshot.fieldText(record, it) }
                }
                assertEquals(log.fieldTexts(), snapshotTexts)
            }
        }
    }

    @Test
    fun `a snapshot keeps reading its records while more are appended`() {
        val content = "2026-10-01 08:00:00.001 [main] INFO  a.B - one\n2026-10-01 08:00:00.002 [main] WARN  a.C - two\n"
        val file = Files.writeString(dir.resolve("live.log"), content)
        runBlocking { ParsedLog.build(file, parser) }.use { log ->
            val snapshot = log.filterSnapshot()
            val appended = "java.lang.Exception: x\n2026-10-01 08:00:00.003 [main] INFO  a.D - three\n"
            Files.writeString(file, appended, StandardOpenOption.APPEND)
            log.append(log.readAppended(Files.size(file)))
            assertEquals(3, log.recordCount)
            val message = log.schema.fields.indexOfFirst { it.name == "Message" }
            assertEquals(listOf("one", "two"), List(snapshot.count) { snapshot.fieldText(it, message) })
            assertEquals("a.C", snapshot.fieldText(1, log.schema.fields.indexOfFirst { it.name == "Logger" }))
        }
    }
}
