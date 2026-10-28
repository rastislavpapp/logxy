package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.trace.TraceColumn
import io.github.rastislavpapp.logxy.core.trace.Traces
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
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTracesTest {

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

    private class Loads : SessionListener {
        val loads = Channel<ShownLog>(Channel.UNLIMITED)
        override fun indexing(fraction: () -> Double) {}
        override fun problem(title: String, detail: String, hint: String?) {}
        override fun loaded(shown: ShownLog) {
            loads.trySend(shown)
        }
        override fun shownEnded() {}
        override fun notMergeable(paths: List<Path>) {}
        override fun parserChanged() {}
        override fun recordsGrew(changed: IntArray) {}
        override fun fieldsShown() {}
        override fun rowsReplaced(keepView: Boolean, oldSize: Int, recordBefore: (row: Int) -> Int) {}
        override fun rowsChanged(firstChanged: Int, oldSize: Int) {}
        override fun levelMappingChanged() {}
        override fun histogramChanged() {}
        override fun foundChanged() {}
        override fun rowHighlightsChanged(fromRecord: Int) {}
        override fun followChanged(follow: Boolean) {}
        override fun statusChanged(status: SessionStatus) {}
    }

    private val mdc = Parser.Pattern("MDC", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c %X - %m%n")

    private fun line(time: String, thread: String, level: String, mdc: String = "{}") =
        "2026-10-01 $time [$thread] $level app.L $mdc - message\n"

    private fun open(vararg files: Path, shifts: List<Long> = emptyList(), parser: Parser = mdc) = runBlocking {
        val loads = Loads()
        val session = withContext(ui) {
            LogSession(files.toList(), scope, NoSettings, loads, chosen = files.map { parser }, shifts = shifts)
                .also { it.start() }
        }
        withTimeout(10_000) { loads.loads.receive() }
        session
    }

    private fun traces(session: LogSession, column: String): Traces? = runBlocking(ui) { session.traces(column) }

    /** Each trace as `id: duration ms, records, worst level, first record`, in [Traces] order. */
    private fun Traces.rows(): List<String> = List(size) { trace ->
        val millis = if (duration(trace) == Traces.NO_DURATION) "-" else "${duration(trace) / 1_000_000}"
        "${id(trace)}: $millis ms, ${records(trace)}, ${worstLevel(trace)}, ${firstRecord(trace)}"
    }

    @Test
    fun `lists one trace per value of the chosen column, from its first record's time to its last one's`() {
        val file = Files.writeString(
            dir.resolve("a.log"),
            line("08:00:00.000", "t1", "INFO ") +
                line("08:00:01.000", "t2", "INFO ") +
                line("08:00:03.500", "t1", "WARN ") +
                line("08:00:04.000", "t3", "ERROR") +
                line("08:00:05.000", "t2", "DEBUG"),
        )
        val session = open(file)
        val traces = traces(session, "Thread")!!
        assertEquals(
            listOf("t1: 3500 ms, 2, WARN, 0", "t2: 4000 ms, 2, INFO, 1", "t3: 0 ms, 1, ERROR, 3"),
            traces.rows(),
        )
        val start = traces.start(0)
        assertEquals(start + 3_500_000_000L, traces.end(0))
        assertEquals(listOf("t2", "t1", "t3"), traces.order(TraceColumn.DURATION, descending = true).map(traces::id))
    }

    @Test
    fun `takes trace ids from a map entry, and records without one belong to no trace`() {
        val file = Files.writeString(
            dir.resolve("mdc.log"),
            line("08:00:00.000", "main", "INFO ", "{requestId=r1, user=ann}") +
                line("08:00:00.250", "main", "INFO ", "{user=bob}") +
                line("08:00:00.500", "w-1", "ERROR", "{requestId=r2}") +
                line("08:00:01.000", "w-2", "INFO ", "{requestId=r1}") +
                line("08:00:02.000", "main", "INFO ", "{}"),
        )
        val traces = traces(open(file), "MDC[requestId]")!!
        assertEquals(listOf("r1: 1000 ms, 2, INFO, 0", "r2: 0 ms, 1, ERROR, 2"), traces.rows())
    }

    @Test
    fun `a merge's traces span its files, with their timestamp shifts, and name the files they come from`() {
        val hour = 3_600_000_000_000L
        val a = Files.writeString(
            dir.resolve("a.log"),
            line("08:00:00.000", "x", "INFO ", "{requestId=r1}") + line("08:00:05.000", "x", "INFO ", "{requestId=r2}"),
        )
        // Logged an hour behind: 07:00:02 is 08:00:02.
        val b = Files.writeString(
            dir.resolve("b.log"),
            line("07:00:02.000", "y", "WARN ", "{requestId=r1}") + line("07:00:09.000", "y", "INFO ", "{requestId=r3}"),
        )
        val traces = traces(open(a, b, shifts = listOf(0, hour)), "MDC[requestId]")!!
        assertEquals(listOf("r1: 2000 ms, 2, WARN, 0", "r2: 0 ms, 1, INFO, 2", "r3: 0 ms, 1, INFO, 3"), traces.rows())
        assertEquals(listOf(listOf("a.log", "b.log"), listOf("a.log"), listOf("b.log")), List(3, traces::sources))
        assertEquals(listOf("r1", "r2", "r3"), traces.order(TraceColumn.SOURCES, descending = false).map(traces::id))
    }

    @Test
    fun `appended records extend their traces, also after an unfinished last line`() {
        fun logfmt(second: Int, level: String, request: String) =
            "ts=2026-10-01T08:00:0$second.000Z level=$level msg=done req=$request\n"
        val file = Files.writeString(dir.resolve("tail.log"), logfmt(0, "info", "r1"))
        val session = open(file, parser = Parser.Logfmt("logfmt"))
        assertEquals(listOf("r1: 0 ms, 1, INFO, 0"), traces(session, "req")!!.rows())
        // Read while the line ends at `req=r1`, the start of the record's trace id.
        val next = logfmt(2, "error", "r12")
        Files.writeString(file, next.dropLast(2), APPEND)
        awaitRead(session, file)
        assertEquals(listOf("r1: 2000 ms, 2, ERROR, 0"), traces(session, "req")!!.rows())
        Files.writeString(file, next.takeLast(2) + logfmt(3, "warn", "r1"), APPEND)
        awaitRead(session, file)
        assertEquals(listOf("r1: 3000 ms, 2, WARN, 0", "r12: 0 ms, 1, ERROR, 1"), traces(session, "req")!!.rows())
    }

    @Test
    fun `groups a column of mostly distinct values, which the log stores as text in the file`() {
        val requests = 20_000
        val content = buildString {
            for (i in 0..<requests * 2) {
                val request = i % requests
                append(line("08:00:%02d.000".format(i / requests), "t", "INFO ", "{requestId=req-$request, n=$i}"))
            }
        }
        val traces = traces(open(Files.writeString(dir.resolve("distinct.log"), content)), "MDC[requestId]")!!
        assertEquals(requests, traces.size)
        assertEquals("req-0: 1000 ms, 2, INFO, 0", traces.rows().first())
        assertEquals(requests, traces.statistics!!.count)
    }

    @Test
    fun `gives no traces for a field the trace ids cannot come from`() {
        val session = open(Files.writeString(dir.resolve("fields.log"), line("08:00:00.000", "t1", "INFO ")))
        for (column in listOf("Timestamp", "Level", "Message", "No such field")) {
            assertEquals(null, traces(session, column), column)
        }
    }

    /** Waits until [session] has read all of [file]. */
    private fun awaitRead(session: LogSession, file: Path) = runBlocking {
        withTimeout(10_000) {
            while (withContext(ui) { session.status.size } != Files.size(file)) {
                withContext(ui) { session.grew() }
                delay(20)
            }
        }
    }
}
