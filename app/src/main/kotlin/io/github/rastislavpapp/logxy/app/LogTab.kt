package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.FileActivity
import io.github.rastislavpapp.logxy.core.FileChange
import io.github.rastislavpapp.logxy.core.FileSnapshot
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.query.QueryCompiler
import io.github.rastislavpapp.logxy.core.query.QueryException
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.time.Duration.Companion.milliseconds

private const val LIVE_POLL_MILLIS = 500L
private const val STATIC_POLL_MILLIS = 2000L
private const val MAX_DETAIL_BYTES = 1024 * 1024

/** The status bar's view of one file; [records] is null while it cannot be shown (indexing, errors). */
data class TabStatus(
    val size: Long? = null,
    val records: Int? = null,
    val shown: Int? = null,
    val unit: String = "record",
    val live: Boolean = false,
    val following: Boolean = false,
    val indexMillis: Long? = null,
    val message: String? = null,
)

/** One open file: indexes it in the background, shows its records and follows changes to it. */
class LogTab(
    val path: Path,
    private val openFiles: (List<Path>) -> Unit,
    private val onStatusChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var job: Job? = null
    private val content = JPanel(BorderLayout())
    private val detail = LogDetailArea()
    private val followButton = accentToggle("").apply {
        icon = Icons.follow
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        toolTipText = "Follow (tail)"
        addActionListener { follow = isSelected }
    }
    private val reloadButton = JButton(Icons.refresh).apply {
        toolTipText = "Reload the file (F5)"
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        addActionListener { reload() }
    }
    private val parserButton = dropDownButton("Parser: how lines are split into columns", maxWidth = 280) { showParsers() }
    private val settingsListener = { settingsChanged() }

    /** The format the user picked, or null to detect it. */
    private var chosenFormat: LogFormat? = null
    private var format: LogFormat = LogFormat.Raw
    private var formatDetected = false

    private var log: LogData? = null
    private var model: LogTableModel? = null
    private var table: LogTable? = null
    private var scrollPane: VirtualScrollPane? = null
    private val filterBar = FilterBar(::applyFilter)
    private var engine: FilterEngine? = null
    private var activeFilter: Filter? = null
    private var filterJob: Job? = null

    // How much of the log the shown filter result covers: records, and bytes (the last record may grow without new ones).
    private var filteredRecords = 0
    private var filteredBytes = 0L
    private var activity = FileActivity.STATIC
    private var indexMillis = 0L
    private var problem: String? = null

    private var follow = false
        set(value) {
            val changed = field != value
            field = value
            followButton.isSelected = value
            scrollPane?.follow = value
            if (changed) updateStatus()
        }

    /** What the status bar shows for this file. */
    var status = TabStatus(message = "Indexing…")
        private set

    // The controls live outside the tab, and are not updated with it while another tab shows its own.
    override fun updateUI() {
        super.updateUI()
        @Suppress("SENSELESS_COMPARISON")
        if (filterBar == null) return
        listOf(fileControls, filterControls).filter { it.parent == null }.forEach { SwingUtilities.updateComponentTreeUI(it) }
    }

    private fun setStatus(status: TabStatus) {
        this.status = status
        onStatusChanged()
    }

    /** Reload, Follow and the parser; placed by the window, outside this tab. */
    val fileControls: JComponent = JPanel(MigLayout("insets 0, gap 2")).apply {
        isOpaque = false
        add(reloadButton)
        add(followButton, "gapright 6")
        add(parserButton, "wmin 120")
    }

    /** The filter's level, mode and saved filters; placed by the window, outside this tab. */
    val filterControls: JComponent get() = filterBar.controls

    init {
        add(filterBar, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        showFormat()
        AppSettings.addListener(settingsListener)
        start()
    }

    /** Auto, raw text, the user's formats (editable), the built-in ones; plus the format in use if it is none of those. */
    private fun showParsers() {
        val chosen = chosenFormat
        val custom = AppSettings.current.formats
        val known = listOf(LogFormat.Raw) + custom + LogFormat.BUILT_IN
        val items = buildList {
            add(PopupItem(autoLabel(), selected = chosen == null) { useFormat(null) })
            (if (chosen == null || chosen in known) known else known + chosen).forEachIndexed { i, format ->
                val tools = if (format in custom) listOf(PopupTool(Icons.advanced, "Edit this parser") { editParsers(format) }) else emptyList()
                add(PopupItem(format.name, selected = format == chosen, separatorBefore = i == 0, tools = tools) { useFormat(format) })
            }
            add(PopupItem("Edit Parsers", Icons.settings, separatorBefore = true) { editParsers(chosen ?: format) })
        }
        showPopupList(parserButton, items)
    }

    private fun autoLabel() = if (formatDetected) "Auto: ${format.name}" else "Auto-detect"

    private fun useFormat(format: LogFormat?) {
        if (format == chosenFormat) return
        chosenFormat = format
        showFormat()
        reload()
    }

    private fun editParsers(select: LogFormat) {
        val dialog = ParsersDialog(SwingUtilities.getWindowAncestor(this), path, offerUse = true, select)
        dialog.isVisible = true
        useFormat(dialog.formatToUse ?: return)
    }

    /** Shows the format in use on the parser button. */
    private fun showFormat() {
        parserButton.text = chosenFormat?.name ?: autoLabel()
    }

    private fun settingsChanged() {
        val settings = AppSettings.current
        val log = log
        val levelsChanged = log != null && !log.isRaw && log.levelMapping != settings.levels
        log?.levelMapping = settings.levels
        model?.fireTableRowsUpdated(0, maxOf(0, (model?.rowCount ?: 0) - 1))
        // A saved format this tab uses was edited: read the file with the new definition.
        val edited = chosenFormat?.let { chosen -> settings.formats.firstOrNull { it.name == chosen.name && it != chosen } }
        if (edited != null) chosenFormat = edited
        // Only renamed: nothing to read again, just show the new name.
        chosenFormat?.let { renamed(it, settings.formats) }?.let { chosenFormat = it }
        renamed(format, settings.formats)?.let { format = it }
        showFormat()
        if (edited != null) {
            reload()
        } else if (levelsChanged && activeFilter != null) {
            runFilter(keepView = true)
        }
    }

    /** The saved format that [format] became by a rename, i.e. with the same definition under a new name. */
    private fun renamed(format: LogFormat, saved: List<LogFormat>): LogFormat? {
        if (format == LogFormat.Raw || format in LogFormat.BUILT_IN || saved.any { it.name == format.name }) return null
        return saved.firstOrNull { candidate ->
            when (format) {
                is LogFormat.Pattern -> candidate is LogFormat.Pattern && candidate.pattern == format.pattern
                is LogFormat.Regex -> candidate is LogFormat.Regex && candidate.regex == format.regex &&
                    candidate.timestampPattern == format.timestampPattern
                LogFormat.Raw -> false
            }
        }
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
        setStatus(TabStatus(message = "Indexing…"))

        val ticker = scope.launch {
            while (true) {
                progressBar.value = (progress.fraction * 1000).toInt()
                delay(100.milliseconds)
            }
        }
        try {
            val started = System.nanoTime()
            // Taken before indexing, so a file replaced meanwhile shows up as a change on the first poll.
            val snapshot = withContext(Dispatchers.IO) { FileSnapshot.take(path) }
                ?: throw IOException("File not found")
            val preferred = AppSettings.current.formats
            format = chosenFormat ?: withContext(Dispatchers.IO) { LogFormat.detect(path, preferred) }
            formatDetected = true
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
            setStatus(TabStatus(message = "Parser does not match"))
            return null
        } catch (e: Exception) {
            showProblem("Cannot open ${path.fileName}.", e.message ?: e.javaClass.simpleName, null)
            setStatus(TabStatus(message = "Error"))
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
                        refreshDetail(oldRecordCount - 1)
                        if (activeFilter != null) catchUpFilter() else scrollPane?.rowsAppended()
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
        // Nothing to filter until the records are there.
        setFilterVisible(false)
        content.removeAll()
        content.add(JPanel(MigLayout("align 50% 50%, wrap 1, gapy 8", "[center]")).apply {
            components.forEach { add(it, if (it is JProgressBar) "w 320!" else "") }
        })
        content.revalidate()
        content.repaint()
    }

    private fun setFilterVisible(visible: Boolean) {
        filterBar.isVisible = visible
        filterControls.isVisible = visible
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
        scrollPane.resumeFollowAtEnd = { activity == FileActivity.LIVE }
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showDetail(model, table.selectionModel.leadSelectionIndex)
        }
        this.model = model
        this.table = table
        this.scrollPane = scrollPane
        engine = FilterEngine(log)
        filterBar.setSchema(log.schema)
        setFilterVisible(true)

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
        content.validate()
        content.repaint()
        split.setDividerLocation(AppState[AppState.DETAIL_DIVIDER]?.toDoubleOrNull()?.coerceIn(0.1, 0.95) ?: 0.75)
        // Remembered as a proportion, so it fits any window size; saved once dragging stops, as each save writes a file.
        var proportion = ""
        val saveDivider = Timer(300) { AppState[AppState.DETAIL_DIVIDER] = proportion }.apply { isRepeats = false }
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            val height = split.height - split.dividerSize
            if (height > 0 && split.isShowing) {
                proportion = "%.3f".format(java.util.Locale.ROOT, split.dividerLocation.toDouble() / height)
                saveDivider.restart()
            }
        }
        scrollPane.follow = follow
        applyFilter()
    }

    private fun showDetail(model: LogTableModel, row: Int) {
        detail.text = if (row < 0 || row >= model.rowCount) "" else model.log.recordText(model.recordAt(row), MAX_DETAIL_BYTES)
        detail.caretPosition = 0
    }

    /** Shows [record] again if it is selected; it may have grown, e.g. by a stack trace written after it. */
    private fun refreshDetail(record: Int) {
        val model = model ?: return
        val row = table?.selectionModel?.leadSelectionIndex ?: return
        if (row !in 0..<model.rowCount || model.recordAt(row) != record) return
        val text = model.log.recordText(record, MAX_DETAIL_BYTES)
        if (text == detail.text) return
        val caret = detail.caretPosition
        detail.text = text
        detail.caretPosition = caret.coerceAtMost(detail.document.length)
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

    /** Runs [activeFilter] over all records; with [keepView], keeps the rows on screen where they are. */
    private fun runFilter(keepView: Boolean) {
        filterJob?.cancel()
        val filter = activeFilter
        if (filter == null) {
            if (model?.isFiltered == true || !keepView) showRows(null, keepView)
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
            filteredRecords = result.recordCount
            filteredBytes = result.indexedSize
            // Still the current job (a replaced one would have been cancelled), but done.
            filterJob = null
            catchUpFilter()
        }
    }

    /**
     * Filters what was appended since the shown result, keeping the result for older records. Never
     * cancels a running filter: whichever finishes calls this again, so nothing appended is missed.
     */
    private fun catchUpFilter() {
        val filter = activeFilter ?: return
        val log = log ?: return
        val model = model ?: return
        if (filterJob?.isActive == true || !model.isFiltered || filteredBytes >= log.indexedSize) return
        // The last record covered may have grown since, so it is filtered again.
        val from = maxOf(0, filteredRecords - 1)
        val evaluation = engine?.prepare(filter, from) ?: return
        filterJob = scope.launch {
            val result = evaluation.run()
            model.replaceRowsFrom(from, result.rows)
            filteredRecords = result.recordCount
            filteredBytes = result.indexedSize
            scrollPane?.rowsAppended()
            updateStatus()
            filterJob = null
            catchUpFilter()
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
        setStatus(
            TabStatus(
                size = log.indexedSize,
                records = log.recordCount,
                shown = model?.filteredRowCount,
                unit = if (log.isRaw) "line" else "record",
                live = activity == FileActivity.LIVE,
                following = follow,
                indexMillis = indexMillis,
                message = problem,
            ),
        )
    }

    fun reloadFile() = reload()

    fun focusFilter() = filterBar.focusFilter()

    fun clearFilter() = filterBar.clear()

    private fun closeLog() {
        filterJob?.cancel()
        filteredRecords = 0
        filteredBytes = 0L
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
