package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterResult
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.query.TextSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSessionTest {

    @TempDir
    lateinit var dir: Path

    /** What escaped to the UI thread's uncaught-exception handler, as the app logs it. */
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

    // Stands in for the UI thread: the session and its listener run only here.
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task).apply { setUncaughtExceptionHandler { _, e -> uncaught += e } }
    }
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

    private class ChangingSettings(override var current: Settings) : SettingsStore {
        private val listeners = ArrayList<() -> Unit>()
        override fun addListener(listener: () -> Unit) {
            listeners += listener
        }
        override fun removeListener(listener: () -> Unit) {
            listeners -= listener
        }

        /** Must run on the session's thread, as listeners run on the thread that changed the settings. */
        fun change(settings: Settings) {
            current = settings
            listeners.toList().forEach { it() }
        }
    }

    private class Recorder : SessionListener {
        val loads = Channel<ShownLog>(Channel.UNLIMITED)
        val problem = CompletableDeferred<String>()
        val notMergeable = CompletableDeferred<List<Path>>()
        override fun indexing(fraction: () -> Double) {}
        override fun problem(title: String, detail: String, hint: String?) {
            problem.complete(title)
        }
        override fun loaded(shown: ShownLog) {
            loads.trySend(shown)
        }
        var onShownEnded = {}
        override fun shownEnded() = onShownEnded()
        override fun notMergeable(paths: List<Path>) {
            notMergeable.complete(paths)
        }
        var parserChanged = 0
        override fun parserChanged() {
            parserChanged++
        }
        override fun recordsGrew(changed: IntArray) {}
        val fieldsShown = Channel<Unit>(Channel.UNLIMITED)
        override fun fieldsShown() {
            fieldsShown.trySend(Unit)
        }
        var replaced = 0

        /** Whether the listener fails each time the rows change, as a UI bug would. */
        var failOnRows = false
        override fun rowsReplaced(keepView: Boolean, oldSize: Int, recordBefore: (row: Int) -> Int) {
            replaced++
            if (failOnRows) throw IllegalStateException("rowsReplaced failed")
        }
        val rowsChanged = ArrayList<Pair<Int, Int>>()
        override fun rowsChanged(firstChanged: Int, oldSize: Int) {
            rowsChanged += firstChanged to oldSize
            if (failOnRows) throw IllegalStateException("rowsChanged failed")
        }
        var levelMappingChanged = 0
        override fun levelMappingChanged() {
            levelMappingChanged++
        }
        var histogramChanged = 0
        override fun histogramChanged() {
            histogramChanged++
        }
        var foundChanged = 0
        override fun foundChanged() {
            foundChanged++
        }
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
    private suspend fun awaitShown(session: LogSession, file: Path, filter: Filter) =
        awaitShown(session, listOf(file), filter)

    private suspend fun awaitShown(
        session: LogSession,
        files: List<Path>,
        filter: Filter,
    ): List<Int> = withTimeout(10_000) {
        var done = false
        var expected = emptyList<Int>()
        while (!done) {
            delay(50)
            withContext(ui) {
                val fresh = session.loaded!!.newFilter()
                // run() frees the UI thread, so the session may read appended data meanwhile: only a result
                // covering all of it counts.
                val result = fresh.prepare(filter).run()
                expected = result.rows.toList()
                val size = files.sumOf { Files.size(it) }
                val rows = session.shown?.takeIf { it.isFiltered }
                val shown = rows?.let { List(it.size) { row -> it.recordAt(row) } }
                done = result.indexedSize == size && session.status.size == size && shown == expected &&
                    session.status.shown == expected.size && !session.status.filtering
            }
        }
        expected
    }

    @Test
    fun `a merge uses the parsers chosen for its files and detects the others`() = runBlocking {
        val a = Files.writeString(dir.resolve("a.log"), lines(10))
        val b = Files.writeString(dir.resolve("b.log"), lines(10))
        val pattern = Parser.Pattern("Mine", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n")
        val recorder = Recorder()
        val session = withContext(ui) {
            LogSession(listOf(a, b), scope, NoSettings, recorder, chosen = listOf(null, pattern)).also { it.start() }
        }
        val shown = withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertEquals(listOf(null, pattern), session.chosenParsers)
            assertEquals(listOf(Parser.BUILT_IN[0], pattern), session.parsers)
            assertEquals(20, shown.size)
            session.useParsers(listOf(pattern, null))
        }
        withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertEquals(listOf(pattern, null), session.chosenParsers)
            assertEquals(listOf(pattern, Parser.BUILT_IN[0]), session.parsers)
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
    fun `a listener failing on changed rows leaves the session's own state complete`() = runBlocking {
        val file = Files.writeString(dir.resolve("failing.log"), lines(1000))
        val recorder = Recorder()
        val (session, _) = open(file, recorder)
        recorder.failOnRows = true
        val warnOrWorse = LevelCondition.atLeast("Level", Level.WARN)
        // A filter result, appended records it catches up with, and showing all records again.
        withContext(ui) { session.applyFilter(Level.WARN, Query.EMPTY) }
        assertEquals(500, awaitShown(session, file, warnOrWorse).size)
        awaitHistogram(session, 500, 250, 250)
        append(file, lines(100))
        assertEquals(550, awaitShown(session, file, warnOrWorse).size)
        awaitHistogram(session, 550, 275, 275)
        withContext(ui) {
            session.applyFilter(null, Query.EMPTY)
            assertEquals(null to 1100, session.status.shown to session.status.records)
        }
        awaitHistogram(session, 1100, 275, 275)
        // Appended records without a filter: the session keeps following the file.
        append(file, lines(100))
        awaitHistogram(session, 1200, 300, 300)
        append(file, lines(100))
        awaitHistogram(session, 1300, 325, 325)
        withContext(ui) { assertEquals(1300, session.status.records) }
        assertTrue(uncaught.isNotEmpty() && uncaught.all { it is IllegalStateException }, uncaught.toString())
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
    private suspend fun awaitHistogram(
        session: LogSession,
        records: Int,
        warnings: Int,
        errors: Int,
    ) = withTimeout(10_000) {
        while (true) {
            val counts = withContext(ui) {
                session.histogram?.let { h ->
                    IntArray(TimeHistogram.CATEGORIES) { c -> (h.firstBin..h.lastBin).sumOf { h.count(c, it) } }
                }
            }
            val reached = counts != null && counts.sum() == records &&
                counts[TimeHistogram.WARN] == warnings && counts[TimeHistogram.ERROR] == errors
            if (reached) return@withTimeout
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

    /** The records [shown] found, in order. */
    private fun found(shown: ShownLog): List<Int> {
        var record = -1
        return List(shown.foundCount) { i ->
            record = shown.nextFound(record, forward = true, inclusive = i == 0)
            record
        }
    }

    /**
     * Waits until the session has read all of [files] and found what a fresh evaluation of [search] among
     * the records [filter] lets through gives; the found records are shown rows all the while.
     */
    private suspend fun awaitFound(
        session: LogSession,
        files: List<Path>,
        filter: Filter?,
        search: TextSearch,
    ): List<Int> =
        withTimeout(10_000) {
            var done = false
            var expected = emptyList<Int>()
            while (!done) {
                delay(50)
                withContext(ui) {
                    val shown = session.shown!!
                    assertFoundShown(shown)
                    val fresh = session.loaded!!.newFilter()
                    val condition = search.condition()
                    val result = fresh.prepare(filter?.let { Filter.And(listOf(it, condition)) } ?: condition).run()
                    expected = result.rows.toList()
                    val size = files.sumOf { Files.size(it) }
                    done = result.indexedSize == size && session.status.size == size &&
                        !shown.isSearching && found(shown) == expected
                }
            }
            expected
        }

    private fun assertFoundShown(shown: ShownLog) {
        for (record in found(shown)) {
            val row = shown.rowOf(record)
            assertTrue(row >= 0 && shown.recordAt(row) == record, "Found record $record is not shown")
        }
    }

    @Test
    fun `a search finds records among those the filter lets through`() = runBlocking {
        val file = Files.writeString(dir.resolve("search.log"), lines(20))
        val recorder = Recorder()
        val (session, shown) = open(file, recorder)
        val search = TextSearch("MESSAGE 1")
        withContext(ui) {
            session.applyFilter(Level.WARN, Query.EMPTY)
            session.search(search)
        }
        val found = awaitFound(session, listOf(file), LevelCondition.atLeast("Level", Level.WARN), search)
        assertEquals(listOf(1, 11, 13, 15, 17, 19), found)
        withContext(ui) {
            assertEquals(2, shown.foundPosition(11))
            assertEquals(0, shown.foundPosition(12))
            assertEquals(1, shown.nextFound(19, forward = true, inclusive = false))
            assertTrue(recorder.foundChanged > 0)
            session.search(null)
            assertEquals(0, shown.foundCount)
        }
    }

    @Test
    fun `an invalid regex is reported and finds nothing`() = runBlocking {
        val file = Files.writeString(dir.resolve("regex.log"), lines(20))
        val (session, shown) = open(file, Recorder())
        withContext(ui) { session.applyFilter(null, Query.EMPTY) }
        awaitFound(session, listOf(file), null, TextSearch("message 1").also { withContext(ui) { session.search(it) } })
        withContext(ui) {
            assertFailsWith<QueryException> { session.search(TextSearch("(", regex = true)) }
            assertEquals(0, shown.foundCount)
            assertFalse(shown.isSearching)
        }
    }

    @Test
    fun `a search follows appended records, with and without a filter, also in a merge`() = runBlocking {
        val warnOrWorse = LevelCondition.atLeast("Level", Level.WARN)
        val search = TextSearch("message 1")
        for ((merged, filter) in listOf(false to null, false to warnOrWorse, true to null, true to warnOrWorse)) {
            val a = Files.writeString(dir.resolve("a-$merged-${filter != null}.log"), lines(200))
            val files = if (merged) {
                listOf(a, Files.writeString(dir.resolve("b-${filter != null}.log"), lines(100)))
            } else {
                listOf(a)
            }
            val (session, _) = open(files, Recorder())
            withContext(ui) {
                session.applyFilter(if (filter != null) Level.WARN else null, Query.EMPTY)
                session.search(search)
            }
            awaitFound(session, files, filter, search)
            repeat(4) { round ->
                for (file in files) {
                    // A record whose level and message only arrive with the next batch.
                    append(file, lines(50) + if (round % 2 == 0) "2026-10-01 08:00:00.000 [main] WA" else "")
                }
                delay(300)
                if (round % 2 == 0) for (file in files) append(file, "RN  app.L - message 1 completed\n")
                awaitFound(session, files, filter, search)
            }
            withContext(ui) { session.dispose() }
        }
    }

    /** Records each search prepared and from which record on; filters without the search wait for [gate]. */
    private class Recording(private val engine: RecordFilter, private val search: Filter) : RecordFilter by engine {
        val searchedFrom = ArrayList<Int>()
        var gate = CompletableDeferred(Unit)
        var searchFails = false

        override fun prepare(filter: Filter, from: Int): PreparedFilter {
            val prepared = engine.prepare(filter, from)
            if (filter == search || (filter is Filter.And && search in filter.parts)) {
                searchedFrom += from
                if (!searchFails) return prepared
                return object : PreparedFilter by prepared {
                    override suspend fun run(): FilterResult = throw IllegalStateException("Disk on fire")
                }
            }
            val gate = gate
            return object : PreparedFilter by prepared {
                override suspend fun run(): FilterResult {
                    gate.await()
                    return prepared.run()
                }
            }
        }
    }

    private suspend fun openRecording(file: Path, search: TextSearch): Triple<LogSession, ShownLog, Recording> {
        var recording: Recording? = null
        val recorder = Recorder()
        val session = withContext(ui) {
            LogSession(listOf(file), scope, NoSettings, recorder, { null }, emptyList()) { loaded ->
                Recording(loaded.newFilter(), search.condition()).also { recording = it }
            }.also { it.start() }
        }
        val shown = withTimeout(10_000) { recorder.loads.receive() }
        return Triple(session, shown, recording!!)
    }

    @Test
    fun `only appended records are searched again`() = runBlocking {
        val file = Files.writeString(dir.resolve("again.log"), lines(1000))
        val search = TextSearch("message 1")
        val (session, _, recording) = openRecording(file, search)
        withContext(ui) {
            session.applyFilter(null, Query.EMPTY)
            session.search(search)
        }
        awaitFound(session, listOf(file), null, search)
        append(file, lines(100))
        awaitFound(session, listOf(file), null, search)
        withContext(ui) {
            assertEquals(0, recording.searchedFrom.first())
            // The last record may have grown, so it is searched again with the appended ones.
            assertEquals(listOf(999), recording.searchedFrom.drop(1).distinct())
        }
    }

    @Test
    fun `a failed search tells why and stops following the rows until the next one`() = runBlocking {
        val file = Files.writeString(dir.resolve("failed.log"), lines(100))
        val search = TextSearch("message 1")
        val (session, shown, recording) = openRecording(file, search)
        withContext(ui) {
            session.applyFilter(null, Query.EMPTY)
            recording.searchFails = true
            session.search(search)
        }
        withTimeout(10_000) { while (withContext(ui) { session.searchError } == null) delay(20) }
        withContext(ui) {
            assertEquals("Disk on fire", session.searchError)
            assertFalse(shown.isSearching)
            assertEquals(0, shown.foundCount)
        }
        append(file, lines(10))
        withTimeout(10_000) { while (withContext(ui) { shown.size } < 110) delay(50) }
        delay(600)
        withContext(ui) {
            assertEquals(listOf(0), recording.searchedFrom)
            recording.searchFails = false
            session.search(search)
            assertEquals(null, session.searchError)
        }
        assertEquals(21, awaitFound(session, listOf(file), null, search).size)
    }

    @Test
    fun `while a new filter runs, the found records are among the shown rows`() = runBlocking {
        val file = Files.writeString(dir.resolve("running.log"), lines(1000))
        val search = TextSearch("message 1")
        val (session, shown, recording) = openRecording(file, search)
        val warnOrWorse = LevelCondition.atLeast("Level", Level.WARN)
        withContext(ui) {
            session.applyFilter(Level.WARN, Query.EMPTY)
            session.search(search)
        }
        awaitFound(session, listOf(file), warnOrWorse, search)
        val gate = CompletableDeferred<Unit>()
        withContext(ui) {
            recording.gate = gate
            session.applyFilter(Level.ERROR, Query.EMPTY)
            // A search started meanwhile is among the rows of the filter shown, too.
            session.search(TextSearch("message 1"))
        }
        val shownWhileFiltering = awaitFound(session, listOf(file), warnOrWorse, search)
        // The shown rows wait for the filter before they take in appended records, and so do the found ones.
        append(file, lines(100))
        delay(1000)
        withContext(ui) {
            assertFoundShown(shown)
            assertEquals(shownWhileFiltering, found(shown))
        }
        gate.complete(Unit)
        val error = awaitFound(session, listOf(file), LevelCondition.atLeast("Level", Level.ERROR), search)
        withContext(ui) { assertFoundShown(shown) }
        assertTrue(error.size < shownWhileFiltering.size)
    }

    @Test
    fun `a reload clears the found records and finds them again`() = runBlocking {
        val file = Files.writeString(dir.resolve("found-reload.log"), lines(100))
        val recorder = Recorder()
        val (session, first) = open(file, recorder)
        val search = TextSearch("message 1")
        withContext(ui) {
            session.applyFilter(null, Query.EMPTY)
            session.search(search)
        }
        assertEquals(11, awaitFound(session, listOf(file), null, search).size)
        Files.writeString(file, lines(30))
        val second = withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertEquals(0, first.foundCount)
            assertEquals(-1, first.nextFound(0, forward = true, inclusive = true))
            session.applyFilter(null, Query.EMPTY)
        }
        // Messages 100 to 129.
        assertEquals(30, awaitFound(session, listOf(file), null, search).size)
        withContext(ui) { assertEquals(30, second.foundCount) }
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
        val bWarnOrWorse = Filter.And(
            listOf(LevelCondition.atLeast("Level", Level.WARN), TextCondition("Source", TextOp.EQUALS, "b.log")),
        )
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
        fun json(n: Int, extra: String = "") =
            """{"timestamp":"2026-10-01T08:00:0$n.000Z","message":"m$n"$extra}""" + "\n"
        for (merged in listOf(false, true)) {
            val events = Files.writeString(dir.resolve("events-$merged.json"), json(1) + json(2))
            val files = if (merged) {
                listOf(events, Files.writeString(dir.resolve("a.log"), lines(5)))
            } else {
                listOf(events)
            }
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

    private val mine = Parser.Pattern("Mine", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n")

    // Without the logger, its name becomes part of the message.
    private val edited = Parser.Pattern("Mine", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %m%n")

    private suspend fun openWith(
        settings: SettingsStore,
        file: Path,
        recorder: Recorder,
        chosen: Parser?,
    ): Pair<LogSession, ShownLog> {
        val session = withContext(ui) {
            LogSession(listOf(file), scope, settings, recorder, chosen = listOf(chosen)).also { it.start() }
        }
        return session to withTimeout(10_000) { recorder.loads.receive() }
    }

    @Test
    fun `a saved parser renamed only changes the name shown`() = runBlocking {
        val file = Files.writeString(dir.resolve("renamed.log"), lines(10))
        val settings = ChangingSettings(Settings(parsers = listOf(mine)))
        val recorder = Recorder()
        val (session, shown) = openWith(settings, file, recorder, chosen = mine)
        val announced = withContext(ui) { recorder.parserChanged }
        withContext(ui) { settings.change(Settings(parsers = listOf(mine.copy(name = "Ours")))) }
        withContext(ui) {
            assertEquals("Ours", session.chosenParser?.name)
            assertEquals(announced + 1, recorder.parserChanged)
            assertFalse(shown.ended)
        }
        assertEquals(null, withTimeoutOrNull(500) { recorder.loads.receive() })
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `a saved parser edited reads the files again`() = runBlocking {
        val file = Files.writeString(dir.resolve("edited.log"), lines(10))
        val settings = ChangingSettings(Settings(parsers = listOf(mine)))
        val recorder = Recorder()
        val (session, shown) = openWith(settings, file, recorder, chosen = mine)
        withContext(ui) { settings.change(Settings(parsers = listOf(edited))) }
        withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertTrue(shown.ended)
            assertEquals(edited, session.chosenParser)
            assertEquals("app.L - message 0", session.records!!.fieldText(0, 3))
            session.dispose()
        }
    }

    @Test
    fun `a saved parser that detection chose reads the files again when edited`() = runBlocking {
        val file = Files.writeString(dir.resolve("detected.log"), lines(10))
        val settings = ChangingSettings(Settings(parsers = listOf(mine)))
        val recorder = Recorder()
        val (session, shown) = openWith(settings, file, recorder, chosen = null)
        assertEquals(mine, withContext(ui) { session.parser })
        withContext(ui) { settings.change(Settings(parsers = listOf(edited))) }
        withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertTrue(shown.ended)
            assertEquals(null, session.chosenParser)
            assertEquals(edited, session.parser)
            assertEquals("app.L - message 0", session.records!!.fieldText(0, 3))
            session.dispose()
        }
    }

    private fun withNotes(count: Int) = lines(100) + buildString {
        repeat(count) { append("2026-10-01 08:00:00.000 [main] NOTE  app.L - note $it\n") }
    }

    private val notesAreErrors = Settings(levels = LevelMapping(Level.BUILT_IN_NAMES + ("NOTE" to Level.ERROR)))

    @Test
    fun `a changed level mapping filters again`() = runBlocking {
        val file = Files.writeString(dir.resolve("levels.log"), withNotes(10))
        val settings = ChangingSettings(Settings())
        val recorder = Recorder()
        val (session, _) = openWith(settings, file, recorder, chosen = null)
        withContext(ui) { session.applyFilter(Level.ERROR, Query.EMPTY) }
        val errors = LevelCondition.atLeast("Level", Level.ERROR)
        assertEquals(25, awaitShown(session, file, errors).size)
        withContext(ui) { settings.change(notesAreErrors) }
        assertEquals(35, awaitShown(session, file, errors).size)
        assertEquals(1, withContext(ui) { recorder.levelMappingChanged })
    }

    @Test
    fun `a changed level mapping counts the times again without a filter`() = runBlocking {
        val file = Files.writeString(dir.resolve("levels.log"), withNotes(10))
        val settings = ChangingSettings(Settings())
        val recorder = Recorder()
        val (session, _) = openWith(settings, file, recorder, chosen = null)
        withContext(ui) { session.applyFilter(null, Query.EMPTY) }
        awaitHistogram(session, 110, 25, 25)
        val counted = withContext(ui) { recorder.histogramChanged }
        withContext(ui) { settings.change(notesAreErrors) }
        awaitHistogram(session, 110, 25, 35)
        assertTrue(withContext(ui) { recorder.histogramChanged } > counted)
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
        withContext(ui) { session.useParser(Parser.BUILT_IN.first()) }
        val title = withTimeout(10_000) { recorder.problem.await() }
        assertTrue("does not match" in title, title)
    }

    @Test
    fun `a merge names the file its parser does not fit`() = runBlocking {
        val files = (1..4).map { Files.writeString(dir.resolve("part-$it.log"), lines(100)) }
        val other = Files.writeString(dir.resolve("other.log"), "plain text\n".repeat(20))
        val recorder = Recorder()
        withContext(ui) {
            val chosen = List(4) { null } + Parser.BUILT_IN.first()
            LogSession(files + listOf(other), scope, NoSettings, recorder, chosen = chosen).start()
        }
        val title = withTimeout(10_000) { recorder.problem.await() }
        assertTrue(title.startsWith("other.log does not match"), title)
    }
}
