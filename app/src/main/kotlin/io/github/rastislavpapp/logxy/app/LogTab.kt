package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.archive.ArchiveOrigin
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import io.github.rastislavpapp.logxy.core.query.Highlighter
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.query.QueryPrinter
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.core.query.TimeLiteral
import io.github.rastislavpapp.logxy.core.session.FilteredRows
import io.github.rastislavpapp.logxy.core.session.LogSession
import io.github.rastislavpapp.logxy.core.session.SessionListener
import io.github.rastislavpapp.logxy.core.session.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.ZoneId
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.UIManager
import javax.swing.text.JTextComponent

private const val MAX_DETAIL_BYTES = 1024 * 1024
private const val MAX_TITLE_CHARS = 40
private const val MAX_MENU_CHARS = 80
private const val MAX_RULE_CHARS = 1000
private const val MAX_UNTIMED_ROWS = 1000
private val RANGE_OPS = listOf(QueryOp.AT_LEAST, QueryOp.ABOVE, QueryOp.AT_MOST, QueryOp.BELOW)
private const val BOTTOM_TAB_PATTERNS = "patterns"

/** A search taking longer than this shows its progress. */
private const val SEARCH_PROGRESS_DELAY_MILLIS = 200L

/** Where the find box floats: below the top of the rows, left of the scroll bar. */
private const val FLOATING_TOP_GAP = 6
private const val FLOATING_RIGHT_GAP = 24

/** Records appended to a live log are searched at most this often. */
private const val SEARCH_APPENDED_MILLIS = 1000

/**
 * One open file, or several merged: shows the records of its [LogSession], with the filter bar and the
 * detail pane. [openFiles] opens dropped files; [openEach] opens files in tabs of their own. [filter]
 * is applied once the records are there. [origin] tells which files were extracted from zips or decompressed.
 */
