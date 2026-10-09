package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.FileActivity
import io.github.rastislavpapp.logxy.core.FileChange
import io.github.rastislavpapp.logxy.core.FileSnapshot
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.query.QueryCompiler
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.FormatMismatchException
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import io.github.rastislavpapp.logxy.core.parse.open
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicReference
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JToolBar
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

private const val LIVE_POLL_MILLIS = 500L
private const val STATIC_POLL_MILLIS = 2000L
private const val MAX_DETAIL_BYTES = 1024 * 1024

/** An entry of the format chooser. */
private sealed interface FormatChoice {
    data class Auto(val detected: LogFormat?) : FormatChoice {
        override fun toString() = if (detected == null) "Auto-detect" else "Auto: ${detected.name}"
    }

    data class Fixed(val format: LogFormat) : FormatChoice {
        override fun toString() = format.name
    }

    data object Edit : FormatChoice {
        override fun toString() = "Edit parsers…"
    }
}

/** One open file: indexes it in the background, shows its records and follows changes to it. */
class LogTab(
    val path: Path,
    private val openFiles: (List<Path>) -> Unit,
    private val onStatusChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var job: Job? = null
    private val content = JPanel(BorderLayout())
    private val detail = JTextArea().apply {
        isEditable = false
        lineWrap = true
    }
    private val followButton = accentToggle("Follow").apply {
        toolTipText = "Keep the end of the file in view as it grows"
        addActionListener { follow = isSelected }
    }
    private val formats = DefaultComboBoxModel<FormatChoice>()
    private val formatBox = object : JComboBox<FormatChoice>(formats) {
        // A toolbar would otherwise stretch it across the whole width.
        override fun getMaximumSize(): Dimension = preferredSize
    }.apply {
        toolTipText = "How lines are split into columns"
        addActionListener { formatChosen() }
    }
    private var updatingFormatBox = false
    private val settingsListener = { settingsChanged() }

    /** The format the user picked, or null to detect it. */
    private var chosenFormat: LogFormat? = null
    private var format: LogFormat = LogFormat.Raw

    private var log: LogData? = null
    private var model: LogTableModel? = null
    private var table: LogTable? = null
    private var scrollPane: VirtualScrollPane? = null
    private val filterBar = FilterBar(::applyFilter)
    private var engine: FilterEngine? = null
    private var activeFilter: Filter? = null
    private var filterJob: Job? = null
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
        add(JPanel(BorderLayout()).apply {
            add(JToolBar().apply {
                isFloatable = false
                add(followButton)
                addSeparator()
                add(JLabel("Parser: "))
                add(formatBox)
            }, BorderLayout.NORTH)
            add(filterBar, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        fillFormats()
        AppSettings.addListener(settingsListener)
        start()
    }

    /** Auto, raw text, the user's formats, the built-in ones; plus the format in use if it is none of those. */
    private fun fillFormats() {
        updatingFormatBox = true
        try {
            formats.removeAllElements()
            formats.addElement(FormatChoice.Auto(null))
            val known = listOf(LogFormat.Raw) + AppSettings.current.formats + LogFormat.BUILT_IN
            val inUse = chosenFormat
            (if (inUse == null || inUse in known) known else known + inUse).forEach { formats.addElement(FormatChoice.Fixed(it)) }
            formats.addElement(FormatChoice.Edit)
        } finally {
            updatingFormatBox = false
        }
        showFormat()
    }

    private fun settingsChanged() {
        val settings = AppSettings.current
        (log as? ParsedLog)?.levelMapping = settings.levels
        model?.fireTableRowsUpdated(0, maxOf(0, (model?.rowCount ?: 0) - 1))
        // A saved format this tab uses was edited: read the file with the new definition.
        val edited = chosenFormat?.let { chosen -> settings.formats.firstOrNull { it.name == chosen.name && it != chosen } }
        if (edited != null) chosenFormat = edited
        fillFormats()
        if (edited != null) reload()
    }

    private fun start() {
        job = scope.launch {
            val reference = load() ?: return@launch
            watch(reference)
        }
    }

    private fun formatChosen() {
        if (updatingFormatBox) return
        when (val choice = formatBox.selectedItem as FormatChoice) {
            is FormatChoice.Auto -> chosenFormat = null
            is FormatChoice.Fixed -> chosenFormat = choice.format
            FormatChoice.Edit -> {
                showFormat()
                val dialog = ParsersDialog(SwingUtilities.getWindowAncestor(this), path, offerUse = true, chosenFormat ?: format)
                dialog.isVisible = true
                chosenFormat = dialog.formatToUse ?: return
                fillFormats()
            }
        }
        reload()
    }

    /** Selects the entry for the current format without reacting to it. */
    private fun showFormat() {
        updatingFormatBox = true
        try {
            val chosen = chosenFormat
            if (chosen == null) {
                formats.removeElementAt(0)
                formats.insertElementAt(FormatChoice.Auto(format), 0)
                formatBox.selectedIndex = 0
            } else {
                formatBox.selectedItem = (0..<formats.size).map { formats.getElementAt(it) }
                    .first { it is FormatChoice.Fixed && it.format == chosen }
            }
        } finally {
            updatingFormatBox = false
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
            val preferred = AppSettings.current.formats
            format = chosenFormat ?: withContext(Dispatchers.IO) { LogFormat.detect(path, preferred) }
            showFormat()
            // Hand the result over outside the coroutine, so a cancellation racing with completion cannot leak it.
            val built = AtomicReference<LogData>()
            try {
                val levels = AppSettings.current.levels
                withContext(Dispatchers.IO) { built.set(format.open(path, progress, levels)) }
            } catch (e: CancellationException) {
                built.getAndSet(null)?.close()
                throw e
            }
            val loaded = built.get()
            indexMillis = (System.nanoTime() - started) / 1_000_000
            log = loaded
            val currentSize = withContext(Dispatchers.IO) { Files.size(path) }
            activity = FileActivity.classify(snapshot.lastModified, snapshot.size, currentSize)
            problem = null
            showTable(loaded)
            if (activity == FileActivity.LIVE) follow = true
            updateStatus()
            return snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: FormatMismatchException) {
            showProblem(
                "${path.fileName} does not match the parser “${format.name}”.",
                e.message.orEmpty(),
                "Choose another parser in the toolbar, or Raw text.",
            )
            statusText = "Parser does not match"
            onStatusChanged()
            return null
        } catch (e: Exception) {
            showProblem("Cannot open ${path.fileName}.", e.message ?: e.javaClass.simpleName, null)
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
            val log = log ?: return
            try {
                val current = withContext(Dispatchers.IO) { FileSnapshot.take(path) }
                when (known.changeTo(current, log.indexedSize)) {
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
                        val appended = withContext(Dispatchers.IO) { log.readAppended(current!!.size) }
                        val oldRecordCount = log.recordCount
                        log.append(appended)
                        model?.recordsAppended(oldRecordCount)
                        if (activeFilter != null) runFilter(keepView = true) else scrollPane?.rowsAppended()
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
                currentCoroutineContext().ensureActive()
                problem = e.message ?: e.javaClass.simpleName
            }
            updateStatus()
        }
    }

    /** Starts over with the file now at [path], e.g. after rotation, truncation or a format change. */
    private fun reload() {
        job?.cancel()
        closeLog()
        start()
    }

    private fun showCentered(vararg components: JComponent) {
        content.removeAll()
        content.add(JPanel(MigLayout("align 50% 50%, wrap 1, gapy 8", "[center]")).apply {
            components.forEach { add(it, if (it is JProgressBar) "w 320!" else "") }
        })
        content.revalidate()
        content.repaint()
    }

    private fun showProblem(title: String, detail: String, hint: String?) {
        val heading = JLabel(title).apply {
            putClientProperty("FlatLaf.styleClass", "h3")
            foreground = UIManager.getColor("Component.error.focusedBorderColor")
        }
        val text = JTextArea(detail).apply {
            isEditable = false
            isOpaque = false
            font = UIManager.getFont("monospaced.font") ?: font
        }
        val components = listOfNotNull<JComponent>(heading, text, hint?.let { JLabel(it) })
        showCentered(*components.toTypedArray())
    }

    private fun showTable(log: LogData) {
        val model = LogTableModel(log)
        val table = LogTable(model)
        // The table has its own transfer handler (for copy), which would otherwise swallow file drops.
        table.transferHandler = FileDropHandler(openFiles, table.transferHandler)
        val scrollPane = VirtualScrollPane(table, model)
        scrollPane.onFollowChanged = { if (follow != it) follow = it }
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showDetail(model, table.selectionModel.leadSelectionIndex)
        }
        this.model = model
        this.table = table
        this.scrollPane = scrollPane
        engine = FilterEngine(log)
        filterBar.setSchema(log.schema)

        detail.font = table.font.deriveFont(Font.PLAIN)
        detail.text = ""
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollPane, JScrollPane(
            detail, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        )).apply {
            resizeWeight = 0.75
            isOneTouchExpandable = true
            border = null
        }
        content.removeAll()
        content.add(split, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
        split.setDividerLocation(0.75)
        scrollPane.follow = follow
        applyFilter()
    }

    private fun showDetail(model: LogTableModel, row: Int) {
        detail.text = if (row < 0 || row >= model.rowCount) "" else model.log.recordText(model.recordAt(row), MAX_DETAIL_BYTES)
        detail.caretPosition = 0
    }

    /** Applies the filter bar's level and query to this file's records. */
    private fun applyFilter() {
        val log = log ?: return
        activeFilter = try {
            buildFilter(log)
        } catch (e: QueryException) {
            filterBar.showError(e.message)
            return
        }
        filterBar.showError(null)
        runFilter(keepView = false)
    }

    private fun buildFilter(log: LogData): Filter? {
        val parts = ArrayList<Filter>()
        val levelField = log.schema.fields.firstOrNull { it.role == Role.LEVEL }
        filterBar.minimumLevel?.let { minimum -> if (levelField != null) parts += LevelCondition.atLeast(levelField.name, minimum) }
        val query = filterBar.query()
        val compiled = QueryCompiler(log.schema).compile(query)
        // Only placeholders (empty values): nothing to filter.
        if (compiled != Filter.And(emptyList())) parts += compiled
        return if (parts.isEmpty()) null else Filter.And(parts)
    }

    /** Runs [activeFilter]; with [keepView], keeps the rows on screen where they are (new records while tailing). */
    private fun runFilter(keepView: Boolean) {
        filterJob?.cancel()
        val filter = activeFilter
        if (filter == null) {
            if (model?.filteredRows != null || !keepView) showRows(null, keepView)
            return
        }
        val evaluation = try {
            engine?.prepare(filter) ?: return
        } catch (e: IllegalArgumentException) {
            filterBar.showError(e.message)
            return
        }
        filterJob = scope.launch {
            val result = evaluation.run()
            showRows(result.rows, keepView)
        }
    }

    private fun showRows(rows: IntArray?, keepView: Boolean) {
        val model = model ?: return
        val table = table ?: return
        val scrollPane = scrollPane ?: return
        val selected = table.selectedRow.takeIf { it >= 0 }?.let { model.recordAt(it) }
        val top = if (model.totalRowCount > 0) model.recordAtIndex(scrollPane.firstVisibleRow) else null
        model.showRows(rows)
        scrollPane.rowsReplaced()
        val selectedIndex = selected?.let { record -> model.indexOfRecord(record).takeIf { it >= 0 && model.recordAtIndex(it) == record } }
        if (!follow) {
            if (keepView && top != null) {
                scrollPane.scrollToRow(maxOf(0, model.indexOfRecord(top)))
            } else if (selectedIndex != null) {
                scrollPane.selectRow(selectedIndex)
            }
        }
        if (selectedIndex != null) scrollPane.selectRow(selectedIndex, scroll = false)
        updateStatus()
    }

    private fun updateStatus() {
        val log = log ?: return
        val numbers = NumberFormat.getIntegerInstance()
        val unit = if (log is LineIndex) "line" else "record"
        val total = "${numbers.format(log.recordCount)} $unit${if (log.recordCount == 1) "" else "s"}"
        val shown = model?.filteredRows?.size
        val count = if (shown == null) total else "${numbers.format(shown)} of $total"
        val mode = if (activity == FileActivity.LIVE) "live" else "static"
        statusText = "${formatByteSize(log.indexedSize)}  ·  $count  ·  $mode  ·  indexed in ${numbers.format(indexMillis)} ms" +
            (problem?.let { "  ·  $it" } ?: "")
        onStatusChanged()
    }

    private fun closeLog() {
        filterJob?.cancel()
        log?.close()
        log = null
        model = null
        table = null
        scrollPane = null
        engine = null
    }

    /** Cancels any running work and releases the file. */
    fun dispose() {
        AppSettings.removeListener(settingsListener)
        scope.cancel()
        closeLog()
    }
}
