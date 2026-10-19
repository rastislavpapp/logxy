package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.archive.ArchiveOrigin
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.query.Highlighter
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.query.QueryPrinter
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.core.query.TimeLiteral
import io.github.rastislavpapp.logxy.core.session.CopyResult
import io.github.rastislavpapp.logxy.core.session.LogSession
import io.github.rastislavpapp.logxy.core.session.MAX_COPY_BYTES
import io.github.rastislavpapp.logxy.core.session.NOT_YET_FOUND
import io.github.rastislavpapp.logxy.core.session.PreparedCopy
import io.github.rastislavpapp.logxy.core.session.RowRanges
import io.github.rastislavpapp.logxy.core.session.SessionListener
import io.github.rastislavpapp.logxy.core.session.SessionStatus
import io.github.rastislavpapp.logxy.core.session.ShownLog
import io.github.rastislavpapp.logxy.core.session.copyLimit
import io.github.rastislavpapp.logxy.core.template.Templates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.text.NumberFormat
import java.time.ZoneId
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JMenuItem
import javax.swing.JOptionPane
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
private const val MAX_RULE_CHARS = 1000
private val RANGE_OPS = listOf(QueryOp.AT_LEAST, QueryOp.ABOVE, QueryOp.AT_MOST, QueryOp.BELOW)
private const val BOTTOM_TAB_TEMPLATES = "templates"

/** The tab was called Patterns before, and app state may still name it so. */
private val BOTTOM_TAB_TEMPLATES_STORED = setOf(BOTTOM_TAB_TEMPLATES, "patterns")

/** A search taking longer than this shows its progress. */
private const val SEARCH_PROGRESS_DELAY_MILLIS = 200

