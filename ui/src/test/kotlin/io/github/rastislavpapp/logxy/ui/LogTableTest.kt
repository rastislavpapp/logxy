package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.session.LogSession
import io.github.rastislavpapp.logxy.core.session.SessionListener
import io.github.rastislavpapp.logxy.core.session.SessionStatus
import io.github.rastislavpapp.logxy.core.session.SettingsStore
import io.github.rastislavpapp.logxy.core.session.ShownLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.SwingUtilities
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class LogTableTest {

    @TempDir
    lateinit var dir: Path

    // Stands in for the UI thread the session runs on.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private val previousManager = RepaintManager.currentManager(null)

    @BeforeTest
    fun installLook() {
        Look.current = PlainLook
    }

    @AfterEach
    fun stop() {
        RepaintManager.setCurrentManager(previousManager)
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

    /** Collects the regions repainting asks for, of all components. */
    private class DirtyRegions : RepaintManager() {
        val regions = ArrayList<Pair<JComponent, Rectangle>>()
        override fun addDirtyRegion(c: JComponent, x: Int, y: Int, w: Int, h: Int) {
            regions += c to Rectangle(x, y, w, h)
        }
    }

    private suspend fun shownLog(): ShownLog {
        val file = dir.resolve("app.log")
        val lines = (0..<10).map { "2026-10-01 08:00:00.00$it [main] INFO  app.L - message $it" }
        Files.write(file, lines)
        val loads = Loads()
        withContext(scope.coroutineContext) { LogSession(file, scope, NoSettings, loads).start() }
        return withTimeout(10_000) { loads.loads.receive() }
    }

    // A selection made in the selection model, by a click or a key, shows only once the table repaints its rows: the
    // table must listen to the model it holds.
    @Test
    fun `a selected row is repainted`() = runBlocking {
        val shown = shownLog()
        SwingUtilities.invokeAndWait {
            val table = LogTable(LogTableModel(shown, ColumnNames()))
            table.setSize(800, 400)
            val dirty = DirtyRegions()
            RepaintManager.setCurrentManager(dirty)

            table.selectionModel.setSelectionInterval(3, 3)

            val row = table.getCellRect(3, 0, false).union(table.getCellRect(3, table.columnCount - 1, false))
            val repainted = dirty.regions.filter { it.first === table }.map { it.second }
            assertTrue(repainted.any { it.contains(row) }, "row $row not in the repainted $repainted")
        }
    }
}
