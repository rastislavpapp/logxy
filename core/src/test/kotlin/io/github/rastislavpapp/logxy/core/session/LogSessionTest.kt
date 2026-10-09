package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
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
        val loads = Channel<LogData>(Channel.UNLIMITED)
        val problem = CompletableDeferred<String>()
        override fun indexing(progress: IndexProgress) {}
        override fun problem(title: String, detail: String, hint: String?) { problem.complete(title) }
        override fun loaded(log: LogData) { loads.trySend(log) }
        override fun formatChanged() {}
        override fun recordsAppended(oldRecordCount: Int) {}
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

    private suspend fun open(file: Path, recorder: Recorder): Pair<LogSession, LogData> {
        val session = withContext(ui) { LogSession(file, scope, NoSettings, recorder).also { it.start() } }
        return session to withTimeout(10_000) { recorder.loads.receive() }
    }

    /**
     * Waits until the session has read all of [file] and shows what a fresh evaluation of [filter]
     * gives, with the status counting it.
     */
    private suspend fun awaitShown(session: LogSession, file: Path, filter: Filter): List<Int> = withTimeout(10_000) {
        var done = false
        var expected = emptyList<Int>()
        while (!done) {
            delay(50)
            withContext(ui) {
                expected = FilterEngine(session.log!!).prepare(filter).run().rows.toList()
                val shown = session.shown?.let { rows -> List(rows.size) { rows[it] } }
                done = session.log!!.indexedSize == Files.size(file) && shown == expected && session.status.shown == expected.size
            }
        }
        expected
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
    fun `a parser that does not fit is reported`() = runBlocking {
        val file = Files.writeString(dir.resolve("other.log"), "plain text\n".repeat(20))
        val recorder = Recorder()
        val session = withContext(ui) { LogSession(file, scope, NoSettings, recorder) }
        withContext(ui) { session.useFormat(LogFormat.BUILT_IN.first()) }
        val title = withTimeout(10_000) { recorder.problem.await() }
        assertTrue("does not match" in title, title)
    }
}
