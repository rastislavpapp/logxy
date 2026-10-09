package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.parse.FormatMismatchException
import io.github.rastislavpapp.logxy.core.parse.PatternFormat
import io.github.rastislavpapp.logxy.core.parse.RegexFormat
import io.github.rastislavpapp.logxy.core.parse.RecordFormat
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

    private val format = PatternFormat("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n", ZoneOffset.UTC)

    private fun parse(content: String, format: RecordFormat = this.format, options: IndexOptions = IndexOptions()): ParsedLog {
        val file = Files.writeString(dir.resolve("test-${content.hashCode()}-${options.hashCode()}.log"), content)
        return runBlocking { ParsedLog.build(file, format, IndexProgress(), options) }
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
    fun `long values are cut`() {
        parse("2026-10-01 08:00:00.001 [main] INFO  a.B - ${"x".repeat(50)}\n").use { log ->
            assertEquals("x".repeat(10) + "…", log.rowValues(0, 10)[4])
        }
    }

    @Test
    fun `parallel parsing matches a single range`() {
        val content = buildString {
            repeat(300) { i ->
                append("2026-10-01 08:00:%02d.%03d [t-${i % 7}] ${if (i % 5 == 0) "ERROR" else "INFO "} log.L${i % 3} - message $i\n".format(i % 60, i))
                if (i % 5 == 0) append("java.lang.RuntimeException: $i\n\tat x.Y.z(Y.java:$i)\n")
            }
        }
        val single = parse(content, options = IndexOptions(parallelism = 1))
        val parallel = parse(content, options = IndexOptions(parallelism = 8, minRangeSize = 1))
        single.use { parallel.use {
            assertEquals(300, single.recordCount)
            assertEquals(single.rows(), parallel.rows())
            assertEquals(List(300) { single.recordText(it, 10_000) }, List(300) { parallel.recordText(it, 10_000) })
            assertFalse(parallel.timestampsSorted)
        } }
    }

    @Test
    fun `appending continues records and reparses an incomplete last line`() {
        val file = Files.writeString(dir.resolve("live.log"), "2026-10-01 08:00:00.001 [main] INFO  a.B - one\n")
        runBlocking { ParsedLog.build(file, format) }.use { log ->
            fun write(text: String) {
                Files.writeString(file, text, StandardOpenOption.APPEND)
                log.append(log.readAppended(Files.size(file)))
            }
            write("java.lang.Exception: x\n")
            assertEquals(1, log.recordCount)
            assertEquals("2026-10-01 08:00:00.001 [main] INFO  a.B - one\njava.lang.Exception: x", log.recordText(0, 1000))

            // Incomplete, it does not match the layout yet, so it continues the previous record.
            write("2026-10-01 08:00:00.002 [main] WA")
            assertEquals(1, log.recordCount)
            write("RN  a.B - two\n")
            assertEquals(2, log.recordCount)
            assertEquals(listOf("2026-10-01 08:00:00.002", "main", "WARN", "a.B", "two"), log.rowValues(1, 1000).toList())
            assertEquals(Level.WARN, log.level(1))
        }
    }

    @Test
    fun `appending to an empty file`() {
        val file = Files.writeString(dir.resolve("empty.log"), "")
        runBlocking { ParsedLog.build(file, format) }.use { log ->
            assertEquals(0, log.recordCount)
            Files.writeString(file, "2026-10-01 08:00:00.001 [main] INFO  a.B - one\n", StandardOpenOption.APPEND)
            log.append(log.readAppended(Files.size(file)))
            assertEquals("one", log.rowValues(0, 100)[4])
        }
    }

    private fun assertMismatch(content: String) {
        val file = Files.writeString(dir.resolve("mismatch-${content.hashCode()}.log"), content)
        assertFailsWith<FormatMismatchException> { runBlocking { ParsedLog.build(file, format) } }
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
        val content = "java.lang.IllegalStateException: boom\n\tat x.Y.z(Y.java:1)\nCaused by: java.io.IOException\n\n" +
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
    fun `regex format with named groups`() {
        val regex = RegexFormat("""(?<timestamp>\S+ \S+) (?<level>\w+) \[(?<thread>[^]]*)] (?<message>.*)""", zone = ZoneOffset.UTC)
        parse("2026-10-01 08:00:00.001 INFO [main] hello world\n\tmore\n", regex).use { log ->
            assertEquals(listOf("2026-10-01 08:00:00.001", "INFO", "main", "hello world"), log.rows()[0])
            assertEquals("2026-10-01 08:00:00.001 INFO [main] hello world\n\tmore", log.recordText(0, 1000))
        }
    }
}
