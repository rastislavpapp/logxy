package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.LogCapture
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.filter.ChunkListener
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterResult
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.Narrowing
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.parse.ParserMismatchException
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
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.slf4j.event.Level as LogLevel

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

    @Test
    fun `counts a variable's values in all records, not only the shown rows, and again once records are appended`() =
        runBlocking {
            fun took(level: String, ms: Int) = "2026-10-01 08:00:00.000 [main] $level  app.L - took $ms ms\n"
            val file = Files.writeString(dir.resolve("values.log"), took("WARN", 1) + took("INFO", 2) + took("INFO", 2))
            val (session, _) = open(file, Recorder())
            withContext(ui) { session.applyFilter(Level.WARN, Query.EMPTY) }
            awaitShown(session, file, LevelCondition.atLeast("Level", Level.WARN))
            fun counts() = runBlocking(ui) {
                val values = session.allVariableValues("took {} ms", 0)!!
                values.values.indices.associate { values.values[it] to values.count(it) }
            }
            assertEquals(mapOf("2" to 2, "1" to 1), counts())
            append(file, took("INFO", 3))
            withTimeout(10_000) {
                while (counts().size < 3) delay(50)
            }
            assertEquals(mapOf("2" to 2, "1" to 1, "3" to 1), counts())
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

    @Test
    fun `a search with more text typed on gives what a fresh search gives`() = runBlocking {
        val warnOrWorse = LevelCondition.atLeast("Level", Level.WARN)
        // Typed on, deleted back, and with an option changed, with records appended meanwhile.
        val steps = listOf(
            TextSearch("message 1"),
            TextSearch("message 12"),
            TextSearch("message 123"),
            TextSearch("message 12"),
            TextSearch("message 12", matchCase = true),
            TextSearch("message 120", matchCase = true),
            TextSearch("MESSAGE 1203", matchCase = false),
        )
        for ((merged, filter) in listOf(false to null, false to warnOrWorse, true to null, true to warnOrWorse)) {
            val a = Files.writeString(dir.resolve("narrow-a-$merged-${filter != null}.log"), lines(300))
            val files = if (merged) {
                listOf(a, Files.writeString(dir.resolve("narrow-b-${filter != null}.log"), lines(200)))
            } else {
                listOf(a)
            }
            val (session, _) = open(files, Recorder())
            withContext(ui) { session.applyFilter(if (filter != null) Level.WARN else null, Query.EMPTY) }
            for (search in steps) {
                withContext(ui) { session.search(search) }
                awaitFound(session, files, filter, search)
                for (file in files) append(file, lines(20))
            }
            withContext(ui) { session.dispose() }
        }
    }

    @Test
    fun `text typed on while a search runs narrows it once it is done`() = runBlocking {
        val file = Files.writeString(dir.resolve("typed-on.log"), lines(1000))
        val search = TextSearch("message 1")
        val (session, _, recording) = openRecording(file, search)
        val gate = CompletableDeferred<Unit>()
        withContext(ui) {
            session.applyFilter(null, Query.EMPTY)
            recording.searchGate = gate
            session.search(search)
            session.search(TextSearch("message 12"))
            session.search(TextSearch("message 123"))
            assertTrue(session.shown!!.isSearching)
        }
        gate.complete(Unit)
        val typed = TextSearch("message 123")
        assertEquals(1, awaitFound(session, listOf(file), null, typed).size)
        withContext(ui) {
            // Only the first search read all records; the rest looked among what it found.
            assertEquals(listOf(0), recording.searchedFrom)
            assertEquals(1, recording.narrowedFrom.size)
        }
    }

    @Test
    fun `typing on keeps the found records shown until the narrowed ones replace them`() = runBlocking {
        val file = Files.writeString(dir.resolve("kept.log"), lines(1000))
        val search = TextSearch("message 1")
        val (session, _, recording) = openRecording(file, search)
        withContext(ui) {
            session.applyFilter(null, Query.EMPTY)
            session.search(search)
        }
        val before = awaitFound(session, listOf(file), null, search).size
        withContext(ui) {
            session.search(TextSearch("message 12"))
            assertTrue(session.searchNarrows)
            assertTrue(session.shown!!.isSearching)
            assertEquals(before, session.shown!!.foundCount)
        }
        assertEquals(11, awaitFound(session, listOf(file), null, TextSearch("message 12")).size)
        withContext(ui) {
            assertFalse(session.searchNarrows)
            assertEquals(1, recording.narrowedFrom.size)
        }
    }

    /** Records each search prepared and from which record on; filters without the search wait for [gate]. */
    private class Recording(private val engine: RecordFilter, private val search: Filter) : RecordFilter by engine {
        val searchedFrom = ArrayList<Int>()

        /** The limit of each run that only looked among earlier found records. */
        val narrowedFrom = ArrayList<Int>()
        var gate = CompletableDeferred(Unit)
        var searchGate = CompletableDeferred(Unit)
        var searchFails = false

        override fun prepare(filter: Filter, from: Int, within: Narrowing?): PreparedFilter {
            val prepared = engine.prepare(filter, from, within)
            if (within != null) narrowedFrom += within.upTo
            if (filter == search || (filter is Filter.And && search in filter.parts)) {
                searchedFrom += from
                val searchGate = searchGate
                return object : PreparedFilter by prepared {
                    override suspend fun run(onChunk: ChunkListener?): FilterResult {
                        searchGate.await()
                        if (searchFails) throw IllegalStateException("Disk on fire")
                        return prepared.run(onChunk)
                    }
                }
            }
            val gate = gate
            return object : PreparedFilter by prepared {
                override suspend fun run(onChunk: ChunkListener?): FilterResult {
                    gate.await()
                    return prepared.run(onChunk)
                }
            }
        }
    }

    /**
     * Stands in for the engine's search over all records: reports the chunks the test gives with [chunk] and the
     * result with [finish]. Searches of appended records are the engine's own.
     */
    private class Scripted(private val engine: RecordFilter, private val search: Filter) : RecordFilter by engine {
        private val listener = CompletableDeferred<ChunkListener?>()
        private val result = CompletableDeferred<IntArray>()
        var recordCount = 0

        override fun prepare(filter: Filter, from: Int, within: Narrowing?): PreparedFilter {
            val prepared = engine.prepare(filter, from, within)
            if (from > 0 || !(filter == search || (filter is Filter.And && search in filter.parts))) return prepared
            return object : PreparedFilter by prepared {
                override val progress = 0.5

                override suspend fun run(onChunk: ChunkListener?): FilterResult {
                    listener.complete(onChunk)
                    val rows = result.await()
                    return FilterResult(rows, 0, recordCount, Files.size(file))
                }
            }
        }

        lateinit var file: Path

        /** Reports the matches among the records `[first, end)` from a thread of its own, as the engine does. */
        suspend fun chunk(first: Int, end: Int, vararg records: Int) {
            val onChunk = listener.await() ?: error("The search reports no chunks")
            Thread { onChunk.chunkDone(first, end, records) }.apply { start() }.join()
        }

        /** Ends the search with all [records] found. */
        fun finish(vararg records: Int) {
            result.complete(records)
        }
    }

    private suspend fun openScripted(file: Path, search: TextSearch): Triple<LogSession, ShownLog, Scripted> {
        var scripted: Scripted? = null
        val recorder = Recorder()
        val session = withContext(ui) {
            LogSession(listOf(file), scope, NoSettings, recorder, { null }, emptyList(), null) { loaded ->
                Scripted(loaded.newFilter(), search.condition()).also {
                    it.file = file
                    scripted = it
                }
            }.also { it.start() }
        }
        val shown = withTimeout(10_000) { recorder.loads.receive() }
        val engine = scripted!!
        engine.recordCount = withContext(ui) { session.records!!.recordCount }
        return Triple(session, shown, engine)
    }

    /** Runs [check] on the UI thread until it holds: the session handles a chunk reported on another thread there. */
    private suspend fun awaitUi(check: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(ui) { check() }) delay(10)
    }

    /** [count] lines from number [first] on, whose records are one second apart. */
    private fun secondLines(count: Int, first: Int = 0) = buildString {
        for (i in first..<first + count) {
            append("2026-10-01 08:%02d:%02d.000 [main] INFO  app.L - message %d\n".format(i / 60, i % 60, i))
        }
    }

    /** Matches of the histogram [of] in the records `[first, last]`, by their times. */
    private fun ShownLog.inHistogram(of: TimeHistogram, first: Int, last: Int): Int {
        val from = timestamp(first)!!
        val to = timestamp(last)!! + 1
        return (of.firstBin..of.lastBin).filter { of.binStart(it) in from..<to }.sumOf { bin ->
            (0..<TimeHistogram.CATEGORIES).sumOf { of.count(it, bin) }
        }
    }

    @Test
    fun `a search reports matches chunk by chunk and never skips one that is not decided`() = runBlocking {
        val file = Files.writeString(dir.resolve("chunks.log"), secondLines(100))
        val search = TextSearch("message")
        val (session, shown, scripted) = openScripted(file, search)
        withContext(ui) { session.search(search) }
        // The last chunk arrives first.
        scripted.chunk(80, 100, 85)
        awaitUi { shown.foundCount == 1 }
        withContext(ui) {
            assertTrue(shown.isSearching)
            assertEquals(0, shown.foundPosition(85), "records before it are not searched")
            assertEquals(NOT_YET_FOUND, shown.nextFound(10, forward = true, inclusive = false))
        }
        scripted.chunk(0, 40, 5, 7)
        awaitUi { shown.foundCount == 3 }
        withContext(ui) {
            assertEquals(1, shown.foundPosition(5))
            assertEquals(0, shown.foundPosition(85))
            // The first match from the current record on is certain, as nothing between can match.
            assertEquals(5, shown.nextFound(3, forward = true, inclusive = true))
            assertEquals(7, shown.nextFound(5, forward = true, inclusive = false))
            // Records 40 to 79 may hold a match between 7 and 85.
            assertEquals(NOT_YET_FOUND, shown.nextFound(7, forward = true, inclusive = false))
            assertEquals(NOT_YET_FOUND, shown.nextFound(85, forward = false, inclusive = false))
        }
        scripted.chunk(40, 80, 50)
        awaitUi { shown.foundCount == 4 }
        withContext(ui) {
            assertEquals(50, shown.nextFound(7, forward = true, inclusive = false))
            assertEquals(4, shown.foundPosition(85))
        }
        scripted.finish(5, 7, 50, 85)
        awaitUi { !shown.isSearching }
        withContext(ui) {
            assertEquals(4, shown.foundCount)
            assertEquals(50, shown.nextFound(7, forward = true, inclusive = false))
        }
    }

    @Test
    fun `next and previous wrap around only once nothing can still match`() = runBlocking {
        val file = Files.writeString(dir.resolve("wrap.log"), secondLines(100))
        val search = TextSearch("message")
        val (session, shown, scripted) = openScripted(file, search)
        withContext(ui) { session.search(search) }
        scripted.chunk(80, 100, 85)
        scripted.chunk(0, 40)
        awaitUi { shown.foundCount == 1 }
        withContext(ui) {
            // Records 40 to 79 are undecided: a match there would come before 85 after wrapping.
            assertEquals(NOT_YET_FOUND, shown.nextFound(85, forward = true, inclusive = false))
            assertEquals(NOT_YET_FOUND, shown.nextFound(85, forward = false, inclusive = false))
            // Wrapping backwards from the start, nothing after 85 is left to match.
            assertEquals(85, shown.nextFound(10, forward = false, inclusive = false))
        }
        scripted.chunk(40, 80, 60)
        awaitUi { shown.foundCount == 2 }
        withContext(ui) {
            assertEquals(60, shown.nextFound(85, forward = true, inclusive = false))
            assertEquals(85, shown.nextFound(10, forward = false, inclusive = false))
            assertEquals(85, shown.nextFound(60, forward = true, inclusive = false))
        }
    }

    @Test
    fun `a search that is nowhere else found wraps to its only match`() = runBlocking {
        val file = Files.writeString(dir.resolve("only.log"), secondLines(100))
        val search = TextSearch("message")
        val (session, shown, scripted) = openScripted(file, search)
        withContext(ui) { session.search(search) }
        scripted.chunk(0, 50, 20)
        scripted.chunk(50, 100)
        awaitUi { shown.foundCount == 1 && shown.foundPosition(20) == 1 }
        withContext(ui) {
            assertEquals(20, shown.nextFound(20, forward = true, inclusive = false))
            assertEquals(20, shown.nextFound(70, forward = true, inclusive = false))
            assertEquals(20, shown.nextFound(70, forward = false, inclusive = false))
        }
    }

    @Test
    fun `the found histogram fills in with the chunks and stays when the search ends`() = runBlocking {
        val file = Files.writeString(dir.resolve("found-histogram.log"), secondLines(100))
        val search = TextSearch("message")
        val (session, shown, scripted) = openScripted(file, search)
        withContext(ui) { session.search(search) }
        assertEquals(null, withContext(ui) { session.foundHistogram?.takeIf { !it.isEmpty } })
        scripted.chunk(80, 100, 85, 86, 90)
        awaitUi { session.foundHistogram?.isEmpty == false }
        withContext(ui) {
            val histogram = session.foundHistogram!!
            assertEquals(3, shown.inHistogram(histogram, 80, 99))
            assertEquals(0, shown.inHistogram(histogram, 0, 79))
        }
        scripted.chunk(0, 40, 5)
        awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, 39) } == 1 }
        scripted.finish(5, 85, 86, 90)
        awaitUi { !shown.isSearching }
        withContext(ui) { assertEquals(4, shown.inHistogram(session.foundHistogram!!, 0, 99)) }
        // A new search clears it at once.
        withContext(ui) {
            session.search(TextSearch("nothing"))
            assertEquals(null, session.foundHistogram?.takeIf { !it.isEmpty })
        }
    }

    @Test
    fun `a search that reports no chunks still gets its histogram`() = runBlocking {
        val file = Files.writeString(dir.resolve("all-at-once.log"), secondLines(100))
        val search = TextSearch("message")
        val (session, shown, scripted) = openScripted(file, search)
        withContext(ui) { session.search(search) }
        scripted.finish(3, 4, 99)
        awaitUi { !shown.isSearching && session.foundHistogram?.isEmpty == false }
        withContext(ui) { assertEquals(3, shown.inHistogram(session.foundHistogram!!, 0, 99)) }
    }

    @Test
    fun `the found histogram follows appended records`() = runBlocking {
        val file = Files.writeString(dir.resolve("follow-histogram.log"), secondLines(100))
        val search = TextSearch("message 1")
        val (session, shown) = open(file, Recorder())
        withContext(ui) { session.search(search) }
        awaitFound(session, listOf(file), null, search)
        // Messages 1 and 10 to 19.
        awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, 99) } == 11 }
        append(file, secondLines(100, first = 100))
        val expected = awaitFound(session, listOf(file), null, search)
        awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, 199) } == expected.size }
        // The last record is searched again with what is appended: its match must not be counted twice.
        append(file, secondLines(100, first = 200))
        val more = awaitFound(session, listOf(file), null, search)
        awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, 299) } == more.size }
    }

    @Test
    fun `the found histogram counts the narrowed records, with a filter and in a merge`() = runBlocking {
        for ((merged, level) in listOf(false to Level.INFO, true to null, true to Level.INFO)) {
            val a = Files.writeString(dir.resolve("narrow-hist-a-$merged-$level.log"), secondLines(100))
            val files = if (merged) {
                listOf(a, Files.writeString(dir.resolve("narrow-hist-b-$level.log"), secondLines(100, first = 100)))
            } else {
                listOf(a)
            }
            val (session, shown) = open(files, Recorder())
            withContext(ui) {
                session.applyFilter(level, Query.EMPTY)
                session.search(TextSearch("message 1"))
            }
            awaitFound(session, files, null, TextSearch("message 1"))
            for (text in listOf("message 12", "message 123")) {
                val search = TextSearch(text)
                withContext(ui) { session.search(search) }
                val expected = awaitFound(session, files, null, search)
                val last = if (merged) 199 else 99
                awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, last) } == expected.size }
            }
            withContext(ui) { session.dispose() }
        }
    }

    @Test
    fun `a narrowing search keeps the found histogram and no match is given until it is done`() = runBlocking {
        val file = Files.writeString(dir.resolve("narrow-histogram.log"), secondLines(100))
        val (session, shown) = open(file, Recorder())
        withContext(ui) { session.search(TextSearch("message 1")) }
        awaitFound(session, listOf(file), null, TextSearch("message 1"))
        awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, 99) } == 11 }
        val before = withContext(ui) { session.foundHistogram }
        withContext(ui) {
            session.search(TextSearch("message 12"))
            // The records of the search before stay, but only for show.
            assertTrue(session.searchNarrows)
            assertSame(before, session.foundHistogram)
            assertEquals(11, shown.foundCount)
            assertEquals(NOT_YET_FOUND, shown.nextFound(0, forward = true, inclusive = true))
        }
        awaitUi { !shown.isSearching }
        withContext(ui) {
            assertEquals(1, shown.foundCount)
            assertEquals(12, shown.nextFound(0, forward = true, inclusive = true))
            assertEquals(1, shown.inHistogram(session.foundHistogram!!, 0, 99))
        }
        // The narrowed records are followed on like any: the histogram counts appended matches, once.
        append(file, secondLines(100, first = 100))
        val typed = TextSearch("message 12")
        val expected = awaitFound(session, listOf(file), null, typed)
        awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, 199) } == expected.size }
        // And typing on again narrows what was found since.
        withContext(ui) { session.search(TextSearch("message 120")) }
        val narrowed = awaitFound(session, listOf(file), null, TextSearch("message 120"))
        awaitUi { session.foundHistogram?.let { shown.inHistogram(it, 0, 199) } == narrowed.size }
    }

    private suspend fun openRecording(file: Path, search: TextSearch): Triple<LogSession, ShownLog, Recording> {
        var recording: Recording? = null
        val recorder = Recorder()
        val session = withContext(ui) {
            LogSession(listOf(file), scope, NoSettings, recorder, { null }, emptyList(), null) { loaded ->
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
        val warning = LogCapture.logged(LogLevel.WARN, "Cannot search $file").single()
        assertEquals("Disk on fire", warning.thrown?.message)
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
    fun `a file said to have grown is read at once, not at the next poll`() = runBlocking {
        val file = Files.writeString(dir.resolve("pushed.log"), lines(10))
        // Old, so the session sees a finished file and polls it at the slow pace, which starts with the load: a poll
        // is still far off when the push comes right after it.
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(1))))
        val (session, shown) = open(file, Recorder())
        append(file, lines(5))
        session.grew()
        val read = withTimeoutOrNull(1_000) { while (withContext(ui) { shown.size } < 15) delay(20) }
        assertNotNull(read)
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
    fun `a file emptied and written again is read again`() = runBlocking {
        val file = Files.writeString(dir.resolve("cleared.log"), lines(100))
        val recorder = Recorder()
        val (session, _) = open(file, recorder)
        Files.writeString(file, "")
        session.grew()
        val empty = withTimeout(10_000) { recorder.loads.receive() }
        assertEquals(0, withContext(ui) { empty.size })
        append(file, lines(3))
        session.grew()
        val read = withTimeoutOrNull(5_000) { while (withContext(ui) { session.shown?.size } != 3) delay(20) }
        assertNotNull(read)
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

    @Test
    fun `renamed columns of a saved parser take effect without reading the files again`() = runBlocking {
        val file = Files.writeString(dir.resolve("aliases.log"), lines(10))
        val settings = ChangingSettings(Settings(parsers = listOf(mine)))
        val recorder = Recorder()
        val (chosenSession, chosenShown) = openWith(settings, file, recorder, chosen = mine)
        val (detectedSession, detectedShown) = openWith(settings, file, Recorder(), chosen = null)
        val renamed = mine.withAliases(mapOf("Logger" to "Class"))
        val announced = withContext(ui) { recorder.parserChanged }
        withContext(ui) { settings.change(Settings(parsers = listOf(renamed))) }
        withContext(ui) {
            assertEquals(renamed, chosenSession.chosenParser)
            assertEquals(renamed, chosenSession.parser)
            assertEquals(renamed, detectedSession.parser)
            assertEquals(announced + 1, recorder.parserChanged)
            assertFalse(chosenShown.ended)
            assertFalse(detectedShown.ended)
        }
        assertEquals(null, withTimeoutOrNull(500) { recorder.loads.receive() })
        withContext(ui) {
            chosenSession.dispose()
            detectedSession.dispose()
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
            val copy = async(start = CoroutineStart.UNDISPATCHED) { shown.copyColumn(4, limit = Long.MAX_VALUE).run() }
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

    private fun preamble(count: Int) = "> Task :run\n".repeat(count)

    private suspend fun openRedetecting(
        file: Path,
        recorder: Recorder,
        redetection: Redetection,
        chosen: Parser? = null,
    ): LogSession {
        val session = withContext(ui) {
            LogSession(listOf(file), scope, NoSettings, recorder, chosen = listOf(chosen), redetection = redetection)
                .also { it.start() }
        }
        withTimeout(10_000) { recorder.loads.receive() }
        return session
    }

    @Test
    fun `a file of preamble reads as raw text until records follow, then as their parser with the preamble kept`() =
        runBlocking {
            val expected = Parser.detect(Files.writeString(dir.resolve("records.log"), lines(10)))
            val file = Files.writeString(dir.resolve("run.log"), preamble(30))
            val recorder = Recorder()
            val session = openRedetecting(file, recorder, Redetection(gap = 100.milliseconds))
            assertEquals(Parser.Raw, withContext(ui) { session.parser })
            append(file, lines(10))
            session.grew()
            val shown = withTimeout(1_500) { recorder.loads.receive() }
            withContext(ui) {
                assertEquals(expected, session.parser)
                assertEquals(40, shown.size)
                val message = shown.schema.indexOf(Role.MESSAGE)
                for (record in 0..<30) {
                    assertEquals(null, shown.level(record))
                    assertEquals(null, shown.timestamp(record))
                    assertEquals("> Task :run", shown.fieldText(record, message))
                }
                assertNotNull(shown.level(30))
                session.dispose()
            }
        }

    @Test
    fun `records appended after the window closed do not switch the parser`() = runBlocking {
        val file = Files.writeString(dir.resolve("late.log"), preamble(30))
        val recorder = Recorder()
        val session = openRedetecting(file, recorder, Redetection(window = 200.milliseconds, gap = 100.milliseconds))
        delay(500)
        append(file, lines(10))
        session.grew()
        assertEquals(null, withTimeoutOrNull(1500) { recorder.loads.receive() })
        assertEquals(Parser.Raw, withContext(ui) { session.parser })
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `a parser the user picked is never detected again, Raw text included`() = runBlocking {
        val file = Files.writeString(dir.resolve("picked.log"), preamble(30))
        val recorder = Recorder()
        val session = openRedetecting(file, recorder, Redetection(gap = 100.milliseconds), chosen = Parser.Raw)
        append(file, lines(10))
        session.grew()
        assertEquals(null, withTimeoutOrNull(1500) { recorder.loads.receive() })
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `a session whose first detection found a parser is never detected again`() = runBlocking {
        val file = Files.writeString(dir.resolve("parsed.log"), lines(30))
        val recorder = Recorder()
        val session = openRedetecting(file, recorder, Redetection(gap = 100.milliseconds))
        append(file, preamble(10))
        session.grew()
        assertEquals(null, withTimeoutOrNull(1500) { recorder.loads.receive() })
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `detection runs once more over the full output when the writer ends, whatever the gap`() = runBlocking {
        val file = Files.writeString(dir.resolve("ended.log"), preamble(30))
        val recorder = Recorder()
        val session = openRedetecting(file, recorder, Redetection(gap = 60.seconds))
        append(file, lines(10))
        session.grew()
        assertEquals(null, withTimeoutOrNull(1500) { recorder.loads.receive() })
        session.writerEnded()
        withTimeout(10_000) { recorder.loads.receive() }
        withContext(ui) {
            assertTrue(session.parser != Parser.Raw)
            session.dispose()
        }
    }

    @Test
    fun `a merge refuses redetection`() {
        val a = Files.writeString(dir.resolve("a.log"), lines(10))
        val b = Files.writeString(dir.resolve("b.log"), lines(10))
        assertFailsWith<IllegalArgumentException> {
            LogSession(listOf(a, b), scope, NoSettings, Recorder(), redetection = Redetection())
        }
    }

    @Test
    fun `without redetection a detected parser must still read one of the first lines, as on the desktop`() =
        runBlocking {
            val file = Files.writeString(dir.resolve("desktop.log"), preamble(12) + lines(30))
            val recorder = Recorder()
            val session = withContext(ui) { LogSession(file, scope, NoSettings, recorder).also { it.start() } }
            val title = withTimeout(10_000) { recorder.problem.await() }
            assertTrue(title.startsWith("desktop.log does not match"), title)
            withContext(ui) { session.dispose() }
        }

    @Test
    fun `with redetection the same file opens with its preamble as records`() = runBlocking {
        val file = Files.writeString(dir.resolve("run.log"), preamble(12) + lines(30))
        val recorder = Recorder()
        val session = openRedetecting(file, recorder, Redetection())
        withContext(ui) {
            assertTrue(session.parser != Parser.Raw)
            assertEquals(42, session.shown!!.size)
            session.dispose()
        }
    }

    @Test
    fun `a load is logged with its files, their parser and the records read`() = runBlocking {
        val file = Files.writeString(dir.resolve("logged.log"), lines(100))
        val (session, _) = open(file, Recorder())
        val loaded = LogCapture.logged(LogLevel.INFO, "Loaded $file").single().message
        assertTrue("“${Parser.detect(file).name}” (detected), 100 records" in loaded, loaded)
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `a parser that does not fit is logged with why`() = runBlocking {
        val file = Files.writeString(dir.resolve("unfit.log"), "plain text\n".repeat(20))
        val recorder = Recorder()
        withContext(ui) { LogSession(file, scope, NoSettings, recorder).useParser(Parser.BUILT_IN.first()) }
        withTimeout(10_000) { recorder.problem.await() }
        val warning = LogCapture.logged(LogLevel.WARN, "$file does not match").single()
        assertTrue(warning.thrown is ParserMismatchException, "${warning.thrown}")
    }

    @Test
    fun `a reload is logged with why`() = runBlocking {
        val file = Files.writeString(dir.resolve("shrinking.log"), lines(100))
        val recorder = Recorder()
        val (session, _) = open(file, recorder)
        Files.writeString(file, lines(3))
        withTimeout(10_000) { recorder.loads.receive() }
        assertEquals(1, LogCapture.logged(LogLevel.INFO, "Reloading $file: $file shrank").size)
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `a file that cannot be read is logged once, not on every poll`() = runBlocking {
        val file = Files.writeString(dir.resolve("gone.log"), lines(10))
        val (session, _) = open(file, Recorder())
        Files.delete(file)
        repeat(3) {
            session.grew()
            delay(200)
        }
        awaitUi { session.status.message == "file not found" }
        assertEquals(1, LogCapture.logged(LogLevel.WARN, "Cannot read $file: file not found").size)
        withContext(ui) { session.dispose() }
    }

    @Test
    fun `a redetection that fails is logged`() = runBlocking {
        val file = Files.writeString(dir.resolve("vanished.log"), preamble(30))
        val session = openRedetecting(file, Recorder(), Redetection(gap = 100.milliseconds))
        Files.delete(file)
        session.writerEnded()
        withTimeout(10_000) {
            while (LogCapture.logged(LogLevel.WARN, "Cannot detect the parser of $file again").isEmpty()) delay(20)
        }
        withContext(ui) { session.dispose() }
    }
}
