package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import io.github.rastislavpapp.logxy.core.parse.open
import io.github.rastislavpapp.logxy.core.pattern.PatternIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What every [LoadedLog] does, whether of one file or a merge; the file under test is always part 0. */
abstract class LoadedLogContract {

    @TempDir
    lateinit var dir: Path

    /** Loads [file] as part 0, with whatever else the adapter needs. */
    internal abstract fun load(file: LogData): LoadedLog

    /** Whether appended data can make the load merge again. */
    abstract val canMergeAgain: Boolean

    protected fun open(name: String, content: String, format: LogFormat? = null): LogData {
        val file = Files.writeString(dir.resolve(name), content)
        return runBlocking { (format ?: LogFormat.detect(file)).open(file) }
    }

    private fun <T> load(content: String, name: String = "a.log", format: LogFormat? = null, test: (LoadedLog) -> T): T {
        val loaded = load(open(name, content, format))
        try {
            return test(loaded)
        } finally {
            loaded.parts.forEach { it.close() }
        }
    }

    /** Appends [text] to the files of [parts] and applies it; the records that may have changed, and the outcome. */
    private fun LoadedLog.grow(text: String, parts: List<Int>): Pair<List<Int>, Appended> {
        val changed = parts.map { part ->
            val log = this.parts[part]
            Files.writeString(log.path, text, StandardOpenOption.APPEND)
            append(part, log.readAppended(Files.size(log.path)))
        }
        return changed to runBlocking { settle() }
    }

    private fun LoadedLog.grow(text: String): Pair<Int, Appended> = grow(text, listOf(0)).let { (changed, outcome) -> changed.single() to outcome }

    private fun Records.field(name: String) = schema.fields.indexOfFirst { it.name == name }

    private fun Records.messages(): List<String> = List(recordCount) { fieldText(it, field("Message")) }

    private fun line(second: Int, message: String, level: String = "INFO ") =
        "2026-10-01 10:00:%02d.000 [main] $level app.A - $message\n".format(second)

    private val lines = line(1, "A1") + line(3, "A2", "WARN ")

    private fun json(second: Int, message: String, extra: String = "") =
        """{"timestamp":"2026-10-01T10:00:%02d.000Z","message":"$message"$extra}""".format(second)

    @Test
    fun `appending grows the records and tells the last record shown before`() = load(lines) { loaded ->
        val before = loaded.records.recordCount
        val (changed, outcome) = loaded.grow("java.lang.NullPointerException\n" + line(9, "A3"))
        assertEquals(Appended.GREW, outcome)
        assertEquals(before + 1, loaded.records.recordCount)
        assertEquals("A2\njava.lang.NullPointerException", loaded.records.fieldText(changed, loaded.records.field("Message")))
        assertEquals("A3", loaded.records.messages().last())
    }

    @Test
    fun `a field first present in appended records grows the schema`() = load(json(1, "A1") + "\n", "a.json") { loaded ->
        val schema = loaded.records.schema
        assertTrue(schema.fields.single { it.name == "Exception" }.hidden)
        assertEquals(Appended.NEW_FIELDS, loaded.grow(json(5, "A2", ""","stack_trace":"boom"""") + "\n").second)
        assertNotEquals(schema, loaded.records.schema)
        assertFalse(loaded.records.schema.fields.single { it.name == "Exception" }.hidden)
        assertEquals(Appended.GREW, loaded.grow(json(6, "A3") + "\n").second)
    }

    // A record's message is one word.
    private val oneWord = LogFormat.Regex("One word", """^(?<timestamp>\S+ \S+) (?<message>\w+)$""", "yyyy-MM-dd HH:mm:ss.SSS")

    @Test
    fun `a last record parsed differently merges again`() =
        load("2026-10-01 10:00:01.000 A1\n2026-10-01 10:00:03.000 A2", format = oneWord) { loaded ->
            val before = loaded.parts[0].recordCount
            // Without its line end, the last line was a record; with more words it is part of the one before.
            val outcome = loaded.grow(" and more\n").second
            assertEquals(before - 1, loaded.parts[0].recordCount)
            if (canMergeAgain) assertEquals(Appended.MERGE_AGAIN, outcome) else assertNotEquals(Appended.MERGE_AGAIN, outcome)
        }

    @Test
    fun `each record is a part's record`() = load(lines) { loaded ->
        val records = loaded.records
        val message = records.field("Message")
        val seen = loaded.parts.map { ArrayList<Int>() }
        for (record in 0..<records.recordCount) {
            val part = loaded.origin?.part(record) ?: 0
            val partRecord = loaded.origin?.record(record) ?: record
            val log = loaded.parts[part]
            assertEquals(log.fieldText(partRecord, log.field("Message")), records.fieldText(record, message))
            seen[part] += partRecord
            // The record itself is its part's first one shown from there on.
            assertEquals(partRecord, loaded.firstRecordFrom(part, record))
        }
        for ((part, records) in seen.withIndex()) assertEquals(List(loaded.parts[part].recordCount) { it }, records)
        assertEquals(loaded.parts[0].recordCount, loaded.firstRecordFrom(0, records.recordCount))
    }

    @Test
    fun `the template of a record is its part's`() = load(line(1, "order 17 shipped") + line(2, "order 18 shipped") + line(3, "done")) { loaded ->
        val part = loaded.parts[0]
        val templates = List(part.recordCount) { PatternIndex.templateOf(part.filterSnapshot(), it) }
        assertEquals(templates[0], templates[1])
        for (record in 0..<loaded.records.recordCount) {
            if ((loaded.origin?.part(record) ?: 0) != 0) continue
            assertEquals(templates[loaded.origin?.record(record) ?: record], loaded.templateOf(record))
        }
    }

    @Test
    fun `reads a field of all records or of given ones`() = load(lines) { loaded ->
        val records = loaded.records
        for (field in records.schema.fields.indices) {
            val all = loaded.fieldReader(field, null, records.recordCount)
            assertEquals(List(records.recordCount) { records.fieldText(it, field) }, List(records.recordCount) { all(it) })
            val some = intArrayOf(records.recordCount - 1, 0)
            val read = loaded.fieldReader(field, some, some.size)
            assertEquals(some.map { records.fieldText(it, field) }, List(some.size) { read(it) })
        }
    }

    @Test
    fun `catching up filters the last record of each file again`() = load(lines) { loaded ->
        val filter = loaded.newFilter()
        val npe = TextCondition("Message", TextOp.EQUALS, "*NullPointer*")
        assertEquals(emptyList(), runBlocking { filter.prepare(npe).run() }.rows.toList())
        val covered = loaded.records.recordCount
        val (changed, _) = loaded.grow("java.lang.NullPointerException\n", loaded.parts.indices.toList())
        val from = filter.changedFrom(covered)
        assertTrue(from <= changed.min())
        assertEquals(changed.sorted(), runBlocking { filter.prepare(npe, from).run() }.rows.toList())
    }
}

class SingleLoadTest : LoadedLogContract() {
    override fun load(file: LogData): LoadedLog = runBlocking { loadedLog(listOf(file), listOf("a")) }

    override val canMergeAgain = false
}

class MergedLoadTest : LoadedLogContract() {
    override fun load(file: LogData): LoadedLog {
        // Interleaves with part 0, and its last record is the latest.
        val other = open("b.log", "2026-10-01 10:00:02.000 [main] INFO  app.B - B1\n2026-10-01 10:00:08.000 [main] INFO  app.B - B2\n")
        return runBlocking { loadedLog(listOf(file, other), listOf("a", "b")) }
    }

    override val canMergeAgain = true
}
