package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.FileActivity
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.UIManager
import kotlin.time.Duration.Companion.milliseconds

/** One open file: indexes it in the background, then shows its lines. */
class LogTab(
    val path: Path,
    private val openFiles: (List<Path>) -> Unit,
    private val onStatusChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var index: LineIndex? = null

    /** Right-hand status bar text for this file. */
    var statusText = "Indexing…"
        private set

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val progress = IndexProgress()
        val progressBar = JProgressBar(0, 1000)
        val progressLabel = JLabel("Indexing ${path.fileName}…")
        showCentered(progressLabel, progressBar)

        val ticker = scope.launch {
            while (true) {
                progressBar.value = (progress.fraction * 1000).toInt()
                delay(100.milliseconds)
            }
        }
        try {
            val started = System.nanoTime()
            // Hand the result over outside the coroutine, so a cancellation racing with completion cannot leak it.
            val built = AtomicReference<LineIndex>()
            try {
                withContext(Dispatchers.IO) { built.set(LineIndex.build(path, progress)) }
            } catch (e: CancellationException) {
                built.getAndSet(null)?.close()
                throw e
            }
            val loaded = built.get()
            val millis = (System.nanoTime() - started) / 1_000_000
            index = loaded
            val activity = withContext(Dispatchers.IO) { classify(loaded) }
            showTable(loaded)
            updateStatus(loaded, activity, millis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = JLabel("Cannot open ${path.fileName}: ${e.message ?: e.javaClass.simpleName}")
            error.foreground = UIManager.getColor("Component.error.focusedBorderColor")
            showCentered(error)
            statusText = "Error"
            onStatusChanged()
        } finally {
            ticker.cancel()
        }
    }

    private fun classify(index: LineIndex): FileActivity {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return FileActivity.classify(attributes.lastModifiedTime().toInstant(), index.indexedSize, attributes.size())
    }

    private fun showCentered(vararg components: JComponent) {
        removeAll()
        add(JPanel(MigLayout("fill, wrap 1", "[center]", "push[]8[]push")).apply {
            components.forEach { add(it, if (it is JProgressBar) "w 320!" else "") }
        })
        revalidate()
        repaint()
    }

    private fun showTable(index: LineIndex) {
        val model = LineTableModel(index)
        val table = LineTable(model)
        // The table has its own transfer handler (for copy), which would otherwise swallow file drops.
        table.transferHandler = FileDropHandler(openFiles, table.transferHandler)
        removeAll()
        add(VirtualScrollPane(table, model), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun updateStatus(index: LineIndex, activity: FileActivity, millis: Long) {
        val numbers = NumberFormat.getIntegerInstance()
        val mode = if (activity == FileActivity.LIVE) "live" else "static"
        statusText = "${formatByteSize(index.indexedSize)}  ·  ${numbers.format(index.lineCount)} lines  ·  " +
            "$mode  ·  indexed in ${numbers.format(millis)} ms"
        onStatusChanged()
    }

    /** Cancels any running work and releases the file. */
    fun dispose() {
        scope.cancel()
        index?.close()
        index = null
    }
}