/** Where the find box floats: below the top of the rows, left of the scroll bar. */
private const val FLOATING_TOP_GAP = 6
private const val FLOATING_RIGHT_GAP = 24

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
    parsers: List<Parser?> = emptyList(),
    private val onStatusChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val session = LogSession(paths, scope, AppSettings, Listener(), origin, parsers)

    /** The files shown; for a merge, without those that turned out to have no timestamps. */
    val paths: List<Path> get() = session.paths

    /** The (first) file. */
    val path: Path get() = session.paths.first()

    val isMerged get() = session.isMerged

    /** The parser chosen for each of [paths], null where it is detected. */
    val chosenParsers: List<Parser?> get() = session.chosenParsers

    /** Short names of [paths], as the merge's source column shows them. */
    val sourceNames: List<String> get() = session.sourceNames

    /** Kept when the file is read again, as are the renames for this tab only. */
    private val columnNames = ColumnNames()

    /**
     * Short name for the tab: the file name, or the merged files' names; files from archives go by the container's
     * name.
     */
    val title: String
        get() {
            if (!isMerged) {
                val origin = origin(path) ?: return path.fileName.toString()
                val container = origin.container.fileName
                return if (origin.entry.isEmpty()) "$container" else "$container/${path.fileName}"
            }
            val names = paths
                .zip(sourceNames) { p, name -> origin(p)?.container?.fileName?.toString() ?: name }
                .distinct()
            val all = names.joinToString(" + ")
            return if (all.length <= MAX_TITLE_CHARS) all else "${names.first()} + ${names.size - 1} more"
        }

    /** The full path, or one per line for a merge; files from archives as `container!/entry`. */
    val description: String get() = paths.joinToString("\n") { (origin(it) ?: it).toString() }
    private val content = JPanel(BorderLayout())
    private val detail = LogDetailArea().apply { contextItems = { offset -> listOfNotNull(detailChartItem(offset)) } }
    private val filterBar = FilterBar(::applyFilter, columnNames)
    private val findBar = FindBar(::searchFor) { forward -> find(forward) }

    /** Slots charted under the histogram; they stay when the file is read again. */
    private val slotCharts: SlotCharts = SlotCharts(scope, session::slotValues).apply {
        onChanged = {
            histogramBar?.let {
                it.revalidate()
                it.repaint()
            }
            templates.repaint()
        }
    }
    private val templates: TemplatesPanel = TemplatesPanel(
        scope,
        { session.templateCounts() },
        { session.templateProgress },
        filterBar::addRules,
        slotCharts,
        ::canChart,
    )

    /** Below the table: the selected record, or the message templates of the shown rows. */
    private val bottomTabs = JTabbedPane().apply {
        val vertical = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        addTab("Detail", JScrollPane(detail, vertical, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER))
        addTab("Templates", templates)
        if (AppState[AppState.BOTTOM_TAB] in BOTTOM_TAB_TEMPLATES_STORED) selectedComponent = templates
        addChangeListener {
            AppState[AppState.BOTTOM_TAB] = if (selectedComponent === templates) BOTTOM_TAB_TEMPLATES else null
        }
    }
    private val followButton = accentToggle(Icons.follow, "Follow (tail)").apply {
        addActionListener { session.follow = isSelected }
    }
    private val reloadButton = toolBarButton(Icons.refresh, "Reload the file (F5)") { session.reload() }
    private val parserButton =
        dropDownButton("Parser: how lines are split into columns", maxWidth = 280) { showParsers() }
    private val columnsButton = toolBarButton(DropDownIcon(Icons.columns), "Columns to show") { showColumns() }.apply {
        isEnabled = false
    }

    /** Names of the columns the user hid; kept when the file is read again. */
    private var hiddenColumns = emptySet<String>()
    private var progressTimer: Timer? = null
    private var filterTimer: Timer? = null
    private var searchTimer: Timer? = null

    private var model: LogTableModel? = null
    private var table: LogTable? = null
    private var scrollPane: VirtualScrollPane? = null
    private var histogramBar: TimeHistogramBar? = null

    /** The record the detail area shows; null if none. */
    private var detailRecord: Int? = null

    private var search: TextSearch? = null
    private var foundMarks = Highlighter.NONE

    /** A find asked for while the search was running: forward, and whether the current record counts. */
    private var pendingFind: Pair<Boolean, Boolean>? = null

    /** What the status bar shows for this file. */
    val status: SessionStatus get() = session.status

    /**
     * Nanoseconds from the first selected row's timestamp to the last's; null unless two rows with timestamps are
     * selected.
     */
    val selectionSpan: Long?
        get() {
            val model = model ?: return null
            val rows = table?.selection?.rows ?: return null
            val first = rows.min
            val last = rows.max
            if (first < 0 || first == last || last >= model.totalRowCount) return null
            val start = model.shown.timestamp(model.recordAtIndex(first)) ?: return null
            val end = model.shown.timestamp(model.recordAtIndex(last)) ?: return null
            return end - start
        }

    /** Number of rows selected in the shown log, not only among those the table holds. */
    val selectedCount: Int get() = table?.selection?.count ?: 0

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
        showParser()
        filter?.let(filterBar::load)
        session.start()
    }

    /**
     * Auto, raw text, the user's parsers (editable), the built-in ones; plus the parser in use if it is none of
     * those.
     */
    private fun showParsers() {
        if (isMerged) return showMergedParsers()
        val chosen = session.chosenParser
        val known = AppSettings.current.knownParsers
        val items = buildList {
            add(PopupItem(autoLabel(), selected = chosen == null) { session.useParser(null) })
            (if (chosen == null || chosen in known) known else known + chosen).forEachIndexed { i, parser ->
                add(parserItem(parser, selected = parser == chosen, first = i == 0) { session.useParser(parser) })
            }
            add(PopupItem("Edit Parsers", Icons.settings, separatorBefore = true) {
                editParsers(chosen ?: session.parser)
            })
            // A guessed parser is none of the known ones; saving it makes it one the user can correct. Not a CSV
            // one: its headers belong to the file.
            val detected = session.parser.takeIf {
                chosen == null && session.parserDetected && it !in known && it !is Parser.Csv
            }
            if (detected != null) add(PopupItem("Save Detected Parser…") { editParsers(detected, adopt = true) })
        }
        showPopupList(parserButton, items)
    }

    /**
     * Like [showParsers], but a parser applies to all files of the merge; "Choose Parser per File" opens
     * the table of files and their parsers, also checked when the files' parsers differ.
     */
    private fun showMergedParsers() {
        val chosen = session.chosenParsers
        val mixed = chosen.distinct().size > 1
        // The parser of all files when not mixed; null is Auto-detect.
        val same = chosen.first()
        val items = buildList {
            val auto = if (session.parserDetected) "Auto: per file" else "Auto-detect"
            add(PopupItem(auto, selected = !mixed && same == null) { session.useParsers(chosen.map { null }) })
            AppSettings.current.knownParsers.forEachIndexed { i, parser ->
                add(parserItem(parser, selected = !mixed && same == parser, first = i == 0) {
                    session.useParsers(chosen.map { parser })
                })
            }
            add(PopupItem("Choose Parser per File", selected = mixed, separatorBefore = true) { chooseParserPerFile() })
            add(PopupItem("Edit Parsers", Icons.settings) { editParsers(session.parser) })
        }
        showPopupList(parserButton, items)
    }

    /** A parser menu entry; the user's own parsers can be edited from it. */
    private fun parserItem(parser: Parser, selected: Boolean, first: Boolean, onPick: () -> Unit): PopupItem {
        val tools = if (parser in AppSettings.current.parsers) {
            listOf(PopupTool(Icons.advanced, "Edit this parser") { editParsers(parser) })
        } else {
            emptyList()
        }
        return PopupItem(parser.name, selected = selected, separatorBefore = first, tools = tools, action = onPick)
    }

    private fun chooseParserPerFile() {
        val descriptions = paths.map { (origin(it) ?: it).toString() }
        val window = SwingUtilities.getWindowAncestor(this)
        val dialog = FileParsersDialog(window, paths, sourceNames, descriptions, session.chosenParsers)
        dialog.isVisible = true
        session.useParsers(dialog.chosenParsers ?: return)
    }

    /** Checks for the columns to show; the menu stays open while they are toggled. */
    private fun showColumns() {
        val model = model ?: return
        val popup = JPopupMenu()
        val boxes = model.columnChoices.map { field ->
            JCheckBoxMenuItem(columnNames.of(field), field.name !in hiddenColumns).apply {
                putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true)
            }
        }

        // At least one column stays: the last checked one cannot be unchecked.
        fun enableBoxes() {
            val single = boxes.count { it.isSelected } == 1
            boxes.forEach { it.isEnabled = !single || !it.isSelected }
        }
        fun update() {
            val hidden = model.columnChoices.filterIndexed { i, _ -> !boxes[i].isSelected }
            hideColumns(hidden.map { it.name }.toSet())
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

    /** The right-click menu of a column header: hide that column, rename it, or show all again. */
    private fun showHeaderMenu(header: JComponent, column: Int, x: Int, y: Int) {
        val model = model ?: return
        val field = model.fieldAt(column) ?: return
        val name = columnNames.of(field)
        val popup = JPopupMenu()
        popup.add(JMenuItem("Hide “$name”").apply {
            isEnabled = model.columnChoices.count { it.name !in hiddenColumns } > 1
            addActionListener { hideColumns(hiddenColumns + field.name) }
        })
        popup.add(JMenuItem("Show All Columns").apply {
            isEnabled = hiddenColumns.isNotEmpty()
            addActionListener { hideColumns(emptySet()) }
        })
        popup.addSeparator()
        fun askName(onRename: (String) -> Unit) =
            showColumnRename(header, x, field, model.columnChoices, columnNames, onRename)
        popup.add(JMenuItem("Rename for this session").apply {
            addActionListener {
                askName { name ->
                    columnNames.rename(field.name, name)
                    columnNamesChanged()
                }
            }
        })
        val parser = savedParser()
        popup.add(JMenuItem("Rename in parser settings").apply {
            if (parser == null) {
                isEnabled = false
                toolTipText = RENAME_NEEDS_SAVED_PARSER
            } else {
                addActionListener { askName { name -> saveColumnName(parser, field, name) } }
            }
        })
        popup.addSeparator()
        popup.add(JMenuItem("Copy Column").apply {
            toolTipText = "The whole value of “$name” of every shown row, one row per line"
            addActionListener {
                val shown = model.shown
                val column = shown.schema.fields.indexOf(field)
                copyColumn(shown.copyColumn(column, copyLimit()))
            }
        })
        popup.show(header, x, y)
    }

    /** The user's saved parser that reads all the files, so a column name can be saved with it; null if none does. */
    private fun savedParser(): Parser? {
        val parser = session.parser
        if (session.parsers.any { it != parser }) return null
        return parser.takeIf { it in AppSettings.current.parsers }
    }

    /**
     * Saves [field]'s new [name] with [parser]. The settings change reaches the sessions of all tabs that use it,
     * which take the new names without reading their files again.
     */
    private fun saveColumnName(parser: Parser, field: Field, name: String) {
        columnNames.renameSaved(field.name, name)
        val renamed = parser.withAliases(columnNames.saved)
        val parsers = AppSettings.current.parsers.map { if (it == parser) renamed else it }
        if (!AppSettings.updateOrShowError(this, AppSettings.current.copy(parsers = parsers))) {
            columnNames.saved = parser.aliases
        }
        columnNamesChanged()
    }

    /** Shows the parser's column names, if they changed, e.g. by a rename in another tab or in the settings. */
    private fun showSavedColumnNames() {
        val aliases = session.parser.aliases
        if (aliases == columnNames.saved) return
        columnNames.saved = aliases
        columnNamesChanged()
    }

    private fun columnNamesChanged() {
        table?.columnNamesChanged()
        filterBar.columnNamesChanged()
    }

    /** Copies the selected rows as their records are written in the file. */
    private fun copySelectedRows() {
        val model = model ?: return
        val rows = selectedRows()
        if (rows.isEmpty()) return
        val count = NumberFormat.getIntegerInstance().format(rows.count)
        val prepared = model.shown.copyRows(rows, copyLimit())
        runCopy("Copy Rows", "Copying $count rows…", "rows", prepared) {
            "The $count selected rows take ${formatByteSize(it.bytes)} in the file"
        }
    }

    private fun copyColumn(prepared: PreparedCopy) = runCopy("Copy Column", "Copying the column…", "column", prepared) {
        val rows = NumberFormat.getIntegerInstance().format(it.rows)
        "The column of $rows rows takes more than ${formatByteSize(copyLimit())}"
    }

    /**
     * Runs [prepared] off the UI thread, with a progress dialog saying [progressText] if it takes a while, and puts
     * what it copied on the clipboard. Cancel copies nothing, and so does closing the tab. [tooLarge] says why a copy
     * of the [what] was refused.
     */
    private fun runCopy(
        title: String,
        progressText: String,
        what: String,
        prepared: PreparedCopy,
        tooLarge: (CopyResult.TooLarge) -> String,
    ) {
        val window = SwingUtilities.getWindowAncestor(this) ?: return
        lateinit var job: Job
        var finished = false
        val dialog = ProgressDialog(window, title, progress = { prepared.progress }) { job.cancel() }
        dialog.text = progressText
        // Undispatched: the dialog is disposed even if the tab closes before the copy would have started.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                prepared.run()
            } finally {
                finished = true
                dialog.dispose()
            }
            val message = when (result) {
                is CopyResult.Copied -> return@launch copy(result.text)
                is CopyResult.TooLarge -> refusal(tooLarge(result))
                CopyResult.FileChanged -> "The file changed while copying; copy the $what again."
                is CopyResult.Failed -> "Cannot copy the $what: ${result.message}"
            }
            JOptionPane.showMessageDialog(this@LogTab, message, title, JOptionPane.ERROR_MESSAGE)
        }
        dialog.showLater { finished }
    }

    /** Why a copy was refused: [reason], the limit, and whether more memory would raise it. */
    private fun refusal(reason: String): String {
        val limit = copyLimit()
        val more = if (limit < MAX_COPY_BYTES) " Give logxy more memory in Preferences › Memory to copy more." else ""
        return "$reason; at most ${formatByteSize(limit)} can be copied.$more"
    }

    /** The selected rows of the shown log. */
    private fun selectedRows(): RowRanges = table?.selection?.rows ?: RowRanges()

    private fun copy(text: String) =
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    /** The right-click menu of a cell: show only records with that value, or all but them. */
    private fun showCellMenu(table: LogTable, row: Int, column: Int, x: Int, y: Int) {
        val model = model ?: return
        val field = model.fieldAt(column) ?: return
        val shown = model.shown
        val fieldIndex = shown.schema.fields.indexOf(field)
        // The whole value, not the cell's text, which is cut to its first line and has its tabs expanded.
        val value = shown.fieldText(model.recordAt(row), fieldIndex)
        val level = model.level(row)?.takeIf { field.role == Role.LEVEL && it != Level.UNKNOWN }
        val firstLine = value.lineSequence().first()
        val escaped = firstLine.take(MAX_RULE_CHARS).replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
        // A long or multi-line value, such as a message with its stack trace, is matched by its start.
        val pattern = if (firstLine.length > MAX_RULE_CHARS || firstLine.length < value.length) "$escaped*" else escaped
        fun rule(op: QueryOp) = if (level != null) {
            Query.Rule(field.name, op, level.name, bare = true)
        } else {
            Query.Rule(field.name, op, pattern)
        }
        val popup = JPopupMenu()
        popup.add(JMenuItem("Copy Cell Value").apply {
            addActionListener { copy(shown.fieldText(model.recordAt(row), fieldIndex)) }
        })
        // Within a selection of several rows, the menu also copies from all of them.
        if (table.isRowSelected(row) && table.selection.count > 1) {
            popup.add(JMenuItem("Copy Selected Rows").apply {
                addActionListener { copySelectedRows() }
            })
            popup.add(JMenuItem("Copy Column “${columnNames.of(field)}”").apply {
                addActionListener {
                    val prepared = shown.copyColumn(fieldIndex, copyLimit(), selectedRows())
                    copyColumn(prepared)
                }
            })
        }
        popup.addSeparator()
        fun add(rule: Query.Rule) {
            popup.add(JMenuItem(QueryPrinter.print(rule).cut(MAX_MENU_CHARS), Icons.filter).apply {
                addActionListener { filterBar.addRule(rule) }
            })
        }
        // `== ""` is a placeholder that filters nothing; `!= ""` keeps records with a value.
        if (value.isNotEmpty()) add(rule(QueryOp.EQUALS))
        add(rule(QueryOp.NOT_EQUALS))
        val hasTemplate = field.role == Role.MESSAGE && shown.schema.hasTemplates()
        val template = if (hasTemplate) session.templateOf(model.recordAt(row)) else null
        if (template != null) {
            popup.addSeparator()
            add(Query.Rule(Query.TEMPLATE_FIELD, QueryOp.EQUALS, template))
            add(Query.Rule(Query.TEMPLATE_FIELD, QueryOp.NOT_EQUALS, template))
            // The cell's text is the message's first line, unless tabs were expanded in it.
            val offset = if (table.getValueAt(row, column) == firstLine) table.textOffsetAt(row, column, x) else -1
            chartItem(template, value, offset)?.let { popup.add(it) }
        }
        if (level != null) {
            popup.addSeparator()
            for (op in RANGE_OPS) add(Query.Rule(field.name, op, level.name, bare = true))
        }
        // The literal is written from the record's time, as the log's own layout may not parse back (e.g. offsets).
        val time = shown.timestamp(model.recordAt(row))?.takeIf { field.type == ColumnType.TIMESTAMP }
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

    /** Whether slots can be charted: the records have templates and times, so the histogram shows. */
    private fun canChart() = histogramBar != null && model?.shown?.schema?.hasTemplates() == true

    /**
     * The menu item that charts, or stops charting, the slot of [message] (whose template is [template]) holding the
     * character at [offset]; null if there is none there, or the templates of the shown rows are not counted yet.
     */
    private fun chartItem(template: String, message: String, offset: Int): JMenuItem? {
        if (offset < 0 || !canChart() || !templates.hasCounts) return null
        val slot = Templates.slotAt(message, offset).takeIf { it >= 0 } ?: return null
        val word = Templates.slotWord(message, slot) ?: return null
        val shown = word.cut(MAX_MENU_CHARS)
        val charted = slotCharts.chart(template, slot) != null
        return JMenuItem(if (charted) "Stop Charting “$shown”" else "Chart “$shown” Over Time").apply {
            addActionListener { toggleChart(this@LogTab, slotCharts, template, slot) }
        }
    }

    /** [chartItem] for the place [offset] in the detail area, which shows [detailRecord]. */
    private fun detailChartItem(offset: Int): JMenuItem? {
        val shown = model?.shown ?: return null
        val record = detailRecord ?: return null
        val field = shown.schema.indexOf(Role.MESSAGE).takeIf { it >= 0 } ?: return null
        val message = shown.fieldText(record, field)
        val firstLine = message.lineSequence().first()
        // The message's first line ends the record's first line in most layouts; JSON shows it escaped, and gets none.
        val start = detail.text.lineSequence().first().lastIndexOf(firstLine)
        if (firstLine.isEmpty() || start < 0 || offset - start !in firstLine.indices) return null
        val template = session.templateOf(record) ?: return null
        return chartItem(template, message, offset - start)
    }

    private fun hideColumns(names: Set<String>) {
        hiddenColumns = names
        model?.hiddenColumns = names
    }

    private fun autoLabel() = if (session.parserDetected) "Auto: ${session.parser.name}" else "Auto-detect"

    private fun editParsers(select: Parser, adopt: Boolean = false) {
        val dialog = PreferencesDialog(
            SwingUtilities.getWindowAncestor(this),
            PreferencesDialog.Section.PARSERS,
            path,
            select,
            offerUse = !isMerged,
            adopt = select.takeIf { adopt },
        )
        dialog.isVisible = true
        session.useParser(dialog.parserToUse ?: return)
    }

    /** Shows the parser in use on the parser button. */
    private fun showParser() {
        parserButton.text = when {
            isMerged -> when {
                session.chosenParsers.any { it != null } -> "Per file"
                session.parserDetected -> "Auto: per file"
                else -> "Auto-detect"
            }
            else -> session.chosenParser?.name ?: autoLabel()
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

    private fun showTable(shown: ShownLog) {
        val model = LogTableModel(shown, columnNames)
        // Columns hidden before stay hidden, unless that would hide them all (the file changed meanwhile).
        val leavesSome = model.columnChoices.any { it.name !in hiddenColumns }
        model.hiddenColumns = if (leavesSome) hiddenColumns else emptySet()
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
        table.transferHandler = FileDropHandler(openFiles)
        // Copying many rows takes a while: it runs off the UI thread rather than through the transfer handler.
        table.actionMap.put(TransferHandler.getCopyAction().getValue(Action.NAME), object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = copySelectedRows()
        })
        val scrollPane = VirtualScrollPane(table, model)
        scrollPane.onFollowChanged = { session.follow = it }
        scrollPane.resumeFollowAtEnd = { session.isLive }
        val timestampField = shown.schema.fields.firstOrNull { it.role == Role.TIMESTAMP }?.name
        scrollPane.onRowsInViewChanged = ::showMarkedTimes
        val histogramBar = timestampField?.let { field ->
            TimeHistogramBar({ session.histogram }, { session.foundHistogram }, slotCharts).apply {
                onJump = ::jumpToTime
                onRange = { from, to -> filterBar.setTimeRange(field, timeLiteral(from), timeLiteral(to)) }
                onShowAllTimes = { filterBar.setTimeRange(field, null, null) }
                hasTimeRange = { filterBar.hasTimeRange(field) }
            }
        }
        table.selectionModel.addListSelectionListener { event ->
            showMarkedTimes()
            if (!event.valueIsAdjusting) {
                showDetail(model, table.selection.lead)
                showFoundCount()
                onStatusChanged()
            }
        }
        this.model = model
        this.table = table
        this.scrollPane = scrollPane
        this.histogramBar = histogramBar
        table.found = foundMarks
        filterBar.setSchema(shown.schema)
        templates.canFilter = shown.schema.hasTemplates()
        setFilterVisible(true)

        detail.text = ""
        detailRecord = null
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
        val remembered = AppState[AppState.DETAIL_DIVIDER]?.toDoubleOrNull()
        split.setDividerLocation(remembered?.coerceIn(0.1, 0.95) ?: 0.75)
        // Remembered as a proportion, so it fits any window size; saved once dragging stops, as each save writes a
        // file.
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

    /** Shows the record at [row] of the shown log, or nothing if there is none. */
    private fun showDetail(model: LogTableModel, row: Int) {
        val record = model.recordAtIndexOrNull(row)
        detailRecord = record
        detail.show(if (record != null) model.shown.recordText(record, MAX_DETAIL_BYTES) else "")
        // A long record, e.g. with a stack trace, shows where the find bar's text is.
        detail.caretPosition = detail.firstFound
    }

    /** Shows [record] again if it is selected; it may have grown, e.g. by a stack trace written after it. */
    private fun refreshDetail(record: Int) {
        val model = model ?: return
        val row = table?.selection?.lead ?: return
        if (model.recordAtIndexOrNull(row) != record) return
        val text = model.shown.recordText(record, MAX_DETAIL_BYTES)
        if (text == detail.text) return
        val caret = detail.caretPosition
        detail.show(text)
        detail.caretPosition = caret.coerceAtMost(detail.document.length)
    }

    /** Applies the filter bar's level and query to this file's records. */
    private fun applyFilter() {
        if (session.shown == null) return
        try {
            // While suspended the query is not read: text that does not parse must not keep it from showing all.
            val query = if (filterBar.suspended) null else filterBar.query()
            if (query == null) {
                session.applyFilter(null, Query.Group(all = true, items = emptyList()))
            } else {
                session.applyFilter(filterBar.minimumLevel, query)
            }
            val matches = query?.let(Highlighter::of) ?: Highlighter.NONE
            table?.matches = matches
            detail.matches = matches
            filterBar.showError(null)
        } catch (e: QueryException) {
            filterBar.showError(e.message)
        }
    }

    private fun showRows(keepView: Boolean, oldSize: Int, recordBefore: (row: Int) -> Int) {
        val model = model ?: return
        val table = table ?: return
        val scrollPane = scrollPane ?: return
        // The selection ends with the rows shown; only the record at its lead stays selected, if it is still shown.
        val lead = table.selection.lead
        val selected = if (lead in table.selection && lead < oldSize) recordBefore(lead) else null
        val top = scrollPane.firstVisibleRow.takeIf { it < oldSize }?.let(recordBefore)
        model.rowsReplaced()
        scrollPane.rowsReplaced()
        templates.refresh()
        slotCharts.refresh()
        val selectedIndex = selected?.let { record ->
            model.indexOfRecord(record).takeIf { it >= 0 && model.recordAtIndex(it) == record }
        }
        if (!session.follow) {
            if (keepView && top != null) {
                scrollPane.scrollToRow(maxOf(0, model.indexOfRecord(top)))
            } else if (selectedIndex != null) {
                scrollPane.selectRow(selectedIndex)
            }
        }
        if (selectedIndex != null) scrollPane.selectRow(selectedIndex, scroll = false)
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
        if (!findBar.isVisible) table?.requestFocusInWindow()
        pendingFind = null
        try {
            session.search(search)
        } catch (e: QueryException) {
            return findBar.showError(e.message)
        }
        // Once found, go to the first from the current record on.
        if (search != null) pendingFind = true to true
    }

    /** Shows what the session's search found, or its progress, and goes to a record asked for meanwhile. */
    private fun showFound() {
        val searching = model?.shown?.isSearching == true
        showSearchProgress(searching)
        if (!searching) {
            val error = session.searchError
            if (error != null) {
                pendingFind = null
                return findBar.showError(error)
            }
            showFoundCount()
        }
        // While searching, too: what the chunks found so far may settle where to go.
        pendingFind?.let { (forward, inclusive) -> find(forward, inclusive) }
    }

    /** Follows a slow search's progress on the find bar and the histogram while [searching]. */
    private fun showSearchProgress(searching: Boolean) {
        if (searching == (searchTimer != null)) return
        searchTimer?.stop()
        searchTimer = null
        if (!searching) return
        // A search narrowing the previous one takes a moment, then its count replaces the old: no flash of "Searching".
        if (!session.searchNarrows) findBar.showCount(0, null)
        searchTimer = Timer(100) {
            showCount()
            histogramBar?.repaint()
        }.apply {
            initialDelay = SEARCH_PROGRESS_DELAY_MILLIS
            start()
        }
    }

    /**
     * Selects the next record the search found after the current one, or the previous before it, wrapping around.
     * The current record is the selected one, or the top one in view if none is; with [inclusive], or if none is
     * selected, it can be found itself.
     */
    private fun find(forward: Boolean, inclusive: Boolean = false) {
        if (search == null) return findBar.open()
        pendingFind = null
        val model = model
        // Not loaded yet: go there once it is.
        if (model == null) {
            pendingFind = forward to inclusive
            return
        }
        val table = table ?: return
        val scrollPane = scrollPane ?: return
        val searching = model.shown.isSearching
        if ((model.shown.foundCount == 0 && !searching) || model.totalRowCount == 0) return
        val lead = table.selection.lead
        val selected = if (lead in table.selection) model.recordAtIndexOrNull(lead) else null
        val current = selected ?: model.recordAtIndex(scrollPane.firstVisibleRow)
        val record = model.shown.nextFound(current, forward, inclusive || selected == null)
        // A searched record between here and the match may still hold one: go once the search is sure.
        if (record == NOT_YET_FOUND) {
            pendingFind = forward to inclusive
            return
        }
        val index = model.indexOfRecord(record)
        if (index < 0 || model.recordAtIndex(index) != record) return
        session.follow = false
        scrollPane.selectRow(index, scroll = index !in scrollPane.firstVisibleRow..scrollPane.lastVisibleRow)
    }

    /** Shows how many records the search found, and which of them is selected. */
    private fun showFoundCount() {
        // While searching, the progress timer shows it, after a delay that keeps fast searches from flickering.
        if (model?.shown?.isSearching == true) return
        showCount()
    }

    /** Shows the count, so far and with the search's progress while it runs. */
    private fun showCount() {
        val shown = model?.shown
        val lead = table?.selection?.lead ?: -1
        val selected = model?.recordAtIndexOrNull(lead) ?: -1
        val position = if (shown != null && selected >= 0) shown.foundPosition(selected) else 0
        val progress = if (shown?.isSearching == true) session.searchProgress else null
        findBar.showCount(position, shown?.foundCount ?: 0, progress)
    }

    /** Marks the times of the selected rows on the histogram, or of the top row in view if none is selected. */
    private fun showMarkedTimes() {
        val bar = histogramBar ?: return
        val model = model ?: return
        val scrollPane = scrollPane ?: return
        val rows = table?.selection?.rows ?: return
        val first = rows.min
        val last = rows.max
        bar.markedTimes = if (first >= 0 && last < model.totalRowCount) {
            val from = model.shown.timestamp(model.recordAtIndex(first))
            val to = model.shown.timestamp(model.recordAtIndex(last))
            if (from != null && to != null) minOf(from, to) to maxOf(from, to) else (from ?: to)?.let { it to it }
        } else if (model.totalRowCount > 0) {
            model.shown.timestamp(model.recordAtIndex(scrollPane.firstVisibleRow))?.let { it to it }
        } else {
            null
        }
    }

    /** Selects the first shown row at [time] or later, or the last row if none is. */
    private fun jumpToTime(time: Long) {
        val model = model ?: return
        val scrollPane = scrollPane ?: return
        val row = model.shown.firstRowAt(time).takeIf { it >= 0 } ?: return
        session.follow = false
        scrollPane.selectRow(row)
        table?.requestFocusInWindow()
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
        templates.reset()
        slotCharts.reset()
        showFilterProgress(false)
        showSearchProgress(false)
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
            val x = maxOf(0, width - size.width - FLOATING_RIGHT_GAP)
            floating.setBounds(x, FLOATING_TOP_GAP, minOf(size.width, width), size.height)
        }

        override fun getPreferredSize(): Dimension = base.preferredSize

        override fun getMinimumSize(): Dimension = base.minimumSize
    }

    private inner class Listener : SessionListener {
        override fun indexing(fraction: () -> Double) = showProgress(fraction)

        override fun shownEnded() {
            columnsButton.isEnabled = false
            showSearchProgress(false)
            histogramBar = null
            templates.reset()
            slotCharts.reset()
            // The table may still be painted until indexing shows its progress: with no rows.
            model?.rowsReplaced()
            model = null
            table = null
            scrollPane = null
        }

        override fun problem(title: String, detail: String, hint: String?) {
            stopProgress()
            showProblem(title, detail, hint)
        }

        override fun loaded(shown: ShownLog) {
            stopProgress()
            showTable(shown)
        }

        override fun notMergeable(paths: List<Path>) {
            // Not while the session is still loading the others.
            SwingUtilities.invokeLater { openEach(paths) }
            onStatusChanged()
        }

        override fun parserChanged() {
            showParser()
            showSavedColumnNames()
        }

        override fun fieldsShown() {
            val model = model ?: return
            model.schemaChanged()
            filterBar.setSchema(model.shown.schema)
        }

        override fun recordsGrew(changed: IntArray) {
            model?.recordsGrew(changed)
            changed.forEach(::refreshDetail)
        }

        override fun rowsReplaced(keepView: Boolean, oldSize: Int, recordBefore: (row: Int) -> Int) =
            showRows(keepView, oldSize, recordBefore)

        override fun rowsChanged(firstChanged: Int, oldSize: Int) {
            model?.rowsChangedFrom(firstChanged, oldSize)
            scrollPane?.rowsAppended()
            templates.refreshSoon()
            slotCharts.refreshSoon()
        }

        override fun foundChanged() {
            showFound()
            histogramBar?.repaint()
        }

        override fun levelMappingChanged() {
            model?.let { it.fireTableRowsUpdated(0, maxOf(0, it.rowCount - 1)) }
            templates.refresh()
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
