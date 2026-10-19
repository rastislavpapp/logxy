package io.github.rastislavpapp.logxy.core.template

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.TemplateCondition
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.CompiledJson
import io.github.rastislavpapp.logxy.core.parse.CompiledPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateIndexTest {

    @TempDir
    lateinit var dir: Path

    private val parser = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n", ZoneOffset.UTC)

    private val sample = """
        2026-10-01 08:00:00.000 [main] INFO  app.Main - Request from 10.0.0.1 took 5 ms
        2026-10-01 08:00:01.000 [w-1] ERROR app.Db - Query 17 failed
        java.sql.SQLException: boom 42
        2026-10-01 08:00:02.000 [w-2] WARN  app.Http - Request from 10.0.0.2 took 5012 ms
        2026-10-01 08:00:03.000 [main] INFO  app.Main - Request from 10.0.0.3 took 7 ms
        2026-10-01 08:00:04.000 [w-1] ERROR app.Db - Query 18 failed
        2026-10-01 08:00:05.000 [main] INFO  app.Main - Cache warmed
    """.trimIndent() + "\n"

    private val request = "Request from {} took {} ms"
    private val query = "Query {} failed"

    private fun parse(content: String, name: String = "a.log"): ParsedLog {
        val file = Files.writeString(dir.resolve(name), content)
        return runBlocking { ParsedLog.build(file, parser, IndexProgress()) }
    }

    private fun TemplateIndex.templates(log: LogData): List<String?> = runBlocking {
        update(log.filterSnapshot())
        val texts = texts()
        List(log.recordCount) { id(it).let { id -> if (id < 0) null else texts[id] } }
    }

    private fun count(log: LogData, rows: IntArray? = null): TemplateCounts = runBlocking {
        val rowCount = rows?.size ?: log.recordCount
        TemplateCounts.count(listOf(TemplateIndex()), listOf(log.filterSnapshot()), rows, rowCount, null)
    }

    private fun TemplateCounts.entries() = List(size) { templates[it] to total(it) }

    private fun FilterEngine.rows(filter: Filter) = runBlocking { prepare(filter).run() }.rows.toList()

    @Test
    fun `an update cancelled part way keeps what it finished, and the next one completes it`() {
        val content = buildString {
            repeat(2000) { append("2026-10-01 08:00:00.000 [main] INFO  app.Main - Job ${it % 7} took $it ms\n") }
        }
        parse(content).use { log ->
            val expected = TemplateIndex().templates(log)
            val index = TemplateIndex(chunkSize = 10)
            runBlocking {
                val update = launch(Dispatchers.Default) { index.update(log.filterSnapshot()) }
                while (index.progress == 1.0 && update.isActive) yield()
                update.cancelAndJoin()
            }
            assertEquals(expected, index.templates(log))
        }
    }

    @Test
    fun `counts records per template and level`() {
        parse(sample).use { log ->
            val counts = count(log)
            assertEquals(listOf(request to 3, query to 2, "Cache warmed" to 1), counts.entries())
            assertEquals(2, counts.count(0, Level.INFO))
            assertEquals(1, counts.count(0, Level.WARN))
            assertEquals(2, counts.count(1, Level.ERROR))
            assertEquals(listOf(Level.INFO, Level.WARN, Level.ERROR), counts.levels)
            assertEquals(6, counts.rows)

            val shown = count(log, intArrayOf(1, 2, 5))
            assertEquals(listOf(request to 1, query to 1, "Cache warmed" to 1), shown.entries())
            assertEquals(3, shown.rows)
        }
    }

    @Test
    fun `parallel chunks give the same ids as one`() {
        val content = buildString {
            for (i in 0..<500) {
                val word = if (i % 7 == 0) "x" else "y$i"
                append("2026-10-01 08:00:00.000 [t] INFO  app.L - kind ${i % 7} message $word\n")
            }
        }
        parse(content).use { log ->
            val expected = TemplateIndex().templates(log)
            assertEquals(expected, TemplateIndex(chunkSize = 3).templates(log))
            assertEquals(setOf("kind {} message x", "kind {} message {}"), expected.toSet())
        }
    }

    @Test
    fun `records over the template limit share one entry`() {
        parse(sample).use { log ->
            val templates = TemplateIndex(maxTemplates = 1).templates(log)
            assertEquals(listOf(request, null, request, request, null, null), templates)
        }
    }

    @Test
    fun `follows appended records and the last one growing`() {
        val file = Files.writeString(dir.resolve("partial.log"), "hello wor")
        runBlocking { LineIndex.build(file) }.use { log ->
            val index = TemplateIndex()
            assertEquals(listOf("hello wor"), index.templates(log))
            Files.writeString(file, "ld 42\nbye 7\n", StandardOpenOption.APPEND)
            log.append(log.readAppended(Files.size(file)))
            assertEquals(listOf("hello world {}", "bye {}"), index.templates(log))
        }
    }

    @Test
    fun `JSON messages are unescaped first`() {
        val file = Files.writeString(dir.resolve("a.json"), """
            {"level":"INFO","message":"User \"bob\" logged in from 10.0.0.1\nsecond line"}
            {"level":"WARN","message":"Disk 93% full"}
            {"level":"INFO"}
        """.trimIndent() + "\n")
        runBlocking { ParsedLog.build(file, CompiledJson(zone = ZoneOffset.UTC), IndexProgress()) }.use { log ->
            assertEquals(listOf("User \"bob\" logged in from {}", "Disk {} full", ""), TemplateIndex().templates(log))
            assertEquals("Disk {} full", TemplateIndex.templateOf(log.filterSnapshot(), 1))
        }
    }

    @Test
    fun `filters by template`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            assertEquals(listOf(0, 2, 3), engine.rows(TemplateCondition("Message", setOf(request))))
            assertEquals(listOf(1, 4, 5), engine.rows(Filter.Not(TemplateCondition("Message", setOf(request)))))
            assertEquals(listOf(1, 4, 5), engine.rows(TemplateCondition("Message", setOf(query, "Cache warmed"))))
            assertEquals(listOf(), engine.rows(TemplateCondition("Message", setOf("nothing like it"))))
        }
    }

    private fun nanos(time: String) = java.time.Instant.parse("2026-10-01T${time}Z").let {
        it.epochSecond * 1_000_000_000L + it.nano
    }

    private fun SlotValues.entries() = List(size) { time(it) to value(it) }

    @Test
    fun `reads a slot's values and times from the records of its template`() {
        parse(sample).use { log ->
            val engine = FilterEngine(log)
            fun values(slot: Int, rows: IntArray? = null) =
                runBlocking { engine.slotValues(rows, rows?.size ?: log.recordCount, request, slot) }.entries()
            assertEquals(
                listOf(nanos("08:00:00") to 5.0, nanos("08:00:02") to 5012.0, nanos("08:00:03") to 7.0),
                values(1),
            )
            // IP addresses are no numbers, but the records still count.
            assertEquals(List(3) { Double.NaN }, values(0).map { it.second })
            // Only the rows given, e.g. those a filter shows.
            assertEquals(listOf(nanos("08:00:03") to 7.0), values(1, intArrayOf(1, 3, 5)))
            assertEquals(listOf(), runBlocking { engine.slotValues(null, 6, "nothing like it", 0) }.entries())
        }
    }

    @Test
    fun `reads slots of JSON messages unescaped`() {
        val file = Files.writeString(dir.resolve("a.json"), """
            {"@timestamp":"2026-10-01T08:00:00Z","message":"User \"bob\" waited 120 ms"}
            {"@timestamp":"2026-10-01T08:00:01Z","message":"User \"eve\" waited 80 ms"}
        """.trimIndent() + "\n")
        runBlocking { ParsedLog.build(file, CompiledJson(zone = ZoneOffset.UTC), IndexProgress()) }.use { log ->
            val values = runBlocking { FilterEngine(log).slotValues(null, 2, "User \"bob\" waited {} ms", 0) }
            assertEquals(listOf(nanos("08:00:00") to 120.0), values.entries())
        }
    }

    @Test
    fun `merged logs read a slot from the template in every file`() {
        val a = parse(sample, "a.log")
        val b = parse("""
            2026-10-01 08:00:00.500 [main] INFO  app.B - Cache warmed
            2026-10-01 08:00:06.000 [main] ERROR app.B - Query 99 failed
        """.trimIndent() + "\n", "b.log")
        val merged = runBlocking { MergedLog.build(listOf(a, b)) }
        try {
            val filter = MergedFilter(merged)
            val values = runBlocking { filter.slotValues(null, merged.recordCount, query, 0) }
            assertEquals(
                listOf(nanos("08:00:01") to 17.0, nanos("08:00:04") to 18.0, nanos("08:00:06") to 99.0),
                values.entries(),
            )
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `merged logs count and filter by template text`() {
        val a = parse(sample, "a.log")
        val b = parse("""
            2026-10-01 08:00:00.500 [main] INFO  app.B - Cache warmed
            2026-10-01 08:00:06.000 [main] ERROR app.B - Query 99 failed
        """.trimIndent() + "\n", "b.log")
        val merged = runBlocking { MergedLog.build(listOf(a, b)) }
        try {
            val filter = MergedFilter(merged)
            val counts = runBlocking { filter.countTemplates(null, merged.recordCount) }
            assertEquals(listOf(request to 3, query to 3, "Cache warmed" to 2), counts.entries())
            val rows = runBlocking { filter.prepare(TemplateCondition("Message", setOf(query))).run() }.rows.toList()
            val messages = rows.map { merged.rowValues(it, 100).last() }
            assertEquals(listOf("Query 17 failed", "Query 18 failed", "Query 99 failed"), messages)
        } finally {
            a.close()
            b.close()
        }
    }
}
