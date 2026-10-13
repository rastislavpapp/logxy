package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.NumberCondition
import io.github.rastislavpapp.logxy.core.filter.PresenceCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.parse.CompiledJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonLogTest {

    @TempDir
    lateinit var dir: Path

    private val parser = CompiledJson(zone = ZoneOffset.UTC)

    private fun parse(content: String, options: IndexOptions = IndexOptions()): ParsedLog {
        val file = Files.writeString(dir.resolve("test-${content.hashCode()}-${options.hashCode()}.json"), content)
        return runBlocking { ParsedLog.build(file, parser, IndexProgress(), options) }
    }

    private fun ParsedLog.column(name: String) = schema.fields.indexOfFirst { it.name == name }.let { field ->
        List(recordCount) { rowValues(it, 1000)[field] }
    }

    private fun ParsedLog.matching(condition: Filter) = runBlocking { FilterEngine(this@matching).prepare(condition).run() }.rows.toList()

    private val sample = """
        Starting the application
        {"@timestamp":"2026-10-01T08:00:00.001Z","level":"INFO","logger_name":"com.example.App","thread_name":"main","message":"Started","tenant":"acme"}
        {"@timestamp":"2026-10-01T08:00:00.002Z","level":"ERROR","logger_name":"com.example.Db","thread_name":"pool \"1\"","message":"Failed:\nretrying","stack_trace":"java.lang.IllegalStateException: boom\n\tat com.example.Db.query(Db.java:10)","context":{"orderId":17,"items":[1,2]}}

        {"@timestamp":1790841601000,"level":"WARN","logger_name":"com.example.App","thread_name":"main","message":"Slow","tenant":"globex","port":8080}
    """.trimIndent() + "\n"

    @Test
    fun `parses a record per line, with other keys as columns`() {
        for (options in listOf(IndexOptions(parallelism = 1), IndexOptions(parallelism = 4, minRangeSize = 1))) {
            parse(sample, options).use { log ->
                assertEquals(4, log.recordCount)
                val visible = log.schema.fields.filter { !it.hidden }.map { it.name }
                assertEquals(listOf("Timestamp", "Level", "Thread", "Logger", "Message", "Exception", "context", "port", "tenant"), visible)
                assertEquals(listOf("", "2026-10-01 08:00:00.001", "2026-10-01 08:00:00.002", "2026-10-01 08:00:01.000"), log.column("Timestamp"))
                assertEquals(listOf("", "main", "pool \"1\"", "main"), log.column("Thread"))
                assertEquals(listOf("Starting the application", "Started", "Failed:", "Slow"), log.column("Message"))
                assertEquals(listOf("", "", "java.lang.IllegalStateException: boom", ""), log.column("Exception"))
                assertEquals(listOf("", "", """{"orderId":17,"items":[1,2]}""", ""), log.column("context"))
                assertEquals(listOf("", "acme", "", "globex"), log.column("tenant"))
                assertEquals(listOf("", "", "", "8080"), log.column("port"))
                assertEquals(listOf(null, Level.INFO, Level.ERROR, Level.WARN), List(4) { log.level(it) })
            }
        }
    }

    @Test
    fun `hides roles that no record has`() {
        parse("""{"time":"2026-10-01T08:00:00Z","msg":"hi","level":30}""" + "\n").use { log ->
            val visible = log.schema.fields.filter { !it.hidden }.map { it.name }
            assertEquals(listOf("Timestamp", "Level", "Message"), visible)
            assertEquals(Level.INFO, log.level(0))
        }
    }

    @Test
    fun `shows the whole record pretty-printed`() {
        parse(sample).use { log ->
            assertEquals("Starting the application", log.recordText(0, 1000))
            val detail = log.recordText(2, 10_000)
            assertTrue(detail.startsWith("{\n  \"@timestamp\": \"2026-10-01T08:00:00.002Z\",\n"), detail)
            assertTrue("\"stack_trace\": \"java.lang.IllegalStateException: boom\n\tat com.example.Db.query(Db.java:10)\"" in detail, detail)
        }
    }

    @Test
    fun `filters on decoded values`() {
        parse(sample).use { log ->
            assertEquals(listOf(2), log.matching(TextCondition("Message", TextOp.EQUALS, "*retrying")))
            assertEquals(listOf(2), log.matching(TextCondition("Thread", TextOp.EQUALS, "pool \"1\"")))
            assertEquals(listOf(3), log.matching(TextCondition("tenant", TextOp.EQUALS, "globex")))
            assertEquals(listOf(1), log.matching(TextCondition("tenant", TextOp.EQUALS, "ac*")))
            assertEquals(listOf(2), log.matching(TextCondition("context", TextOp.EQUALS, "*orderId*")))
            assertEquals(listOf(0), log.matching(TextCondition("Message", TextOp.EQUALS, "starting*", ignoreCase = true)))
            assertEquals(listOf(2), log.matching(PresenceCondition("Exception")))
            assertEquals(listOf(2, 3), log.matching(LevelCondition("Level", setOf(Level.WARN, Level.ERROR))))
        }
    }

    @Test
    fun `compares numbers, also in strings`() {
        val content = """
            {"message":"a","status":200,"duration":12.5}
            {"message":"b","status":"503","duration":2001}
            {"message":"c","status":"n/a","duration":-3e2}
            {"message":"d"}
        """.trimIndent() + "\n"
        parse(content).use { log ->
            assertEquals(listOf(1), log.matching(NumberCondition("status", from = 500.0)))
            assertEquals(listOf(0), log.matching(NumberCondition("status", to = 499.0)))
            assertEquals(listOf(0, 2, 3), log.matching(Filter.Not(NumberCondition("status", from = 500.0))))
            assertEquals(listOf(1), log.matching(NumberCondition("duration", from = Math.nextUp(2000.0))))
            assertEquals(listOf(0, 2), log.matching(NumberCondition("duration", to = 12.5)))
            assertEquals(listOf(), log.matching(NumberCondition("Message", from = 0.0)))
            assertEquals(listOf(), log.matching(NumberCondition("nope", from = 0.0)))
        }
    }

    @Test
    fun `tails appended lines`() {
        val file = Files.writeString(dir.resolve("tail.json"), """{"level":"INFO","message":"one"}""" + "\n" + """{"level":"INFO","mess""")
        runBlocking { ParsedLog.build(file, parser) }.use { log ->
            assertEquals(2, log.recordCount)
            Files.writeString(file, """age":"two"}""" + "\n" + """{"level":"WARN","message":"three"}""" + "\n", java.nio.file.StandardOpenOption.APPEND)
            log.append(log.readAppended(Files.size(file)))
            assertEquals(listOf("one", "two", "three"), log.column("Message"))
        }
    }
}
