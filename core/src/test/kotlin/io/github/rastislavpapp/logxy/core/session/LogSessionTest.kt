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
import io.github.rastislavpapp.logxy.core.query.TextSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
import kotlin.test.assertFalse
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
        val loads = Channel<ShownLog>(Channel.UNLIMITED)
        val problem = CompletableDeferred<String>()
        val notMergeable = CompletableDeferred<List<Path>>()
        override fun indexing(fraction: () -> Double) {}
        override fun problem(title: String, detail: String, hint: String?) { problem.complete(title) }
        override fun loaded(shown: ShownLog) { loads.trySend(shown) }
        var onShownEnded = {}
        override fun shownEnded() = onShownEnded()
        override fun notMergeable(paths: List<Path>) { notMergeable.complete(paths) }
        override fun formatChanged() {}
        override fun recordsGrew(changed: IntArray) {}
        val fieldsShown = Channel<Unit>(Channel.UNLIMITED)
        override fun fieldsShown() { fieldsShown.trySend(Unit) }
        var replaced = 0
        override fun rowsReplaced(keepView: Boolean, oldSize: Int, recordBefore: (row: Int) -> Int) { replaced++ }
        val rowsChanged = ArrayList<Pair<Int, Int>>()
        override fun rowsChanged(firstChanged: Int, oldSize: Int) { rowsChanged += firstChanged to oldSize }
        override fun levelMappingChanged() {}
        override fun histogramChanged() {}
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

    private suspend fun open(files: List<Path>, recorder: Recorder): Pair<LogSession, ShownLog> {
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
                val shown = session.shown?.takeIf { it.isFiltered }?.let { rows -> List(rows.size) { rows.recordAt(it) } }
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
        val shown = withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertEquals(listOf(null, pattern), session.chosenFormats)
            assertEquals(listOf(LogFormat.BUILT_IN[0], pattern), session.formats)
            assertEquals(20, shown.size)
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
    fun `applying the filter that is already shown does not replace the rows`() = runBlocking {
        val file = Files.writeString(dir.resolve("same.log"), lines(100))
        val recorder = Recorder()
        val (session, _) = open(file, recorder)
        val empty = Query.Group(all = true, items = emptyList())
        withContext(ui) {
            session.applyFilter(null, empty)
            session.applyFilter(null, empty)
            assertEquals(1, recorder.replaced)
            session.applyFilter(Level.WARN, empty)
            session.applyFilter(Level.WARN, empty)
        }
        awaitShown(session, file, LevelCondition.atLeast("Level", Level.WARN))
        withContext(ui) {
            session.applyFilter(Level.WARN, empty)
            assertEquals(2, recorder.replaced)
            session.applyFilter(null, empty)
            assertEquals(3, recorder.replaced)
        }
    }

    /** Waits until the histogram counts [records] records, [warnings] warnings and [errors] errors. */
    private suspend fun awaitHistogram(session: LogSession, records: Int, warnings: Int, errors: Int) = withTimeout(10_000) {
        while (true) {
            val counts = withContext(ui) {
                session.histogram?.let { h ->
                    IntArray(TimeHistogram.CATEGORIES) { c -> (h.firstBin..h.lastBin).sumOf { h.count(c, it) } }
                }
            }
            if (counts != null && counts.sum() == records && counts[TimeHistogram.WARN] == warnings && counts[TimeHistogram.ERROR] == errors) return@withTimeout
            delay(50)
        }
    }

    @Test
    fun `counts the times of the shown rows, also of records appended later`() = runBlocking {
        val file = Files.writeString(dir.resolve("histogram.log"), lines(1000))
        val (session, _) = open(file, Recorder())
        withContext(ui) { session.applyFilter(null, Query.EMPTY) }
        awaitHistogram(session, 1000, 250, 250)
        // Ends in the middle of an ERROR record, which counts once completed with the next append.
        append(file, lines(100) + "2026-10-01 08:00:00.000 [main] ERR")
        awaitHistogram(session, 1100, 275, 275)
        append(file, "OR app.L - completed\n")
        awaitHistogram(session, 1101, 275, 276)
        withContext(ui) { session.applyFilter(Level.WARN, Query.EMPTY) }
        awaitHistogram(session, 551, 275, 276)
        append(file, lines(4))
        awaitHistogram(session, 553, 276, 277)
    }

    @Test
    fun `merged files are counted file by file`() = runBlocking {
        val a = Files.writeString(dir.resolve("a.log"), lines(300))
        val b = Files.writeString(dir.resolve("b.log"), lines(500))
        val (session, _) = open(listOf(a, b), Recorder())
        withContext(ui) { session.applyFilter(null, Query.EMPTY) }
        awaitHistogram(session, 800, 200, 200)
        withContext(ui) { session.applyFilter(Level.ERROR, Query.EMPTY) }
        awaitHistogram(session, 200, 0, 200)
    }

    @Test
    fun `a search finds records among those the filter lets through`() = runBlocking {
        val file = Files.writeString(dir.resolve("search.log"), lines(20))
        val (session, _) = open(file, Recorder())
        withContext(ui) { session.applyFilter(Level.WARN, Query.EMPTY) }
        val found = withContext(ui) { session.prepareSearch(TextSearch("MESSAGE 1"))!!.run() }
        assertEquals(listOf(1, 11, 13, 15, 17, 19), found.rows.toList())
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
        assertEquals(100, withContext(ui) { first.size })
        Files.writeString(file, lines(3))
        val second = withTimeout(10_000) { recorder.loads.receive() }
        assertEquals(3, withContext(ui) { second.size })
        assertTrue(withContext(ui) { first.ended && first.size == 0 })
    }

    @Test
    fun `merged files follow appends to all of them, filtered`() = runBlocking {
        val a = Files.writeString(dir.resolve("a.log"), lines(300))
        val b = Files.writeString(dir.resolve("b.log"), lines(200))
        val (session, shown) = open(listOf(a, b), Recorder())
        assertTrue(session.isMerged)
        assertEquals(500, withContext(ui) { shown.size })
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
    fun `a field that appended records are the first to have is shown, also in a merge`() = runBlocking {
        fun json(n: Int, extra: String = "") = """{"timestamp":"2026-10-01T08:00:0$n.000Z","message":"m$n"$extra}""" + "\n"
        for (merged in listOf(false, true)) {
            val events = Files.writeString(dir.resolve("events-$merged.json"), json(1) + json(2))
            val files = if (merged) listOf(events, Files.writeString(dir.resolve("a.log"), lines(5))) else listOf(events)
            val recorder = Recorder()
            val (session, shown) = open(files, recorder)
            fun exceptionHidden() = shown.schema.fields.single { it.name == "Exception" }.hidden
            assertTrue(withContext(ui) { exceptionHidden() })
            append(events, json(3, ""","stack_trace":"boom""""))
            withTimeout(10_000) { recorder.fieldsShown.receive() }
            assertFalse(withContext(ui) { exceptionHidden() })
            withContext(ui) { session.dispose() }
        }
    }

    @Test
    fun `files without timestamps are left out of a merge`() = runBlocking {
        val a = Files.writeString(dir.resolve("a.log"), lines(10))
        val b = Files.writeString(dir.resolve("b.log"), lines(10))
        val raw = Files.writeString(dir.resolve("notes.txt"), "just\nsome\ntext\n")
        // JSON has an optional timestamp field, without values when the time is under a key it does not know.
        val json = Files.writeString(dir.resolve("events.json"), """{"when":"yesterday","message":"one"}""" + "\n")
        val recorder = Recorder()
        val (session, shown) = open(listOf(a, raw, json, b), recorder)
        assertEquals(listOf(raw, json), withTimeout(1000) { recorder.notMergeable.await() })
        assertTrue(session.isMerged)
        assertEquals(listOf(a, b), withContext(ui) { session.paths })
        assertEquals(20, withContext(ui) { shown.size })
    }

    @Test
    fun `a reload ends the shown log before it closes the files`() = runBlocking {
        val file = Files.writeString(dir.resolve("reload.log"), lines(10))
        val recorder = Recorder()
        val (session, shown) = open(file, recorder)
        var readable: String? = null
        recorder.onShownEnded = {
            assertTrue(shown.ended)
            readable = session.records!!.fieldText(0, 4)
        }
        withContext(ui) { session.reload() }
        assertEquals("message 0", readable)
        withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `appended records change the rows the same way with and without a filter`() = runBlocking {
        val changes = listOf(null, LevelCondition.atLeast("Level", Level.DEBUG)).map { filter ->
            val file = Files.writeString(dir.resolve("follow-${filter != null}.log"), lines(100))
            val recorder = Recorder()
            val (session, shown) = open(file, recorder)
            if (filter != null) {
                withContext(ui) { session.applyFilter(Level.DEBUG, Query.EMPTY) }
                awaitShown(session, file, filter)
            }
            for (size in listOf(110, 120)) {
                append(file, lines(10))
                withTimeout(10_000) { while (withContext(ui) { shown.size } < size) delay(50) }
            }
            withContext(ui) {
                session.dispose()
                recorder.rowsChanged.toList()
            }
        }
        assertEquals(listOf(100 to 100, 110 to 110), changes[0])
        assertEquals(changes[0], changes[1])
    }

    @Test
    fun `a column copied while the session reloads tells the file changed`() = runBlocking {
        val file = Files.writeString(dir.resolve("copy.log"), lines(10_000))
        val (session, shown) = open(file, Recorder())
        val result = withContext(ui) {
            // Runs up to reading on another thread, then the reload closes the files under it.
            val copy = async(start = CoroutineStart.UNDISPATCHED) { shown.copyColumn(4) }
            session.reload()
            copy.await()
        }
        assertEquals(CopyResult.FileChanged, result)
        withContext(ui) { session.dispose() }
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

    @Test
    fun `a merge names the file its parser does not fit`() = runBlocking {
        val files = (1..4).map { Files.writeString(dir.resolve("part-$it.log"), lines(100)) }
        val other = Files.writeString(dir.resolve("other.log"), "plain text\n".repeat(20))
        val recorder = Recorder()
        withContext(ui) {
            LogSession(files + listOf(other), scope, NoSettings, recorder, chosen = List(4) { null } + LogFormat.BUILT_IN.first()).start()
        }
        val title = withTimeout(10_000) { recorder.problem.await() }
        assertTrue(title.startsWith("other.log does not match"), title)
    }
}
