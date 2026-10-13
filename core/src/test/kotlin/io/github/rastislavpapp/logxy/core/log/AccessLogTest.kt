package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.NumberCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.parse.CompiledAccessLog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessLogTest {

    @TempDir
    lateinit var dir: Path

    private fun parse(content: String, options: IndexOptions = IndexOptions()): ParsedLog {
        val file = Files.writeString(dir.resolve("access-${content.hashCode()}-${options.hashCode()}.log"), content)
        return runBlocking { ParsedLog.build(file, CompiledAccessLog(ZoneOffset.UTC), IndexProgress(), options) }
    }

    private fun ParsedLog.column(name: String) = schema.fields.indexOfFirst { it.name == name }.let { field ->
        List(recordCount) { rowValues(it, 1000)[field] }
    }

    private fun ParsedLog.matching(condition: Filter) = runBlocking { FilterEngine(this@matching).prepare(condition).run() }.rows.toList()

    private val sample = """
        203.0.113.7 - alice [13/Oct/2026:10:00:00 +0200] "GET /api/users?id=3 HTTP/1.1" 200 512 "https://example.com/" "Mozilla/5.0 (X11; \"Linux\")"
        198.51.100.2 - - [13/Oct/2026:08:00:01 +0000] "POST /login HTTP/1.1" 302 -
        192.0.2.1 - - [12/Oct/2026:22:00:02 -1000] "-" 400 0 "-" "-"
        192.0.2.9 - - [13/Oct/2026:08:00:03 +0000] "\x16\x03\x01\x02" 400 157 "-" "-"
    """.trimIndent() + "\n"

    @Test
    fun `parses common and combined lines`() {
        for (options in listOf(IndexOptions(parallelism = 1), IndexOptions(parallelism = 4, minRangeSize = 1))) {
            parse(sample, options).use { log ->
                assertEquals(4, log.recordCount)
                // No record has an ident.
                assertEquals(
                    listOf("Host", "User", "Timestamp", "Method", "Request", "Status", "Bytes", "Referer", "User agent"),
                    log.schema.fields.filter { !it.hidden }.map { it.name },
                )
                assertEquals(listOf("203.0.113.7", "198.51.100.2", "192.0.2.1", "192.0.2.9"), log.column("Host"))
                assertEquals(listOf("", "", "", ""), log.column("Ident"))
                assertEquals(listOf("alice", "", "", ""), log.column("User"))
                assertEquals(
                    listOf("2026-10-13 08:00:00", "2026-10-13 08:00:01", "2026-10-13 08:00:02", "2026-10-13 08:00:03"),
                    log.column("Timestamp"),
                )
                assertEquals(listOf("GET", "POST", "", ""), log.column("Method"))
                assertEquals(
                    listOf("GET /api/users?id=3 HTTP/1.1", "POST /login HTTP/1.1", "-", """\x16\x03\x01\x02"""),
                    log.column("Request"),
                )
                assertEquals(listOf("200", "302", "400", "400"), log.column("Status"))
                assertEquals(listOf("512", "", "0", "157"), log.column("Bytes"))
                assertEquals(listOf("https://example.com/", "", "", ""), log.column("Referer"))
                assertEquals(listOf("""Mozilla/5.0 (X11; \"Linux\")""", "", "", ""), log.column("User agent"))
                assertEquals(listOf(null, null, null, null), List(4) { log.level(it) })
            }
        }
    }

    @Test
    fun `lines cut short start no record`() {
        val text = sample.lines().first()
        val line = text.toByteArray()
        val matcher = CompiledAccessLog().newMatcher()
        // Only cuts within or right after the size (a common line) and the whole line are records; others must be
        // rejected, not fail.
        val accepted = (0..line.size).filter { matcher.match(line, 0, it) }
        val size = text.indexOf(" 512 ") + 1
        assertEquals(listOf(size + 1, size + 2, size + 3, size + 4, line.size), accepted)
    }

    @Test
    fun `trailing spaces end a line`() {
        val matcher = CompiledAccessLog().newMatcher()
        for (line in listOf("""192.0.2.1 - - [13/Oct/2026:08:00:00 +0000] "GET / HTTP/1.1" 200 5 """, """192.0.2.1 - - [13/Oct/2026:08:00:00 +0000] "GET / HTTP/1.1" 200 5 "-" "-"  """)) {
            val bytes = line.toByteArray()
            assertEquals(true, matcher.match(bytes, 0, bytes.size), line)
        }
    }

    @Test
    fun `filters on method and status`() {
        parse(sample).use { log ->
            assertEquals(listOf(1), log.matching(TextCondition("Method", TextOp.EQUALS, "POST")))
            assertEquals(listOf(2, 3), log.matching(NumberCondition("Status", from = 400.0)))
        }
    }

    @Test
    fun `many distinct clients and agents are stored as spans`() {
        val lines = (0..<40_000).joinToString("\n", postfix = "\n") { i ->
            """10.${i / 65536}.${i / 256 % 256}.${i % 256} - - [13/Oct/2026:08:00:00 +0000] "GET /$i HTTP/1.1" 200 1 "https://example.com/$i" "agent/$i""""
        }
        parse(lines).use { log ->
            assertEquals(40_000, log.recordCount)
            val types = log.schema.fields.associate { it.name to it.type }
            assertEquals(ColumnType.SPAN, types["Host"])
            assertEquals(ColumnType.SPAN, types["User agent"])
            assertEquals(ColumnType.DICT, types["Status"])
            assertEquals("agent/39999", log.column("User agent").last())
        }
    }
}
