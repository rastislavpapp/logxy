package io.github.rastislavpapp.logxy.core.merge

import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.PresenceCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.parse.CompiledPattern
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MergedLogTest {

    @TempDir
    lateinit var dir: Path

    private val withThread = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n", ZoneOffset.UTC)
    private val withoutThread = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p %c - %m%n", ZoneOffset.UTC)

    private fun parse(name: String, parser: CompiledPattern, content: String): ParsedLog {
        val file = Files.writeString(dir.resolve(name), content.trimIndent() + "\n")
        return runBlocking { ParsedLog.build(file, parser, IndexProgress()) }
    }

    private fun merge(vararg parts: ParsedLog) = runBlocking { MergedLog.build(parts.toList()) }

    private fun MergedLog.column(name: String): List<String> {
        val field = schema.fields.indexOfFirst { it.name == name }
        return List(recordCount) { rowValues(it, 1000)[field] }
    }

    private fun MergedLog.rows(filter: Filter, from: Int = 0) =
        runBlocking { MergedFilter(this@rows).prepare(filter, from).run() }.rows.toList()

    private val fileA get() = parse("a.log", withThread, """
        2026-10-01 10:00:01.500 [main] INFO  app.A - A1
        2026-10-01 10:00:01.200 [worker] WARN  app.A - A2
    """)

    private val fileB get() = parse("b.log", withoutThread, """
        2026-10-01 10:00:01.300 ERROR app.B - B1
        2026-10-01 10:00:02.000 INFO  app.B - B2
    """)

    @Test
    fun `files interleave by time and keep their own order`() {
        merge(fileA, fileB).use { log ->
            assertEquals(listOf("B1", "A1", "A2", "B2"), log.column("Message"))
            assertEquals(listOf("b.log", "a.log", "a.log", "b.log"), log.column("Source"))
            assertEquals(listOf(1, 0, 0, 1), List(log.recordCount) { log.part(it) })
            assertEquals(1, log.position(1, 0) + 1)
        }
    }

    @Test
    fun `a file starting earlier comes first, records without time keep their place`() {
        val late = parse("late.log", withThread, """
            2026-10-01 11:00:00.000 [main] INFO  app.L - late
        """)
        val early = parse("early.log", withThread, """
            banner without a timestamp
            2026-10-01 09:00:00.000 [main] INFO  app.E - early
            2026-10-01 12:00:00.000 [main] INFO  app.E - noon
        """)
        merge(late, early).use { log ->
            assertEquals(listOf("banner without a timestamp", "early", "late", "noon"), log.column("Message"))
        }
    }

    @Test
    fun `fields are matched by role, then by name, missing ones are empty`() {
        merge(fileA, fileB).use { log ->
            val names = log.schema.fields.map { it.name }
            assertEquals(listOf("Source", "Timestamp", "Thread", "Level", "Logger", "Message"), names)
            assertEquals(Role.SOURCE, log.schema.fields[0].role)
            assertEquals(listOf("", "main", "worker", ""), log.column("Thread"))
            assertEquals(listOf("ERROR", "INFO", "WARN", "INFO"), log.column("Level"))
            val levels = List(log.recordCount) { log.level(it) }
            assertEquals(listOf(Level.ERROR, Level.INFO, Level.WARN, Level.INFO), levels)
        }
    }

    @Test
    fun `source names are as long as needed to tell files apart`() {
        val paths = listOf("/x/app/server.log", "/x/db/server.log", "/x/db/other.log").map { Path.of(it) }
        val names = MergedLog.sourceNames(paths)
        assertEquals(listOf("app/server.log", "db/server.log", "db/other.log"), names)
        assertEquals(listOf("a.log", "b.log"), MergedLog.sourceNames(listOf(Path.of("/x/a.log"), Path.of("/y/b.log"))))
    }

    @Test
    fun `names files from zips by the zip`() {
        val origins = mapOf(
            "/spool/1/app.zip/logs/node1.log" to ZipSpool.Origin(Path.of("/data/app.zip"), "logs/node1.log"),
            "/spool/2/more.zip/archive/older.zip/node3.log" to
                ZipSpool.Origin(Path.of("/data/more.zip"), "archive/older.zip/node3.log"),
            "/spool/3/a.zip/x/app.log" to ZipSpool.Origin(Path.of("/d1/a.zip"), "x/app.log"),
            "/spool/3/a.zip/y/app.log" to ZipSpool.Origin(Path.of("/d1/a.zip"), "y/app.log"),
            "/spool/4/b.zip/app.log" to ZipSpool.Origin(Path.of("/d1/b.zip"), "app.log"),
            "/spool/5/b.zip/app.log" to ZipSpool.Origin(Path.of("/d2/b.zip"), "app.log"),
        ).mapKeys { Path.of(it.key) }
        fun names(vararg paths: String) = MergedLog.sourceNames(paths.map { Path.of(it) }) { origins[it] }
        assertEquals(
            listOf("app.zip/node1.log", "more.zip/node3.log", "other.log"),
            names("/spool/1/app.zip/logs/node1.log", "/spool/2/more.zip/archive/older.zip/node3.log", "/x/other.log"),
        )
        assertEquals(
            listOf("a.zip/x/app.log", "a.zip/y/app.log"),
            names("/spool/3/a.zip/x/app.log", "/spool/3/a.zip/y/app.log"),
        )
        assertEquals(
            listOf("d1/b.zip/app.log", "d2/b.zip/app.log"),
            names("/spool/4/b.zip/app.log", "/spool/5/b.zip/app.log"),
        )
    }

    @Test
    fun `filters work per file and map to merged positions`() {
        merge(fileA, fileB).use { log ->
            assertEquals(listOf(0, 2), log.rows(LevelCondition.atLeast("Level", Level.WARN)))
            assertEquals(listOf(1, 2), log.rows(TextCondition("Source", TextOp.EQUALS, "a.*", ignoreCase = true)))
            assertEquals(listOf(0, 3), log.rows(Filter.Not(TextCondition("Source", TextOp.EQUALS, "a.log"))))
            // Thread exists only in a.log; elsewhere it is absent.
            assertEquals(listOf(1), log.rows(TextCondition("Thread", TextOp.EQUALS, "main")))
            assertEquals(listOf(0, 2, 3), log.rows(TextCondition("Thread", TextOp.NOT_EQUALS, "main")))
            assertEquals(listOf(1, 2), log.rows(PresenceCondition("Thread")))
            val either = Filter.Or(
                listOf(TextCondition("Message", TextOp.EQUALS, "A2"), TextCondition("Message", TextOp.EQUALS, "B2")),
            )
            assertEquals(listOf(2, 3), log.rows(either, from = 2))
        }
    }

    @Test
    fun `appended records are merged among themselves at the end`() {
        val a = fileA
        val b = fileB
        merge(a, b).use { log ->
            Files.writeString(a.path, "2026-10-01 10:00:05.000 [main] ERROR app.A - A3\n", StandardOpenOption.APPEND)
            Files.writeString(b.path, "2026-10-01 10:00:03.000 INFO  app.B - B3\n", StandardOpenOption.APPEND)
            // A record earlier than ones already shown still goes after them.
            Files.writeString(a.path, "2026-10-01 10:00:00.000 [main] INFO  app.A - A4\n", StandardOpenOption.APPEND)
            for (part in listOf(a, b)) part.append(part.readAppended(Files.size(part.path)))
            assertEquals(true, runBlocking { log.appendNew() })
            assertEquals(listOf("B1", "A1", "A2", "B2", "B3", "A3", "A4"), log.column("Message"))
            assertEquals(6, log.position(0, 3))
        }
    }

    @Test
    fun `catching up re-filters the last record of each file`() {
        val a = fileA
        val b = fileB
        merge(a, b).use { log ->
            val filter = MergedFilter(log)
            val npe = TextCondition("Message", TextOp.EQUALS, "*NullPointer*")
            assertEquals(emptyList(), runBlocking { filter.prepare(npe, 0).run() }.rows.toList())
            val covered = log.recordCount
            // A2 (merged position 2) gets a stack trace; B2 (position 3) stays as it was.
            Files.writeString(a.path, "java.lang.NullPointerException\n", StandardOpenOption.APPEND)
            a.append(a.readAppended(Files.size(a.path)))
            assertEquals(true, runBlocking { log.appendNew() })
            assertEquals(2, filter.changedFrom(covered))
            val rows = runBlocking { filter.prepare(npe, filter.changedFrom(covered)).run() }.rows
            assertEquals(listOf(2), rows.toList())
        }
    }

    @Test
    fun `a part needs a timestamp`() {
        val raw = parse("raw.log", CompiledPattern("%m%n"), "just text")
        val failure = runCatching { merge(fileA, raw) }.exceptionOrNull()
        assertFalse(failure == null)
    }

    private fun MergedLog.use(block: (MergedLog) -> Unit) = try {
        block(this)
    } finally {
        parts.forEach { it.close() }
    }

    @Test
    fun `a field stored as a span in one log matches the same field of another`() {
        val dict = Schema(listOf(Field("Logger", ColumnType.DICT, Role.LOGGER), Field("Thread", ColumnType.DICT)))
        val span = Schema(listOf(Field("Logger", ColumnType.SPAN, Role.LOGGER), Field("thread", ColumnType.SPAN)))
        val names = MergedSchema(listOf(dict, span)).schema.fields.map { it.name }
        assertEquals(listOf("Source", "Logger", "Thread"), names)
    }

    @Test
    fun `MDC keys of several files share columns`() {
        val mdc = CompiledPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c %X - %m%n", ZoneOffset.UTC)
        val a = parse("mdc-a.log", mdc, "2026-10-01 10:00:01.000 [main] INFO  app.A {tenant=acme, user=1} - A1")
        val b = parse("mdc-b.log", mdc, "2026-10-01 10:00:02.000 [main] INFO  app.B {tenant=globex} - B1")
        merge(a, b).use { log ->
            assertEquals(
                listOf("Source", "Timestamp", "Thread", "Level", "Logger", "Message", "MDC[tenant]", "MDC[user]"),
                log.schema.fields.filter { !it.hidden }.map { it.name },
            )
            assertEquals(listOf("acme", "globex"), log.column("MDC[tenant]"))
            assertEquals(listOf(1), log.rows(TextCondition("MDC[tenant]", TextOp.EQUALS, "glob*")))
        }
    }
}