class LogTab(
    paths: List<Path>,
    private val openFiles: (List<Path>) -> Unit,
    private val openEach: (List<Path>) -> Unit,
    filter: SavedFilter? = null,
    private val origin: (Path) -> ArchiveOrigin? = { null },
    /** The parser chosen for each of [paths], null (or missing) to detect it. */
    formats: List<LogFormat?> = emptyList(),
    private val onStatusChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val session = LogSession(paths, scope, AppSettings, Listener(), origin, formats)

    /** The files shown; for a merge, without those that turned out to have no timestamps. */
    val paths: List<Path> get() = session.paths

    /** The (first) file. */
    val path: Path get() = session.paths.first()

    val isMerged get() = session.isMerged

    /** The parser chosen for each of [paths], null where it is detected. */
    val chosenFormats: List<LogFormat?> get() = session.chosenFormats

    /** Short names of [paths], as the merge's source column shows them. */
    val sourceNames: List<String> get() = session.sourceNames

    /** Short name for the tab: the file name, or the merged files' names; files from archives go by the container's name. */
    val title: String
        get() {
            if (!isMerged) return origin(path)?.let { if (it.entry.isEmpty()) "${it.container.fileName}" else "${it.container.fileName}/${path.fileName}" } ?: path.fileName.toString()
            val names = paths.zip(sourceNames) { p, name -> origin(p)?.container?.fileName?.toString() ?: name }.distinct()
            val all = names.joinToString(" + ")
            return if (all.length <= MAX_TITLE_CHARS) all else "${names.first()} + ${names.size - 1} more"
        }

    /** The full path, or one per line for a merge; files from archives as `container!/entry`. */
    val description: String get() = paths.joinToString("\n") { (origin(it) ?: it).toString() }
    private val content = JPanel(BorderLayout())
    private val detail = LogDetailArea()
    private val filterBar = FilterBar(::applyFilter)
    private val findBar = FindBar(::searchFor) { forward -> find(forward) }
    private val patterns = PatternsPanel(scope, { session.patternCounts() }, { session.patternProgress }, filterBar::addRules)

    /** Below the table: the selected record, or the message patterns of the shown rows. */
    private val bottomTabs = JTabbedPane().apply {
        addTab("Detail", JScrollPane(detail, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER))
        addTab("Patterns", patterns)
        if (AppState[AppState.BOTTOM_TAB] == BOTTOM_TAB_PATTERNS) selectedComponent = patterns
        addChangeListener { AppState[AppState.BOTTOM_TAB] = if (selectedComponent === patterns) BOTTOM_TAB_PATTERNS else null }
    }
    private val followButton = accentToggle(Icons.follow, "Follow (tail)").apply {
        addActionListener { session.follow = isSelected }
    }
    private val reloadButton = toolBarButton(Icons.refresh, "Reload the file (F5)") { session.reload() }
    private val parserButton = dropDownButton("Parser: how lines are split into columns", maxWidth = 280) { showParsers() }
    private val columnsButton = toolBarButton(DropDownIcon(Icons.columns), "Columns to show") { showColumns() }.apply {
        isEnabled = false
    }

    /** Names of the columns the user hid; kept when the file is read again. */
    private var hiddenColumns = emptySet<String>()
    private var progressTimer: Timer? = null
    private var filterTimer: Timer? = null

    private var model: LogTableModel? = null
    private var table: LogTable? = null
    private var scrollPane: VirtualScrollPane? = null
    private var histogramBar: TimeHistogramBar? = null

    private var search: TextSearch? = null
    private var foundMarks = Highlighter.NONE

    /** Records the search found, in order; complete unless [searchJob] is running. */
    private var found = IntArray(0)
    private var searchJob: Job? = null

    /** Records were appended while [searchJob] ran, so it is to run again. */
    private var searchStale = false

    /** A find asked for while the search was running: forward, and whether the current record counts. */
    private var pendingFind: Pair<Boolean, Boolean>? = null
    private val searchAppended = Timer(SEARCH_APPENDED_MILLIS) { runSearch(jump = false) }.apply { isRepeats = false }

    /** What the status bar shows for this file. */
    val status: SessionStatus get() = session.status

    /** Nanoseconds from the first selected row's timestamp to the last's; null unless two rows with timestamps are selected. */
    val selectionSpan: Long?
        get() {
            val model = model ?: return null
            val selection = table?.selectionModel ?: return null
            val first = selection.minSelectionIndex
            val last = selection.maxSelectionIndex
            if (first < 0 || first == last || last >= model.rowCount) return null
            val start = model.records.timestamp(model.recordAt(first)) ?: return null
            val end = model.records.timestamp(model.recordAt(last)) ?: return null
            return end - start
        }

    // The file controls live outside the tab, and are not updated with it while another tab shows its own.
    override fun updateUI() {
        super.updateUI()
        @Suppress("SENSELESS_COMPARISON")
        if (fileControls != null && fileControls.parent == null) SwingUtilities.updateComponentTreeUI(fileControls)
    }

    /** Reload, Follow and the parser; placed by the window, outside this tab. */
    val fileControls: JComponent = JPanel(MigLayout("insets 0, gap 2")).apply {
        isOpaque = false
        add(reloadButton)
        add(followButton, "gapright 6")
        add(parserButton, "wmin 120")
        add(columnsButton)
    }

    init {
        add(filterBar, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        showFormat()
        filter?.let(filterBar::load)
        session.start()
    }

    /** Auto, raw text, the user's formats (editable), the built-in ones; plus the format in use if it is none of those. */
    private fun showParsers() {
        if (isMerged) return showMergedParsers()
        val chosen = session.chosenFormat
        val custom = AppSettings.current.formats
        val known = listOf(LogFormat.Raw) + custom + AppSettings.current.builtInFormats
        val items = buildList {
            add(PopupItem(autoLabel(), selected = chosen == null) { session.useFormat(null) })
            (if (chosen == null || chosen in known) known else known + chosen).forEachIndexed { i, format ->
                val tools = if (format in custom) listOf(PopupTool(Icons.advanced, "Edit this parser") { editParsers(format) }) else emptyList()
                add(PopupItem(format.name, selected = format == chosen, separatorBefore = i == 0, tools = tools) { session.useFormat(format) })
            }
            add(PopupItem("Edit Parsers", Icons.settings, separatorBefore = true) { editParsers(chosen ?: session.format) })
            // A guessed format is none of the known ones; saving it makes it one the user can correct.
            val detected = session.format.takeIf { chosen == null && session.formatDetected && it !in known }
            if (detected != null) add(PopupItem("Save Detected Parser…") { editParsers(detected, adopt = true) })
        }
        showPopupList(parserButton, items)
    }

    /**
     * Like [showParsers], but a parser applies to all files of the merge; "Choose Parser per File" opens
     * the table of files and their parsers, also checked when the files' parsers differ.
     */
    private fun showMergedParsers() {
        val chosen = session.chosenFormats
        val mixed = chosen.distinct().size > 1
        // The parser of all files when not mixed; null is Auto-detect.
        val same = chosen.first()
        val custom = AppSettings.current.formats
        val known = listOf(LogFormat.Raw) + custom + AppSettings.current.builtInFormats
        val items = buildList {
            val auto = if (session.formatDetected) "Auto: per file" else "Auto-detect"
            add(PopupItem(auto, selected = !mixed && same == null) { session.useFormats(chosen.map { null }) })
            known.forEachIndexed { i, format ->
                val tools = if (format in custom) listOf(PopupTool(Icons.advanced, "Edit this parser") { editParsers(format) }) else emptyList()
                add(PopupItem(format.name, selected = !mixed && same == format, separatorBefore = i == 0, tools = tools) {
                    session.useFormats(chosen.map { format })
                })
            }
            add(PopupItem("Choose Parser per File", selected = mixed, separatorBefore = true) { chooseParserPerFile() })
            add(PopupItem("Edit Parsers", Icons.settings) { editParsers(session.format) })
        }
        showPopupList(parserButton, items)
    }

    private fun chooseParserPerFile() {
        val descriptions = paths.map { (origin(it) ?: it).toString() }
        val dialog = FileParsersDialog(SwingUtilities.getWindowAncestor(this), paths, sourceNames, descriptions, session.chosenFormats)
        dialog.isVisible = true
        session.useFormats(dialog.formats ?: return)
    }

    /** Checks for the columns to show; the menu stays open while they are toggled. */
    private fun showColumns() {
        val model = model ?: return
        val popup = JPopupMenu()
        val boxes = model.columnChoices.map { field ->
            JCheckBoxMenuItem(field.name, field.name !in hiddenColumns).apply {
                putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true)
            }
        }
        // At least one column stays: the last checked one cannot be unchecked.
        fun enableBoxes() {
            val single = boxes.count { it.isSelected } == 1
            boxes.forEach { it.isEnabled = !single || !it.isSelected }
        }
        fun update() {
            hideColumns(boxes.filter { !it.isSelected }.map { it.text }.toSet())
            enableBoxes()
        }
        boxes.forEach { box ->
            box.addActionListener { update() }
            popup.add(box)
        }
        popup.addSeparator()
        popup.add(JMenuItem("Show All").apply {
            addActionListener {
                boxes.forEach { it.isSelected = true }
                update()
            }
        })
        enableBoxes()
        popup.show(columnsButton, 0, columnsButton.height)
    }

    /** The right-click menu of a column header: hide that column, or show all again. */
    private fun showHeaderMenu(header: JComponent, column: Int, x: Int, y: Int) {
        val model = model ?: return
        val field = model.fieldAt(column) ?: return
        val popup = JPopupMenu()
        popup.add(JMenuItem("Hide “${field.name}”").apply {
            isEnabled = model.columnChoices.count { it.name !in hiddenColumns } > 1
            addActionListener { hideColumns(hiddenColumns + field.name) }
        })
        popup.add(JMenuItem("Show All Columns").apply {
            isEnabled = hiddenColumns.isNotEmpty()
            addActionListener { hideColumns(emptySet()) }
        })
        popup.addSeparator()
        popup.add(JMenuItem("Copy Column").apply {
            toolTipText = "The whole value of “${field.name}” of every shown row, one row per line"
            addActionListener {
                copyColumn(model.records, model.records.schema.fields.indexOf(field), IntArray(model.totalRowCount) { model.recordAtIndex(it) })
            }
        })
        popup.show(header, x, y)
    }

    /** Copies [field] of [records], one per line; reading many may take a while, so it is done off the UI thread. */
    private fun copyColumn(log: Records, field: Int, records: IntArray) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                buildString { for (record in records) append(log.fieldText(record, field)).append('\n') }
            }
            copy(text)
        }
    }

    private fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    /** The right-click menu of a cell: show only records with that value, or all but them. */
    private fun showCellMenu(table: LogTable, row: Int, column: Int, x: Int, y: Int) {
        val model = model ?: return
        val field = model.fieldAt(column) ?: return
        val fieldIndex = model.records.schema.fields.indexOf(field)
        // The whole value, not the cell's text, which is cut to its first line and has its tabs expanded.
        val value = model.records.fieldText(model.recordAt(row), fieldIndex)
        val level = model.level(row)?.takeIf { field.role == Role.LEVEL && it != Level.UNKNOWN }
        val firstLine = value.lineSequence().first()
        val escaped = firstLine.take(MAX_RULE_CHARS).replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
        // A long or multi-line value, such as a message with its stack trace, is matched by its start.
        val pattern = if (firstLine.length > MAX_RULE_CHARS || firstLine.length < value.length) "$escaped*" else escaped
        fun rule(op: QueryOp) =
            if (level != null) Query.Rule(field.name, op, level.name, bare = true) else Query.Rule(field.name, op, pattern)
        val popup = JPopupMenu()
        popup.add(JMenuItem("Copy Cell Value").apply {
            addActionListener { copy(model.records.fieldText(model.recordAt(row), fieldIndex)) }
        })
        // Within a selection of several rows, the menu also copies from all of them.
        if (table.isRowSelected(row) && table.selectedRowCount > 1) {
            popup.add(JMenuItem("Copy Selected Rows").apply {
                addActionListener { table.transferHandler.exportToClipboard(table, Toolkit.getDefaultToolkit().systemClipboard, TransferHandler.COPY) }
            })
            popup.add(JMenuItem("Copy Column “${field.name}”").apply {
                addActionListener { copyColumn(model.records, fieldIndex, table.selectedRows.map(model::recordAt).toIntArray()) }
            })
        }
        popup.addSeparator()
        fun add(rule: Query.Rule) {
            val text = QueryPrinter.print(rule).let { if (it.length > MAX_MENU_CHARS) it.take(MAX_MENU_CHARS - 1) + "…" else it }
            popup.add(JMenuItem(text, Icons.filter).apply {
                addActionListener { filterBar.addRule(rule) }
            })
        }
        // `== ""` is a placeholder that filters nothing; `!= ""` keeps records with a value.
        if (value.isNotEmpty()) add(rule(QueryOp.EQUALS))
        add(rule(QueryOp.NOT_EQUALS))
        val template = if (field.role == Role.MESSAGE && model.records.schema.hasPatterns()) session.templateOf(model.recordAt(row)) else null
        if (template != null) {
            popup.addSeparator()
            add(Query.Rule(Query.PATTERN_FIELD, QueryOp.EQUALS, template))
            add(Query.Rule(Query.PATTERN_FIELD, QueryOp.NOT_EQUALS, template))
        }
        if (level != null) {
            popup.addSeparator()
            for (op in RANGE_OPS) add(Query.Rule(field.name, op, level.name, bare = true))
        }
        // The literal is written from the record's time, as the log's own layout may not parse back (e.g. offsets).
        val time = model.records.timestamp(model.recordAt(row))?.takeIf { field.type == ColumnType.TIMESTAMP }
        if (time != null) {
            val literal = TimeLiteral.format(time, ZoneId.systemDefault())
            popup.addSeparator()
            for (op in RANGE_OPS) add(Query.Rule(field.name, op, literal))
        }
        popup.addSeparator()
        popup.add(JMenuItem(if (filterBar.suspended) "Resume Filter" else "Suspend Filter", Icons.pause).apply {
            isEnabled = filterBar.canSuspend
            addActionListener { filterBar.toggleSuspended() }
        })
        popup.show(table, x, y)
    }

    private fun hideColumns(names: Set<String>) {
        hiddenColumns = names
        model?.hiddenColumns = names
    }

    private fun autoLabel() = if (session.formatDetected) "Auto: ${session.format.name}" else "Auto-detect"

    private fun editParsers(select: LogFormat, adopt: Boolean = false) {
        val dialog = PreferencesDialog(
            SwingUtilities.getWindowAncestor(this), PreferencesDialog.Section.PARSERS, path, select, offerUse = !isMerged,
            adopt = select.takeIf { adopt },
        )
        dialog.isVisible = true
        session.useFormat(dialog.formatToUse ?: return)
    }

    /** Shows the format in use on the parser button. */
    private fun showFormat() {
        parserButton.text = when {
            isMerged -> when {
                session.chosenFormats.any { it != null } -> "Per file"
                session.formatDetected -> "Auto: per file"
                else -> "Auto-detect"
            }
            else -> session.chosenFormat?.name ?: autoLabel()
        }
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
    }

    private fun showProgress(fraction: () -> Double) {
        val progressBar = JProgressBar(0, 1000)
        showCentered(JLabel("Indexing $title…"), progressBar)
        stopProgress()
        progressTimer = Timer(100) { progressBar.value = (fraction() * 1000).toInt() }.apply { start() }
    }

    private fun stopProgress() {
        progressTimer?.stop()
        progressTimer = null
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

    private fun showTable(records: Records) {
        val model = LogTableModel(records)
        // Columns hidden before stay hidden, unless that would hide them all (the file changed meanwhile).
        model.hiddenColumns = hiddenColumns.takeIf { hidden -> model.columnChoices.any { it.name !in hidden } }.orEmpty()
        val table = LogTable(model)
        columnsButton.isEnabled = true
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = cellMenu(e)
            override fun mouseReleased(e: MouseEvent) = cellMenu(e)

            private fun cellMenu(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                if (e.isPopupTrigger && row >= 0 && column >= 0) showCellMenu(table, row, column, e.x, e.y)
            }
        })
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            // The popup trigger comes with the press on some systems, with the release on others.
            override fun mousePressed(e: MouseEvent) = headerMenu(e)
            override fun mouseReleased(e: MouseEvent) = headerMenu(e)

            private fun headerMenu(e: MouseEvent) {
                val column = table.tableHeader.columnAtPoint(e.point)
                if (e.isPopupTrigger && column >= 0) showHeaderMenu(table.tableHeader, column, e.x, e.y)
            }
        })
        // Copying rows gives the records as written; file drops on the table still open files.
        table.transferHandler = FileDropHandler(openFiles, RecordCopyHandler(model))
        val scrollPane = VirtualScrollPane(table, model)
        scrollPane.onFollowChanged = { session.follow = it }
        scrollPane.resumeFollowAtEnd = { session.isLive }
        val timestampField = records.schema.fields.firstOrNull { it.role == Role.TIMESTAMP }?.name
        scrollPane.onRowsInViewChanged = ::showMarkedTimes
        val histogramBar = timestampField?.let { field ->
            TimeHistogramBar { session.histogram }.apply {
                onJump = ::jumpToTime
                onRange = { from, to -> filterBar.setTimeRange(field, timeLiteral(from), timeLiteral(to)) }
                onShowAllTimes = { filterBar.setTimeRange(field, null, null) }
                hasTimeRange = { filterBar.hasTimeRange(field) }
            }
        }
        table.selectionModel.addListSelectionListener { event ->
            showMarkedTimes()
            if (!event.valueIsAdjusting) {
                showDetail(model, table.selectionModel.leadSelectionIndex)
                showFoundCount()
                onStatusChanged()
            }
        }
        this.model = model
        this.table = table
        this.scrollPane = scrollPane
        this.histogramBar = histogramBar
        table.found = foundMarks
        filterBar.setSchema(records.schema)
        patterns.canFilter = records.schema.hasPatterns()
        setFilterVisible(true)

        detail.text = ""
        val rowsPanel = JPanel(BorderLayout()).apply {
            histogramBar?.let { add(it, BorderLayout.NORTH) }
            add(FloatingTopRight(scrollPane, findBar), BorderLayout.CENTER)
        }
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, rowsPanel, bottomTabs).apply {
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
        scrollPane.follow = session.follow
        applyFilter()
    }

    private fun showDetail(model: LogTableModel, row: Int) {
        detail.show(if (row < 0 || row >= model.rowCount) "" else model.records.recordText(model.recordAt(row), MAX_DETAIL_BYTES))
        // A long record, e.g. with a stack trace, shows where the find bar's text is.
        detail.caretPosition = detail.firstFound
    }

    /** Shows [record] again if it is selected; it may have grown, e.g. by a stack trace written after it. */
    private fun refreshDetail(record: Int) {
        val model = model ?: return
        val row = table?.selectionModel?.leadSelectionIndex ?: return
        if (row !in 0..<model.rowCount || model.recordAt(row) != record) return
        val text = model.records.recordText(record, MAX_DETAIL_BYTES)
        if (text == detail.text) return
        val caret = detail.caretPosition
        detail.show(text)
        detail.caretPosition = caret.coerceAtMost(detail.document.length)
    }

    /** Applies the filter bar's level and query to this file's records. */
    private fun applyFilter() {
        if (session.records == null) return
        try {
            // While suspended the query is not read: text that does not parse must not keep it from showing all.
            val query = if (filterBar.suspended) null else filterBar.query()
            if (query == null) session.applyFilter(null, Query.Group(all = true, items = emptyList()))
            else session.applyFilter(filterBar.minimumLevel, query)
            val matches = query?.let(Highlighter::of) ?: Highlighter.NONE
            table?.matches = matches
            detail.matches = matches
            filterBar.showError(null)
        } catch (e: QueryException) {
            filterBar.showError(e.message)
        }
    }

    private fun showRows(rows: FilteredRows?, keepView: Boolean) {
        val model = model ?: return
        val table = table ?: return
        val scrollPane = scrollPane ?: return
        val selected = table.selectedRow.takeIf { it >= 0 }?.let { model.recordAt(it) }
        val top = if (model.totalRowCount > 0) model.recordAtIndex(scrollPane.firstVisibleRow) else null
        model.showRows(rows)
        scrollPane.rowsReplaced()
        patterns.refresh()
        val selectedIndex = selected?.let { record -> model.indexOfRecord(record).takeIf { it >= 0 && model.recordAtIndex(it) == record } }
        if (!session.follow) {
            if (keepView && top != null) {
                scrollPane.scrollToRow(maxOf(0, model.indexOfRecord(top)))
            } else if (selectedIndex != null) {
                scrollPane.selectRow(selectedIndex)
            }
        }
        if (selectedIndex != null) scrollPane.selectRow(selectedIndex, scroll = false)
        if (search != null) runSearch(jump = false)
    }

    /** Opens the find bar, to find the text selected in the detail pane if it is one line. */
    fun showFind() {
        findBar.open(detail.selectedText?.takeIf { it.isNotBlank() && '\n' !in it })
    }

    /** Marks [search] and finds the records it matches, going to the first from the current one on. */
    private fun searchFor(search: TextSearch?) {
        this.search = search
        foundMarks = search?.let(Highlighter::of) ?: Highlighter.NONE
        table?.found = foundMarks
        detail.found = foundMarks
        found = IntArray(0)
        if (!findBar.isVisible) table?.requestFocusInWindow()
        runSearch(jump = search != null)
    }

    /** Finds the shown records [search] matches; with [jump], then selects the first from the current one on. */
    private fun runSearch(jump: Boolean) {
        searchJob?.cancel()
        searchJob = null
        searchStale = false
        searchAppended.stop()
        val evaluation = try {
            search?.let(session::prepareSearch)
        } catch (e: QueryException) {
            found = IntArray(0)
            pendingFind = null
            findBar.showError(e.message)
            return
        }
        if (evaluation == null) {
            found = IntArray(0)
            showFoundCount()
            return
        }
        if (jump) {
            pendingFind = true to true
            findBar.showCount(0, null)
        }
        searchJob = scope.launch {
            val progress = launch {
                delay(SEARCH_PROGRESS_DELAY_MILLIS)
                while (true) {
                    findBar.showCount(0, null, evaluation.progress)
                    delay(100)
                }
            }
            val result = try {
                evaluation.run()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                searchJob = null
                pendingFind = null
                findBar.showError(e.message ?: e.javaClass.simpleName)
                return@launch
            } finally {
                progress.cancel()
            }
            found = result.rows
            searchJob = null
            showFoundCount()
            pendingFind?.let { (forward, inclusive) -> find(forward, inclusive) }
            if (searchStale) searchAppended.start()
        }
    }

    /** Searches appended records soon; not at once, as a live log may grow every moment. */
    private fun searchAppendedSoon() {
        if (search == null) return
        if (searchJob != null) searchStale = true else if (!searchAppended.isRunning) searchAppended.start()
    }

    /**
     * Selects the next record the search found after the current one, or the previous before it, wrapping around.
     * The current record is the selected one, or the top one in view if none is; with [inclusive], or if none is
     * selected, it can be found itself.
     */
    private fun find(forward: Boolean, inclusive: Boolean = false) {
        if (search == null) return findBar.open()
        pendingFind = null
        if (searchJob != null) {
            pendingFind = forward to inclusive
            return
        }
        val model = model ?: return
        val table = table ?: return
        val scrollPane = scrollPane ?: return
        if (found.isEmpty() || model.totalRowCount == 0) return
        val lead = table.selectionModel.leadSelectionIndex
        val selected = lead.takeIf { it in 0..<model.rowCount && table.selectionModel.isSelectedIndex(it) }
        val current = selected?.let(model::recordAt) ?: model.recordAtIndex(scrollPane.firstVisibleRow)
        val include = inclusive || selected == null
        // The first found record at or after the current one.
        val at = found.binarySearch(current).let { if (it >= 0) it else -it - 1 }
        val isCurrent = at < found.size && found[at] == current
        var i = when {
            forward -> if (isCurrent && !include) at + 1 else at
            else -> if (isCurrent && include) at else at - 1
        }
        // A record found may not be shown yet, while the filter's result is still on its way.
        repeat(found.size) {
            i = Math.floorMod(i, found.size)
            val record = found[i]
            val index = model.indexOfRecord(record)
            if (index >= 0 && model.recordAtIndex(index) == record) {
                session.follow = false
                scrollPane.selectRow(index, scroll = index !in scrollPane.firstVisibleRow..scrollPane.lastVisibleRow)
                return
            }
            i += if (forward) 1 else -1
        }
    }

    /** Shows how many records the search found, and which of them is selected. */
    private fun showFoundCount() {
        if (searchJob != null) return
        val model = model
        val lead = table?.selectionModel?.leadSelectionIndex ?: -1
        val selected = if (model != null && lead in 0..<model.rowCount) model.recordAt(lead) else -1
        val position = if (selected >= 0) found.binarySearch(selected) else -1
        findBar.showCount(if (position >= 0) position + 1 else 0, found.size)
    }

    /** Marks the times of the selected rows on the histogram, or of the top row in view if none is selected. */
    private fun showMarkedTimes() {
        val bar = histogramBar ?: return
        val model = model ?: return
        val scrollPane = scrollPane ?: return
        val selection = table?.selectionModel ?: return
        val first = selection.minSelectionIndex
        val last = selection.maxSelectionIndex
        bar.markedTimes = if (first >= 0 && last < model.rowCount) {
            val from = model.records.timestamp(model.recordAt(first))
            val to = model.records.timestamp(model.recordAt(last))
            if (from != null && to != null) minOf(from, to) to maxOf(from, to) else (from ?: to)?.let { it to it }
        } else if (model.totalRowCount > 0) {
            model.records.timestamp(model.recordAtIndex(scrollPane.firstVisibleRow))?.let { it to it }
        } else {
            null
        }
    }

    /** Selects the first shown row at [time] or later, or the last row if none is. */
    private fun jumpToTime(time: Long) {
        val model = model ?: return
        val scrollPane = scrollPane ?: return
        if (model.totalRowCount == 0) return
        // Rows are about in time order; a few out of order only make the jump land near them.
        var low = 0
        var high = model.totalRowCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (timeFrom(model, middle) < time) low = middle + 1 else high = middle
        }
        session.follow = false
        scrollPane.selectRow(minOf(low, model.totalRowCount - 1))
        table?.requestFocusInWindow()
    }

    /** The time of the shown row at [index], or of the next one that has a time; [Long.MAX_VALUE] if none near. */
    private fun timeFrom(model: LogTableModel, index: Int): Long {
        for (row in index..<minOf(model.totalRowCount, index + MAX_UNTIMED_ROWS)) {
            val time = model.records.timestampNanos(model.recordAtIndex(row))
            if (time != Records.NO_TIMESTAMP) return time
        }
        return Long.MAX_VALUE
    }

    private fun timeLiteral(nanos: Long) = TimeLiteral.format(nanos, ZoneId.systemDefault())

    /** Follows a slow filter's progress on the filter bar while [filtering]. */
    private fun showFilterProgress(filtering: Boolean) {
        if (filtering == (filterTimer != null)) return
        filterTimer?.stop()
        filterTimer = null
        filterBar.showProgress(if (filtering) session.filterProgress else null)
        if (filtering) filterTimer = Timer(50) { filterBar.showProgress(session.filterProgress) }.apply { start() }
    }

    fun reloadFile() = session.reload()

    fun focusFilter() = filterBar.focusFilter()

    /** Goes to the next record with the find bar's text, or the previous; opens the bar if it is closed. */
    fun findNext(forward: Boolean) = find(forward)

    fun clearFilter() = filterBar.clear()

    /** The filter as edited in this tab. */
    val filter: SavedFilter get() = filterBar.current()

    /** Cancels any running work and releases the file. */
    fun dispose() {
        stopProgress()
        patterns.reset()
        showFilterProgress(false)
        searchAppended.stop()
        session.dispose()
        scope.cancel()
        filterBar.release()
        stopCarets(this)
    }

    // A caret blinks on a shared Swing timer, which would otherwise keep the closed tab, and its records, in memory.
    private fun stopCarets(component: Component) {
        if (component is JTextComponent) component.caret?.blinkRate = 0
        if (component is Container) component.components.forEach(::stopCarets)
    }

    /** Shows [floating] at its preferred size over the top right corner of [base], clear of the scroll bar. */
    private class FloatingTopRight(private val base: JComponent, private val floating: JComponent) : JLayeredPane() {
        init {
            // As Any: an Int would pick the overload that takes an index, not a layer.
            add(base, DEFAULT_LAYER as Any)
            add(floating, PALETTE_LAYER as Any)
        }

        override fun doLayout() {
            base.setBounds(0, 0, width, height)
            val size = floating.preferredSize
            floating.setBounds(maxOf(0, width - size.width - FLOATING_RIGHT_GAP), FLOATING_TOP_GAP, minOf(size.width, width), size.height)
        }

        override fun getPreferredSize(): Dimension = base.preferredSize

        override fun getMinimumSize(): Dimension = base.minimumSize
    }

    private inner class Listener : SessionListener {
        override fun indexing(fraction: () -> Double) {
            columnsButton.isEnabled = false
            searchJob?.cancel()
            searchJob = null
            found = IntArray(0)
            histogramBar = null
            patterns.reset()
            model = null
            table = null
            scrollPane = null
            showProgress(fraction)
        }

        override fun problem(title: String, detail: String, hint: String?) {
            stopProgress()
            showProblem(title, detail, hint)
        }

        override fun loaded(records: Records) {
            stopProgress()
            showTable(records)
        }

        override fun notMergeable(paths: List<Path>) {
            // Not while the session is still loading the others.
            SwingUtilities.invokeLater { openEach(paths) }
            onStatusChanged()
        }

        override fun formatChanged() = showFormat()

        override fun fieldsShown() {
            val model = model ?: return
            model.schemaChanged()
            filterBar.setSchema(model.records.schema)
        }

        override fun recordsAppended(oldRecordCount: Int, changed: IntArray) {
            model?.recordsAppended(oldRecordCount, changed)
            changed.forEach(::refreshDetail)
            if (session.shown == null) {
                scrollPane?.rowsAppended()
                patterns.refreshSoon()
                searchAppendedSoon()
            }
        }

        override fun rowsReplaced(rows: FilteredRows?, keepView: Boolean) = showRows(rows, keepView)

        override fun rowsExtended(firstChanged: Int, oldSize: Int) {
            model?.rowsChangedFrom(firstChanged, oldSize)
            scrollPane?.rowsAppended()
            patterns.refreshSoon()
            searchAppendedSoon()
        }

        override fun levelMappingChanged() {
            model?.let { it.fireTableRowsUpdated(0, maxOf(0, it.rowCount - 1)) }
            patterns.refresh()
        }

        override fun histogramChanged() {
            showMarkedTimes()
            histogramBar?.repaint()
        }

        override fun followChanged(follow: Boolean) {
            followButton.isSelected = follow
            scrollPane?.follow = follow
        }

        override fun statusChanged(status: SessionStatus) {
            showFilterProgress(status.filtering)
            onStatusChanged()
        }
    }
}
