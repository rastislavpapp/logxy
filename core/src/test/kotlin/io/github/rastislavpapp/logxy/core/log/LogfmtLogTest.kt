package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.parse.CompiledLogfmt
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class LogfmtLogTest {

    @TempDir
    lateinit var dir: Path

    private fun parse(content: String, options: IndexOptions = IndexOptions()): ParsedLog {
        val file = Files.writeString(dir.resolve("test-${content.hashCode()}-${options.hashCode()}.log"), content)
        return runBlocking { ParsedLog.build(file, CompiledLogfmt(zone = ZoneOffset.UTC), IndexProgress(), options) }
    }

    private fun ParsedLog.column(name: String) = schema.fields.indexOfFirst { it.name == name }.let { field ->
        List(recordCount) { rowValues(it, 1000)[field] }
    }

    private fun ParsedLog.matching(condition: Filter) = runBlocking { FilterEngine(this@matching).prepare(condition).run() }.rows.toList()

    private val sample = """
        ts=2026-10-01T08:00:00.001Z level=info msg="server started" port=8080 caller=main.go:12
        ts=2026-10-01T08:00:00.002Z level=error msg="request failed: \"timeout\"" path=/api/orders user= retry
        panic: runtime error: index out of range
        goroutine 1 [running]:
        	main.main()
        ts=2026-10-01T08:00:01Z lvl=warn msg=slow duration=1.5s user=alice
    """.trimIndent() + "\n"

    @Test
    fun `parses a record per line, continuation lines included, with other keys as columns`() {
        for (options in listOf(IndexOptions(parallelism = 1), IndexOptions(parallelism = 4, minRangeSize = 1))) {
            parse(sample, options).use { log ->
                assertEquals(3, log.recordCount)
                val visible = log.schema.fields.filter { !it.hidden }.map { it.name }
                assertEquals(listOf("Timestamp", "Level", "Logger", "Message", "duration", "path", "port", "retry", "user"), visible)
                assertEquals(listOf("2026-10-01 08:00:00.001", "2026-10-01 08:00:00.002", "2026-10-01 08:00:01.000"), log.column("Timestamp"))
                assertEquals(listOf("server started", "request failed: \"timeout\"", "slow"), log.column("Message"))
                assertEquals(listOf("main.go:12", "", ""), log.column("Logger"))
                assertEquals(listOf("8080", "", ""), log.column("port"))
                assertEquals(listOf("", "", "alice"), log.column("user"))
                assertEquals(listOf(Level.INFO, Level.ERROR, Level.WARN), List(3) { log.level(it) })
                assertEquals(true, log.recordText(1, 10_000).contains("goroutine 1 [running]:"))
            }
        }
    }

    @Test
    fun `filters on a key of its own`() {
        parse(sample).use { log ->
            assertEquals(listOf(1), log.matching(TextCondition("path", TextOp.EQUALS, "/api/orders")))
        }
    }
}
