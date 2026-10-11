package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryOp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSessionTest {

    @TempDir
    lateinit var dir: Path

    // Stands in for the UI thread: the session and its listener run only here.
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)

    @AfterEach
    fun stop() {
        scope.cancel()
        executor.shutdown()
    }

    private object NoSettings : SettingsStore {
        override val current = Settings()
        override fun addListener(listener: () -> Unit) {}
        override fun removeListener(listener: () -> Unit) {}
    }

    private class Recorder : SessionListener {
        val loads = Channel<Records>(Channel.UNLIMITED)
        val problem = CompletableDeferred<String>()
        val notMergeable = CompletableDeferred<List<Path>>()
        override fun indexing(fraction: () -> Double) {}
        override fun problem(title: String, detail: String, hint: String?) { problem.complete(title) }
        override fun loaded(records: Records) { loads.trySend(records) }
        override fun notMergeable(paths: List<Path>) { notMergeable.complete(paths) }
        override fun formatChanged() {}
        override fun recordsAppended(oldRecordCount: Int, changed: IntArray) {}
        override fun rowsReplaced(rows: FilteredRows?, keepView: Boolean) {}
        override fun rowsExtended(firstChanged: Int, oldSize: Int) {}
        override fun levelMappingChanged() {}
        override fun followChanged(follow: Boolean) {}
        override fun statusChanged(status: SessionStatus) {}
    }

    private var n = 0
    private val levels = listOf("INFO ", "WARN ", "DEBUG", "ERROR")
    private fun line() = "2026-10-01 08:00:00.000 [main] ${levels[n % 4]} app.L - message ${n++}\n"
    private fun lines(count: Int) = buildString { repeat(count) { append(line()) } }

    private fun append(file: Path, text: String) {
        Files.writeString(file, text, StandardOpenOption.APPEND)
    }

    private suspend fun open(file: Path, recorder: Recorder) = open(listOf(file), recorder)

    private suspend fun open(files: List<Path>, recorder: Recorder): Pair<LogSession, Records> {
        val session = withContext(ui) { LogSession(files, scope, NoSettings, recorder).also { it.start() } }
        return session to withTimeout(10_000) { recorder.loads.receive() }
    }

    /**
     * Waits until the session has read all of [files] and shows what a fresh evaluation of [filter]
     * gives, with the status counting it.
     */
    private suspend fun awaitShown(session: LogSession, file: Path, filter: Filter) = awaitShown(session, listOf(file), filter)

    private suspend fun awaitShown(session: LogSession, files: List<Path>, filter: Filter): List<Int> = withTimeout(10_000) {
        var done = false
        var expected = emptyList<Int>()
        while (!done) {
            delay(50)
            withContext(ui) {
                val records = session.records!!
                val fresh = if (records is MergedLog) MergedFilter(records) else FilterEngine(records as LogData)
                // run() frees the UI thread, so the session may read appended data meanwhile: only a result covering all of it counts.
                val result = fresh.prepare(filter).run()
                expected = result.rows.toList()
                val size = files.sumOf { Files.size(it) }
                val shown = session.shown?.let { rows -> List(rows.size) { rows[it] } }
                done = result.indexedSize == size && session.status.size == size && shown == expected &&
                    session.status.shown == expected.size && !session.status.filtering
            }
        }
        expected
    }

    @Test
    fun `a merge uses the formats chosen for its files and detects the others`() = runBlocking {
        val a = Files.writeString(dir.resolve("a.log"), lines(10))
        val b = Files.writeString(dir.resolve("b.log"), lines(10))
        val pattern = LogFormat.Pattern("Mine", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n")
        val recorder = Recorder()
        val session = withContext(ui) { LogSession(listOf(a, b), scope, NoSettings, recorder, chosen = listOf(null, pattern)).also { it.start() } }
        val records = withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertEquals(listOf(null, pattern), session.chosenFormats)
            assertEquals(listOf(LogFormat.BUILT_IN[0], pattern), session.formats)
            assertEquals(20, records.recordCount)
            session.useFormats(listOf(pattern, null))
        }
        withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertEquals(listOf(pattern, null), session.chosenFormats)
            assertEquals(listOf(pattern, LogFormat.BUILT_IN[0]), session.formats)
            session.dispose()
        }
    }

    @Test
    fun `a filter follows appended records, also when a record is completed later`() = runBlocking {
        val file = Files.writeString(dir.resolve("tail.log"), lines(1000))
        val (session, _) = open(file, Recorder())
        withContext(ui) { session.applyFilter(Level.WARN, Query.EMPTY) }
        val warnOrWorse = LevelCondition.atLeast("Level", Level.WARN)
        assertEquals(500, awaitShown(session, file, warnOrWorse).size)
        repeat(6) { round ->
            // Every other batch ends in the middle of a WARN record, completed with the next one.
            append(file, lines(100) + if (round % 2 == 0) "2026-10-01 08:00:00.000 [main] WA" else "")
            delay(300)
            if (round % 2 == 0) append(file, "RN  app.L - completed ${n++}\ncontinued\n")
            awaitShown(session, file, warnOrWorse)
        }
        // Half of the 1600 lines are WARN or ERROR, plus the three completed WARN records.
        assertEquals(803, awaitShown(session, file, warnOrWorse).size)
    }

    @Test
    fun `a filter on the message sees a stack trace written after its record`() = runBlocking {
        val file = Files.writeString(dir.resolve("trace.log"), lines(10))
        val (session, _) = open(file, Recorder())
        val query = Query.Group(all = true, items = listOf(Query.Rule("Message", QueryOp.EQUALS, "*NullPointer*")))
        withContext(ui) { session.applyFilter(null, query) }
        val npe = TextCondition("Message", TextOp.EQUALS, "*NullPointer*", ignoreCase = true)
        assertEquals(emptyList(), awaitShown(session, file, npe))
        append(file, "java.lang.NullPointerException: boom\n\tat a.B(B.java:1)\n")
        assertEquals(listOf(9), awaitShown(session, file, npe))
    }

    @Test
    fun `a truncated file is read again`() = runBlocking {
        val file = Files.writeString(dir.resolve("rotate.log"), lines(100))
        val recorder = Recorder()
        val (_, first) = open(file, recorder)
        assertEquals(100, withContext(ui) { first.recordCount })
        Files.writeString(file, lines(3))
        val second = withTimeout(10_000) { recorder.loads.receive() }
        assertEquals(3, withContext(ui) { second.recordCount })
    }

    @Test
    fun `merged files follow appends to all of them, filtered`() = runBlocking {
        val a = Files.writeString(dir.resolve("a.log"), lines(300))
        val b = Files.writeString(dir.resolve("b.log"), lines(200))
        val (session, records) = open(listOf(a, b), Recorder())
        assertTrue(records is MergedLog)
        assertEquals(500, withContext(ui) { records.recordCount })
        val query = Query.Group(all = true, items = listOf(Query.Rule("Source", QueryOp.EQUALS, "b.log")))
        withContext(ui) { session.applyFilter(Level.WARN, query) }
        val bWarnOrWorse = Filter.And(listOf(LevelCondition.atLeast("Level", Level.WARN), TextCondition("Source", TextOp.EQUALS, "b.log")))
        assertEquals(100, awaitShown(session, listOf(a, b), bWarnOrWorse).size)
        repeat(4) { round ->
            append(a, lines(50))
            // b gets a record whose level only arrives with the next batch.
            append(b, lines(30) + if (round % 2 == 0) "2026-10-01 08:00:00.000 [main] WA" else "")
            delay(300)
            if (round % 2 == 0) append(b, "RN  app.L - completed ${n++}\n")
            awaitShown(session, listOf(a, b), bWarnOrWorse)
        }
        assertEquals(500 + 4 * 80 + 2, withContext(ui) { session.records!!.recordCount })
    }

    @Test
    fun `files without timestamps are left out of a merge`() = runBlocking {
        val a = Files.writeString(dir.resolve("a.log"), lines(10))
        val b = Files.writeString(dir.resolve("b.log"), lines(10))
        val raw = Files.writeString(dir.resolve("notes.txt"), "just\nsome\ntext\n")
        val recorder = Recorder()
        val (session, records) = open(listOf(a, raw, b), recorder)
        assertEquals(listOf(raw), withTimeout(1000) { recorder.notMergeable.await() })
        assertTrue(records is MergedLog)
        assertEquals(listOf(a, b), withContext(ui) { session.paths })
        assertEquals(20, withContext(ui) { records.recordCount })
    }

    @Test
    fun `a parser that does not fit is reported`() = runBlocking {
        val file = Files.writeString(dir.resolve("other.log"), "plain text\n".repeat(20))
        val recorder = Recorder()
        val session = withContext(ui) { LogSession(file, scope, NoSettings, recorder) }
        withContext(ui) { session.useFormat(LogFormat.BUILT_IN.first()) }
        val title = withTimeout(10_000) { recorder.problem.await() }
        assertTrue("does not match" in title, title)
    }
}
