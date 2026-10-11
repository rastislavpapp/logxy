package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.query.QueryPrinter
import io.github.rastislavpapp.logxy.core.query.TimeLiteral
import io.github.rastislavpapp.logxy.core.session.FilteredRows
import io.github.rastislavpapp.logxy.core.session.LogSession
import io.github.rastislavpapp.logxy.core.session.SessionListener
import io.github.rastislavpapp.logxy.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.swing.Swing
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.ZoneId
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

private const val MAX_DETAIL_BYTES = 1024 * 1024
private const val MAX_TITLE_CHARS = 40
private const val MAX_MENU_CHARS = 80
private val RANGE_OPS = listOf(QueryOp.AT_LEAST, QueryOp.ABOVE, QueryOp.AT_MOST, QueryOp.BELOW)

/**
 * One open file, or several merged: shows the records of its [LogSession], with the filter bar and the
 * detail pane. [openFiles] opens dropped files; [openEach] opens files in tabs of their own. [filter]
 * is applied once the records are there. [origin] tells which files were extracted from zips.
 */
class LogTab(
    paths: List<Path>,
    private val openFiles: (List<Path>) -> Unit,
    private val openEach: (List<Path>) -> Unit,
    filter: SavedFilter? = null,
    private val origin: (Path) -> ZipSpool.Origin? = { null },
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

    /** Short name for the tab: the file name, or the merged files' names; files from zips go by the zip's name. */
    val title: String
        get() {
            if (!isMerged) return origin(path)?.let { "${it.zip.fileName}/${path.fileName}" } ?: path.fileName.toString()
            val names = paths.zip(sourceNames) { path, name -> origin(path)?.zip?.fileName?.toString() ?: name }.distinct()
            val all = names.joinToString(" + ")
            return if (all.length <= MAX_TITLE_CHARS) all else "${names.first()} + ${names.size - 1} more"
        }

    /** The full path, or one per line for a merge; files from zips as `zip!/entry`. */
    val description: String get() = paths.joinToString("\n") { (origin(it) ?: it).toString() }
    private val content = JPanel(BorderLayout())
    private val detail = LogDetailArea()
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
    private val filterBar = FilterBar(::applyFilter)
    private var progressTimer: Timer? = null
    private var filterTimer: Timer? = null

    private var model: LogTableModel? = null
    private var table: LogTable? = null
    private var scrollPane: VirtualScrollPane? = null
    private var levelMarks: LevelMarks? = null

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
        val known = listOf(LogFormat.Raw) + custom + LogFormat.BUILT_IN
        val items = buildList {
            add(PopupItem(autoLabel(), selected = chosen == null) { session.useFormat(null) })
            (if (chosen == null || chosen in known) known else known + chosen).forEachIndexed { i, format ->
                val tools = if (format in custom) listOf(PopupTool(Icons.advanced, "Edit this parser") { editParsers(format) }) else emptyList()
                add(PopupItem(format.name, selected = format == chosen, separatorBefore = i == 0, tools = tools) { session.useFormat(format) })
            }
            add(PopupItem("Edit Parsers", Icons.settings, separatorBefore = true) { editParsers(chosen ?: session.format) })
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
        val known = listOf(LogFormat.Raw) + custom + LogFormat.BUILT_IN
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
        popup.show(header, x, y)
    }

    /** The right-click menu of a cell: show only records with that value, or all but them. */
    private fun showCellMenu(table: LogTable, row: Int, column: Int, x: Int, y: Int) {
        val model = model ?: return
        val field = model.fieldAt(column) ?: return
        val value = model.getValueAt(row, column) as String
        val level = model.level(row)?.takeIf { field.role == Role.LEVEL && it != Level.UNKNOWN }
        val pattern = value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
        fun rule(op: QueryOp) =
            if (level != null) Query.Rule(field.name, op, level.name, bare = true) else Query.Rule(field.name, op, pattern)
        val popup = JPopupMenu()
        fun add(rule: Query.Rule) {
            val text = QueryPrinter.print(rule).let { if (it.length > MAX_MENU_CHARS) it.take(MAX_MENU_CHARS - 1) + "…" else it }
            popup.add(JMenuItem(text, Icons.filter).apply {
                addActionListener { filterBar.addRule(rule) }
            })
        }
        // `== ""` is a placeholder that filters nothing; `!= ""` keeps records with a value.
        if (value.isNotEmpty()) add(rule(QueryOp.EQUALS))
        add(rule(QueryOp.NOT_EQUALS))
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
        popup.show(table, x, y)
    }

    private fun hideColumns(names: Set<String>) {
        hiddenColumns = names
        model?.hiddenColumns = names
    }

    private fun autoLabel() = if (session.formatDetected) "Auto: ${session.format.name}" else "Auto-detect"

    private fun editParsers(select: LogFormat) {
        val dialog = PreferencesDialog(SwingUtilities.getWindowAncestor(this), PreferencesDialog.Section.PARSERS, path, select, offerUse = !isMerged)
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
        // The table has its own transfer handler (for copy), which would otherwise swallow file drops.
        table.transferHandler = FileDropHandler(openFiles, table.transferHandler)
        val scrollPane = VirtualScrollPane(table, model)
        scrollPane.onFollowChanged = { session.follow = it }
        scrollPane.resumeFollowAtEnd = { session.isLive }
        val levelMarks = LevelMarks(model, scope, scrollPane::repaintMarks)
        scrollPane.marks = levelMarks
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                showDetail(model, table.selectionModel.leadSelectionIndex)
                onStatusChanged()
            }
        }
        this.model = model
        this.table = table
        this.scrollPane = scrollPane
        this.levelMarks = levelMarks
        filterBar.setSchema(records.schema)
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
        scrollPane.follow = session.follow
        applyFilter()
    }

    private fun showDetail(model: LogTableModel, row: Int) {
        detail.text = if (row < 0 || row >= model.rowCount) "" else model.records.recordText(model.recordAt(row), MAX_DETAIL_BYTES)
        detail.caretPosition = 0
    }

    /** Shows [record] again if it is selected; it may have grown, e.g. by a stack trace written after it. */
    private fun refreshDetail(record: Int) {
        val model = model ?: return
        val row = table?.selectionModel?.leadSelectionIndex ?: return
        if (row !in 0..<model.rowCount || model.recordAt(row) != record) return
        val text = model.records.recordText(record, MAX_DETAIL_BYTES)
        if (text == detail.text) return
        val caret = detail.caretPosition
        detail.text = text
        detail.caretPosition = caret.coerceAtMost(detail.document.length)
    }

    /** Applies the filter bar's level and query to this file's records. */
    private fun applyFilter() {
        if (session.records == null) return
        try {
            session.applyFilter(filterBar.minimumLevel, filterBar.query())
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
        levelMarks?.reset()
        val selectedIndex = selected?.let { record -> model.indexOfRecord(record).takeIf { it >= 0 && model.recordAtIndex(it) == record } }
        if (!session.follow) {
            if (keepView && top != null) {
                scrollPane.scrollToRow(maxOf(0, model.indexOfRecord(top)))
            } else if (selectedIndex != null) {
                scrollPane.selectRow(selectedIndex)
            }
        }
        if (selectedIndex != null) scrollPane.selectRow(selectedIndex, scroll = false)
    }

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

    fun clearFilter() = filterBar.clear()

    /** The filter as edited in this tab. */
    val filter: SavedFilter get() = filterBar.current()

    /** Cancels any running work and releases the file. */
    fun dispose() {
        stopProgress()
        showFilterProgress(false)
        session.dispose()
        scope.cancel()
    }

    private inner class Listener : SessionListener {
        override fun indexing(fraction: () -> Double) {
            columnsButton.isEnabled = false
            levelMarks?.cancel()
            levelMarks = null
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

        override fun recordsAppended(oldRecordCount: Int, changed: IntArray) {
            model?.recordsAppended(oldRecordCount, changed)
            changed.forEach(::refreshDetail)
            if (session.shown == null) {
                scrollPane?.rowsAppended()
                levelMarks?.changedFrom(changed.fold(oldRecordCount, ::minOf))
            }
        }

        override fun rowsReplaced(rows: FilteredRows?, keepView: Boolean) = showRows(rows, keepView)

        override fun rowsExtended(firstChanged: Int, oldSize: Int) {
            model?.rowsChangedFrom(firstChanged, oldSize)
            scrollPane?.rowsAppended()
            levelMarks?.changedFrom(firstChanged)
        }

        override fun levelMappingChanged() {
            model?.let { it.fireTableRowsUpdated(0, maxOf(0, it.rowCount - 1)) }
            levelMarks?.reset()
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
