package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.FileActivity
import io.github.rastislavpapp.logxy.core.FileChange
import io.github.rastislavpapp.logxy.core.FileSnapshot
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.UIManager
import kotlin.coroutines.coroutineContext

private const val LIVE_POLL_MILLIS = 500L
private const val STATIC_POLL_MILLIS = 2000L

/** One open file: indexes it in the background, shows its lines and follows changes to it. */
class LogTab(
    val path: Path,
    private val openFiles: (List<Path>) -> Unit,
    private val onStatusChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var job: Job? = null
    private val content = JPanel(BorderLayout())
    private val followButton = JToggleButton("Follow").apply {
        toolTipText = "Keep the end of the file in view as it grows"
        addActionListener { follow = isSelected }
    }

    private var index: LineIndex? = null
    private var model: LineTableModel? = null
    private var scrollPane: VirtualScrollPane? = null
    private var activity = FileActivity.STATIC
    private var indexMillis = 0L
    private var problem: String? = null

    private var follow = false
        set(value) {
            field = value
            followButton.isSelected = value
            scrollPane?.follow = value
        }

    /** Right-hand status bar text for this file. */
    var statusText = "Indexing…"
        private set

    init {
        add(JToolBar().apply {
            isFloatable = false
            add(followButton)
        }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        start()
    }

    private fun start() {
        job = scope.launch {
            val reference = load() ?: return@launch
            watch(reference)
        }
    }

    /** Indexes the file and shows it; returns the snapshot the index corresponds to, or null on error. */
    private suspend fun load(): FileSnapshot? {
        val progress = IndexProgress()
        val progressBar = JProgressBar(0, 1000)
        showCentered(JLabel("Indexing ${path.fileName}…"), progressBar)
        statusText = "Indexing…"
        onStatusChanged()

        val ticker = scope.launch {
            while (true) {
                progressBar.value = (progress.fraction * 1000).toInt()
                delay(100)
            }
        }
        try {
            val started = System.nanoTime()
            // Taken before indexing, so a file replaced meanwhile shows up as a change on the first poll.
            val snapshot = withContext(Dispatchers.IO) { FileSnapshot.take(path) }
                ?: throw IOException("File not found")
            // Hand the result over outside the coroutine, so a cancellation racing with completion cannot leak it.
            val built = AtomicReference<LineIndex>()
            try {
                withContext(Dispatchers.IO) { built.set(LineIndex.build(path, progress)) }
            } catch (e: CancellationException) {
                built.getAndSet(null)?.close()
                throw e
            }
            val loaded = built.get()
            indexMillis = (System.nanoTime() - started) / 1_000_000
            index = loaded
            val currentSize = withContext(Dispatchers.IO) { Files.size(path) }
            activity = FileActivity.classify(snapshot.lastModified, snapshot.size, currentSize)
            problem = null
            showTable(loaded)
            if (activity == FileActivity.LIVE) follow = true
            updateStatus()
            return snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = JLabel("Cannot open ${path.fileName}: ${e.message ?: e.javaClass.simpleName}")
            error.foreground = UIManager.getColor("Component.error.focusedBorderColor")
            showCentered(error)
            statusText = "Error"
            onStatusChanged()
            return null
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun watch(reference: FileSnapshot) {
        var known = reference
        while (true) {
            delay(if (activity == FileActivity.LIVE) LIVE_POLL_MILLIS else STATIC_POLL_MILLIS)
            val index = index ?: return
            try {
                val current = withContext(Dispatchers.IO) { FileSnapshot.take(path) }
                when (known.changeTo(current, index.indexedSize)) {
                    FileChange.MISSING -> {
                        problem = "file not found"
                        updateStatus()
                        continue
                    }
                    FileChange.SHRUNK, FileChange.REPLACED -> {
                        reload()
                        return
                    }
                    FileChange.GROWN -> {
                        val appended = withContext(Dispatchers.IO) { index.scanAppended(current!!.size) }
                        val oldLineCount = index.lineCount
                        index.append(appended)
                        model?.linesAppended(oldLineCount)
                        scrollPane?.rowsAppended()
                        if (activity == FileActivity.STATIC) {
                            activity = FileActivity.LIVE
                            follow = true
                        }
                    }
                    FileChange.UNCHANGED ->
                        activity = FileActivity.classify(current!!.lastModified, current.size, current.size)
                }
                known = current!!
                problem = null
            } catch (e: IOException) {
                // Closing the tab closes the file under a running read.
                coroutineContext.ensureActive()
                problem = e.message ?: e.javaClass.simpleName
            }
            updateStatus()
        }
    }

    /** Starts over with the file now at [path], e.g. after rotation or truncation. */
    private fun reload() {
        job?.cancel()
        closeIndex()
        start()
    }

    private fun showCentered(vararg components: JComponent) {
        content.removeAll()
        content.add(JPanel(MigLayout("fill, wrap 1", "[center]", "push[]8[]push")).apply {
            components.forEach { add(it, if (it is JProgressBar) "w 320!" else "") }
        })
        content.revalidate()
        content.repaint()
    }

    private fun showTable(index: LineIndex) {
        val model = LineTableModel(index)
        val table = LineTable(model)
        // The table has its own transfer handler (for copy), which would otherwise swallow file drops.
        table.transferHandler = FileDropHandler(openFiles, table.transferHandler)
        val scrollPane = VirtualScrollPane(table, model)
        scrollPane.onFollowChanged = { if (follow != it) follow = it }
        this.model = model
        this.scrollPane = scrollPane
        content.removeAll()
        content.add(scrollPane, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
        scrollPane.follow = follow
    }

    private fun updateStatus() {
        val index = index ?: return
        val numbers = NumberFormat.getIntegerInstance()
        val mode = if (activity == FileActivity.LIVE) "live" else "static"
        val lines = if (index.lineCount == 1) "line" else "lines"
        statusText = "${formatByteSize(index.indexedSize)}  ·  ${numbers.format(index.lineCount)} $lines  ·  " +
            "$mode  ·  indexed in ${numbers.format(indexMillis)} ms" + (problem?.let { "  ·  $it" } ?: "")
        onStatusChanged()
    }

    private fun closeIndex() {
        index?.close()
        index = null
        model = null
        scrollPane = null
    }

    /** Cancels any running work and releases the file. */
    fun dispose() {
        scope.cancel()
        closeIndex()
    }
}
