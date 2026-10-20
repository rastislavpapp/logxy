package io.github.rastislavpapp.logxy.ui

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
import io.github.rastislavpapp.logxy.core.query.TemplateRules
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.core.query.TimeLiteral
import io.github.rastislavpapp.logxy.core.query.escapeWildcards
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.miginfocom.swing.MigLayout
import org.slf4j.LoggerFactory
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
import javax.swing.text.JTextComponent

private const val MAX_TITLE_CHARS = 40
private const val MAX_RULE_CHARS = 1000
private val RANGE_OPS = listOf(QueryOp.AT_LEAST, QueryOp.ABOVE, QueryOp.AT_MOST, QueryOp.BELOW)
private val log = LoggerFactory.getLogger(LogTab::class.java)

/** A search taking longer than this shows its progress. */
private const val SEARCH_PROGRESS_DELAY_MILLIS = 200

/** Where the find box floats: below the top of the rows, left of the scroll bar. */
private const val FLOATING_TOP_GAP = 6
private const val FLOATING_RIGHT_GAP = 24

/** The views around a [LogTab]'s rows, which the user can show and hide one by one where the host offers it. */
enum class LogPart { FILTER, HISTOGRAM, TEMPLATES }

/**
 * One open file, or several merged: shows the records of its [LogSession], with the filter bar and, if the host has
 * one, the detail pane. [openFiles] opens dropped files; [openEach] opens files in tabs of their own. [filter]
 * is applied once the records are there. [origin] tells which files were extracted from zips or decompressed.
 * The [host] keeps the settings and gives what only the app or the plugin has. With [togglesParts], the
 * [shownParts] are shown or hidden as set; else the detail and the templates are tabs below the rows, which a divider
 * arrow hides.
 */
class LogTab(
    paths: List<Path>,
    private val host: LogViewHost,
    private val openFiles: (List<Path>) -> Unit,
    private val openEach: (List<Path>) -> Unit,
    filter: SavedFilter? = null,
    private val origin: (Path) -> ArchiveOrigin? = { null },
    /** The parser chosen for each of [paths], null (or missing) to detect it. */
    parsers: List<Parser?> = emptyList(),
    private val togglesParts: Boolean = false,
    private val onStatusChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + host.uiDispatcher)
    private val session = LogSession(paths, scope, host.settings, Listener(), origin, parsers)

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
    private val detail: DetailPane? = host.detailPane()
    private val filterBar = FilterBar(::applyFilter, columnNames, host)
    private val findBar = host.findView(this, ::searchFor) { forward -> find(forward) }

    /** Variables charted under the histogram; they stay when the file is read again. */
    private val variableCharts: VariableCharts = VariableCharts(scope, session::variableSeries).apply {
        onChanged = {
            showCharts()
            chartPanel?.repaint()
            templates.repaint()
        }
        onNoNumbers = { chart ->
            // Not from within the read that found it, which reads other templates' variables after.
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(
                    this@LogTab,
                    "No record has a number in this variable, so there is nothing to chart:\n${chart.label}",
                    "Chart Variable",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
    }
    private val templates: TemplatesPanel = TemplatesPanel(
        scope,
        { session.templateCounts() },
        { session.templateProgress },
        filterBar::addRules,
        filterBar::addRuleFor,
        session::variableValues,
        variableCharts,
        ::canChart,
    )

    /** Below the table, unless [togglesParts]: the selected record, or the message templates of the shown rows. */
    private val bottomTabs by lazy {
        JTabbedPane().apply {
            if (detail != null) {
                val vertical = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                val horizontal = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                addTab("Detail", JScrollPane(detail.component, vertical, horizontal))
            }
            addTab("Templates", templates)
        }
    }

    /**
     * The parts that show, once the records are there; all unless [togglesParts]. Rules added from outside the
     * filter bar, e.g. from a cell's menu, show it.
     */
    var shownParts: Set<LogPart> = LogPart.entries.toSet()
        set(value) {
            if (value == field) return
            val bottomChanged = (LogPart.TEMPLATES in value) != (LogPart.TEMPLATES in field)
            field = value
            filterBar.isVisible = filterReady && LogPart.FILTER in value
            histogramBar?.isVisible = LogPart.HISTOGRAM in value
            showCharts()
            if (bottomChanged && togglesParts) showParts()
            revalidate()
            repaint()
            onPartsChanged()
        }

    /** Called when [shownParts] changed, also when the tab showed a part itself. */
    var onPartsChanged: () -> Unit = {}

    /** Whether the records are there, so the filter bar shows if [shownParts] has it. */
    private var filterReady = false
    private val followButton = accentToggle(Icons.follow, "Follow (tail)").apply {
        addActionListener { session.follow = isSelected }
    }
    private val reloadButton = toolBarButton(Icons.refresh, "Reload the file (F5)") { session.reload() }
    private val parserButton =
        dropDownButton("Parser: how lines are split into columns", maxWidth = 280) { showParsers() }
    private val columnsButton = toolBarButton(DropDownIcon(Icons.columns), "Columns to show") { showColumns() }.apply {
        isEnabled = false
    }

    /**
     * Names of the columns the user hid, kept when the file is read again; they may be set before the records are
     * there. Names that would hide every column hide none.
     */
    var hiddenColumns = emptySet<String>()
        set(value) {
            field = value
            model?.let(::applyHiddenColumns)
        }

    /**
     * The widths in pixels the user dragged columns to, by field name, kept when the file is read again; they may be
     * set before the records are there.
     */
    var columnWidths: Map<String, Int>
        get() = table?.resizedWidths ?: rememberedWidths
        set(value) {
            rememberedWidths = value
            table?.resizedWidths = value
        }

    /** [columnWidths] while there is no table, e.g. while the file is read again. */
    private var rememberedWidths = emptyMap<String, Int>()
    private var progressTimer: Timer? = null
    private var filterTimer: Timer? = null
    private var searchTimer: Timer? = null

    private var model: LogTableModel? = null
    private var table: LogTable? = null
    private var scrollPane: VirtualScrollPane? = null
    private var histogramBar: TimeHistogramBar? = null

    /** The variable charts under the histogram, and the rows under them; null while there is no histogram. */
    private var chartPanel: VariableChartPanel? = null
    private var chartSplit: JSplitPane? = null

    /** Where the table goes: alone, or under the charts in [chartSplit] while there are some. */
    private val rowsHolder = JPanel(BorderLayout())
    private var rowsView: JComponent? = null

    /** The histogram above [rowsHolder]; null until the records are there. */
    private var rowsPanel: JComponent? = null

    /** Where the charts' divider was dragged to; -1 until it is, for their own height. */
    private var chartHeight = -1

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
        filterBar.onRulesAdded = { shownParts += LogPart.FILTER }
        // Over all records: the shown rows may already be narrowed by the very rule being edited.
        filterBar.variableValues = { template, variable, onValues ->
            scope.launch {
                val values = try {
                    session.allVariableValues(template, variable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Cannot count the values of {{}} of “{}”", variable + 1, template, e)
                    null
                }
                onValues(values)
            }
        }
        session.start()
    }

    /**
     * Auto, raw text, the user's parsers (editable), the built-in ones; plus the parser in use if it is none of
     * those.
     */
    private fun showParsers(anchor: JComponent = parserButton) {
        if (isMerged) return showMergedParsers(anchor)
        val chosen = session.chosenParser
        val known = host.settings.current.knownParsers
        val items = buildList {
            add(PopupItem(autoLabel(), selected = chosen == null) { session.useParser(null) })
            (if (chosen == null || chosen in known) known else known + chosen).forEachIndexed { i, parser ->
                add(parserItem(parser, selected = parser == chosen, first = i == 0) { session.useParser(parser) })
            }
            if (host.settingsDialogs == null) return@buildList
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
        showPopupList(anchor, items)
    }

    /**
     * Like [showParsers], but a parser applies to all files of the merge; "Choose Parser per File" opens
     * the table of files and their parsers, also checked when the files' parsers differ.
     */
    private fun showMergedParsers(anchor: JComponent) {
        val chosen = session.chosenParsers
        val mixed = chosen.distinct().size > 1
        // The parser of all files when not mixed; null is Auto-detect.
        val same = chosen.first()
        val items = buildList {
            val auto = if (session.parserDetected) "Auto: per file" else "Auto-detect"
            add(PopupItem(auto, selected = !mixed && same == null) { session.useParsers(chosen.map { null }) })
            host.settings.current.knownParsers.forEachIndexed { i, parser ->
                add(parserItem(parser, selected = !mixed && same == parser, first = i == 0) {
                    session.useParsers(chosen.map { parser })
                })
            }
            add(PopupItem("Choose Parser per File", selected = mixed, separatorBefore = true) { chooseParserPerFile() })
            if (host.settingsDialogs != null) {
                add(PopupItem("Edit Parsers", Icons.settings) { editParsers(session.parser) })
            }
        }
        showPopupList(anchor, items)
    }

    /** A parser menu entry; the user's own parsers can be edited from it, if the host has the settings dialog. */
    private fun parserItem(parser: Parser, selected: Boolean, first: Boolean, onPick: () -> Unit): PopupItem {
        val tools = if (host.settingsDialogs != null && parser in host.settings.current.parsers) {
            listOf(PopupTool(Icons.advanced, "Edit this parser") { editParsers(parser) })
        } else {
            emptyList()
        }
        return PopupItem(parser.name, selected = selected, separatorBefore = first, tools = tools, action = onPick)
    }

    private fun chooseParserPerFile() {
        val descriptions = paths.map { (origin(it) ?: it).toString() }
        val window = SwingUtilities.getWindowAncestor(this)
        val chosen = host.chooseParsersPerFile(window, paths, sourceNames, descriptions, session.chosenParsers)
        session.useParsers(chosen ?: return)
    }

    /** Checks for the columns to show; the menu stays open while they are toggled. */
    private fun showColumns(anchor: JComponent = columnsButton) {
        val model = model ?: return
        val popup = JPopupMenu()
        val boxes = model.columnChoices.map { field ->
            JCheckBoxMenuItem(columnNames.of(field), field.name !in hiddenColumns).apply(look::keepMenuOpen)
        }

        // At least one column stays: the last checked one cannot be unchecked.
        fun enableBoxes() {
            val single = boxes.count { it.isSelected } == 1
            boxes.forEach { it.isEnabled = !single || !it.isSelected }
        }
        fun update() {
            val hidden = model.columnChoices.filterIndexed { i, _ -> !boxes[i].isSelected }
            hiddenColumns = hidden.map { it.name }.toSet()
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
        popup.show(anchor, 0, anchor.height)
    }

    /** The right-click menu of a column header: hide that column, rename it, or show all again. */
    private fun showHeaderMenu(header: JComponent, column: Int, x: Int, y: Int) {
        val model = model ?: return
        val field = model.fieldAt(column) ?: return
        val name = columnNames.of(field)
        val popup = JPopupMenu()
        popup.add(JMenuItem("Hide “$name”").apply {
            isEnabled = model.columnChoices.count { it.name !in hiddenColumns } > 1
            addActionListener { hiddenColumns += field.name }
        })
        popup.add(JMenuItem("Show All Columns").apply {
            isEnabled = hiddenColumns.isNotEmpty()
            addActionListener { hiddenColumns = emptySet() }
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
        return parser.takeIf { it in host.settings.current.parsers }
    }

    /**
     * Saves [field]'s new [name] with [parser]. The settings change reaches the sessions of all tabs that use it,
     * which take the new names without reading their files again.
     */
    private fun saveColumnName(parser: Parser, field: Field, name: String) {
        columnNames.renameSaved(field.name, name)
        val renamed = parser.withAliases(columnNames.saved)
        val settings = host.settings
        val parsers = settings.current.parsers.map { if (it == parser) renamed else it }
        if (!settings.updateOrShowError(this, settings.current.copy(parsers = parsers))) {
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
        val hint = host.moreMemoryHint
        val more = if (limit < MAX_COPY_BYTES && hint != null) " $hint" else ""
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
        val escaped = escapeWildcards(firstLine.take(MAX_RULE_CHARS))
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
            add(TemplateRules.template(template, QueryOp.EQUALS))
            add(TemplateRules.template(template, QueryOp.NOT_EQUALS))
            // The cell shows the message's first line with its tabs expanded, unless it is cut.
            val whole = table.getValueAt(row, column) == LogTableModel.expandTabs(firstLine)
            val shownAt = if (whole) table.textOffsetAt(row, column, x) else -1
            val offset = LogTableModel.offsetBeforeTabs(firstLine, shownAt)
            val variable = if (offset >= 0) Templates.variableAt(value, offset) else -1
            val word = if (variable >= 0) Templates.variableWord(value, variable) else null
            if (word != null) {
                val shown = "{${variable + 1}} == “${word.cut(MAX_MENU_CHARS)}”"
                popup.add(JMenuItem("Show Only $shown", Icons.filter).apply {
                    addActionListener {
                        filterBar.addRuleFor { TemplateRules.showOnly(it, template, variable, word) }
                    }
                })
                popup.add(JMenuItem("Hide $shown").apply {
                    addActionListener { filterBar.addRule(TemplateRules.hide(template, variable, word)) }
                })
                chartItem(template, variable, word)?.let { popup.add(it) }
            }
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

    /** Whether variables can be charted: the records have templates and times, and the histogram shows. */
    private fun canChart() =
        histogramBar != null && LogPart.HISTOGRAM in shownParts && model?.shown?.schema?.hasTemplates() == true

    /**
     * The menu item that charts, or stops charting, [variable] of [template], whose value in the clicked record is
     * [word]; null if variables cannot be charted, or the templates of the shown rows are not counted yet.
     */
    private fun chartItem(template: String, variable: Int, word: String): JMenuItem? {
        if (!canChart() || !templates.hasCounts) return null
        val shown = word.cut(MAX_MENU_CHARS)
        val charted = variableCharts.chart(template, variable) != null
        return JMenuItem(if (charted) "Stop Charting “$shown”" else "Chart “$shown” Over Time").apply {
            // Found to hold no number in any shown record.
            isEnabled = variableCharts.canChart(template, variable)
            addActionListener { toggleChart(this@LogTab, variableCharts, template, variable) }
        }
    }

    /** Puts the charts between the histogram and the rows while there are some, and the histogram shows. */
    private fun showCharts() {
        val view = rowsView ?: return
        val split = chartSplit
        val charts = variableCharts.all.isNotEmpty() && LogPart.HISTOGRAM in shownParts
        val wanted = if (split != null && charts) split else view
        if (rowsHolder.componentCount > 0 && rowsHolder.getComponent(0) === wanted) return
        rowsHolder.removeAll()
        if (wanted === split) {
            split.topComponent = chartPanel
            split.bottomComponent = view
        }
        rowsHolder.add(wanted, BorderLayout.CENTER)
        rowsHolder.revalidate()
        rowsHolder.repaint()
        if (wanted === split) {
            if (chartHeight > 0) split.dividerLocation = chartHeight else split.resetToPreferredSizes()
        }
    }

    // Hiding every column happens when the file changed meanwhile, or the names were remembered for another parser.
    private fun applyHiddenColumns(model: LogTableModel) {
        val leavesSome = model.columnChoices.any { it.name !in hiddenColumns }
        model.hiddenColumns = if (leavesSome) hiddenColumns else emptySet()
    }

    private fun autoLabel() = if (session.parserDetected) "Auto: ${session.parser.name}" else "Auto-detect"

    private fun editParsers(select: Parser, adopt: Boolean = false) {
        val dialogs = host.settingsDialogs ?: return
        val window = SwingUtilities.getWindowAncestor(this)
        val picked = dialogs.editParsers(window, path, select, offerUse = !isMerged, adopt = select.takeIf { adopt })
        session.useParser(picked ?: return)
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
        filterReady = visible
        filterBar.isVisible = visible && LogPart.FILTER in shownParts
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
            look.heading(this)
            foreground = look.errorText
        }
        val text = JTextArea(detail).apply {
            isEditable = false
            isOpaque = false
            font = look.monospacedFont
        }
        val components = listOfNotNull<JComponent>(heading, text, hint?.let { JLabel(it) })
        showCentered(*components.toTypedArray())
    }

    private fun showTable(shown: ShownLog) {
        val model = LogTableModel(shown, columnNames)
        applyHiddenColumns(model)
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
            TimeHistogramBar({ session.histogram }, { session.foundHistogram }).apply {
                onJump = ::jumpToSlice
                onRange = { from, to -> filterBar.setTimeRange(field, timeLiteral(from), timeLiteral(to)) }
                onShowAllTimes = { filterBar.setTimeRange(field, null, null) }
                hasTimeRange = { filterBar.hasTimeRange(field) }
            }
        }
        val chartPanel = histogramBar?.let { bar ->
            VariableChartPanel(variableCharts) { bar.timeline }.apply {
                onJump = ::jumpToTime
                onJumpToRecord = ::jumpToRecord
                onRange = bar.onRange
                onPointer = { bar.pointer = it }
                bar.onPointer = { pointer = it }
                onDragged = { bar.draggedTimes = it }
                bar.onDragged = { draggedTimes = it }
                bar.onTimelineChanged = ::repaint
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
        this.chartPanel = chartPanel
        table.found = foundMarks
        filterBar.setSchema(shown.schema)
        templates.canFilter = shown.schema.hasTemplates()
        setFilterVisible(true)

        detail?.show("")
        table.resizedWidths = rememberedWidths
        rowsView = if (findBar.floats) {
            FloatingTopRight(scrollPane, findBar.component)
        } else {
            JPanel(BorderLayout()).apply {
                add(findBar.component, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
            }
        }
        // No arrows: the charts close by their ×. The height they are dragged to holds while the tab is open.
        chartSplit = chartPanel?.let {
            look.verticalSplit().apply {
                border = null
                addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
                    if (isShowing) chartHeight = dividerLocation
                }
            }
        }
        rowsHolder.removeAll()
        showCharts()
        histogramBar?.isVisible = LogPart.HISTOGRAM in shownParts
        rowsPanel = JPanel(BorderLayout()).apply {
            histogramBar?.let { add(it, BorderLayout.NORTH) }
            add(rowsHolder, BorderLayout.CENTER)
        }
        showParts()
        scrollPane.follow = session.follow
        applyFilter()
    }

    /** Lays out the rows and, below them, the detail and the templates as tabs, or those of [shownParts]. */
    private fun showParts() {
        val rows = rowsPanel ?: return
        val bottom = when {
            !togglesParts -> bottomTabs
            LogPart.TEMPLATES in shownParts -> templates
            else -> null
        }
        content.removeAll()
        if (bottom == null) {
            content.add(rows, BorderLayout.CENTER)
            content.revalidate()
            content.repaint()
            return
        }
        val split = look.verticalSplit().apply {
            topComponent = rows
            bottomComponent = bottom
            resizeWeight = 0.75
            // Parts the host toggles have no arrows; else only the one that hides the bottom tabs, and brings them
            // back, not the one that hides the rows.
            if (!togglesParts) {
                isOneTouchExpandable = true
                look.expandableBottomOnly(this)
            }
            border = null
        }
        content.add(split, BorderLayout.CENTER)
        content.validate()
        content.repaint()
        val remembered = host.state[UiState.DETAIL_DIVIDER]?.toDoubleOrNull()
        split.setDividerLocation(remembered?.coerceIn(0.1, 0.95) ?: 0.75)
        // Remembered as a proportion, so it fits any window size; saved once dragging stops, as each save writes a
        // file.
        var proportion = ""
        val saveDivider = Timer(300) { host.state[UiState.DETAIL_DIVIDER] = proportion }.apply { isRepeats = false }
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            val height = split.height - split.dividerSize
            if (height > 0 && split.isShowing) {
                proportion = "%.3f".format(java.util.Locale.ROOT, split.dividerLocation.toDouble() / height)
                saveDivider.restart()
            }
        }
    }

    /** Shows the record at [row] of the shown log, or nothing if there is none. */
    private fun showDetail(model: LogTableModel, row: Int) {
        val detail = detail ?: return
        val record = model.recordAtIndexOrNull(row)
        detail.show(if (record != null) model.shown.recordText(record, MAX_DETAIL_BYTES) else "")
        // A long record, e.g. with a stack trace, shows where the find bar's text is.
        detail.component.caretPosition = detail.firstFound
    }

    /** Shows [record] again if it is selected; it may have grown, e.g. by a stack trace written after it. */
    private fun refreshDetail(record: Int) {
        val detail = detail ?: return
        val model = model ?: return
        val row = table?.selection?.lead ?: return
        if (model.recordAtIndexOrNull(row) != record) return
        val text = model.shown.recordText(record, MAX_DETAIL_BYTES)
        val area = detail.component
        if (text == area.text) return
        val caret = area.caretPosition
        detail.show(text)
        area.caretPosition = caret.coerceAtMost(area.document.length)
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
            detail?.matches = matches
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
        variableCharts.refresh()
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
        findBar.open(detail?.component?.selectedText?.takeIf { it.isNotBlank() && '\n' !in it })
    }

    /** Marks [search] and finds the records it matches, going to the first from the current one on. */
    private fun searchFor(search: TextSearch?) {
        this.search = search
        foundMarks = search?.let(Highlighter::of) ?: Highlighter.NONE
        table?.found = foundMarks
        detail?.found = foundMarks
        if (!findBar.isOpen) table?.requestFocusInWindow()
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
        // A find past the record's first line expands its row, and shows that line.
        val local = index - model.windowStart
        if (local !in 0..<table.rowCount) return
        val y = table.showFound(local)
        if (y >= 0) scrollPane.showInRow(index, y, table.rowHeight)
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
        val marked = if (first >= 0 && last < model.totalRowCount) {
            val from = model.shown.timestamp(model.recordAtIndex(first))
            val to = model.shown.timestamp(model.recordAtIndex(last))
            if (from != null && to != null) minOf(from, to) to maxOf(from, to) else (from ?: to)?.let { it to it }
        } else if (model.totalRowCount > 0) {
            model.shown.timestamp(model.recordAtIndex(scrollPane.firstVisibleRow))?.let { it to it }
        } else {
            null
        }
        bar.markedTimes = marked
        chartPanel?.markedTimes = marked
    }

    /** Selects the row of [record], if it is shown. */
    private fun jumpToRecord(record: Int) {
        val model = model ?: return
        val scrollPane = scrollPane ?: return
        val row = model.indexOfRecord(record).takeIf { it >= 0 && model.recordAtIndex(it) == record } ?: return
        session.follow = false
        scrollPane.selectRow(row)
        table?.requestFocusInWindow()
    }

    /**
     * Selects the first shown row from [from] to [to] (exclusive) at the histogram [category], e.g. the slice's
     * first error; the first row at [from] or later if there is none.
     */
    private fun jumpToSlice(from: Long, to: Long, category: Int) {
        val model = model ?: return
        val row = model.shown.firstRowIn(from, to, category)
        if (row < 0) return jumpToTime(from)
        jumpToRecord(model.shown.recordAt(row))
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

    /** See [LogSession.grew]; callable from any thread. */
    fun fileGrew() = session.grew()

    fun focusFilter() {
        shownParts += LogPart.FILTER
        filterBar.focusFilter()
    }

    /** Whether new records are scrolled to as they come; see [fileControls]. */
    var follow: Boolean
        get() = session.follow
        set(value) {
            session.follow = value
        }

    /** What the parser button of [fileControls] says, for a host that shows the controls in a toolbar of its own. */
    val parserText: String get() = parserButton.text

    /** Opens the parser menu of [fileControls] under [anchor] instead, e.g. a button of the host's toolbar. */
    fun showParserMenu(anchor: JComponent) = showParsers(anchor)

    /** Whether the columns can be chosen yet: the records are there. */
    val canChooseColumns: Boolean get() = columnsButton.isEnabled

    /** Opens the columns menu of [fileControls] under [anchor] instead. */
    fun showColumnsMenu(anchor: JComponent) = showColumns(anchor)

    /** Goes to the next record with the find bar's text, or the previous; opens the bar if it is closed. */
    fun findNext(forward: Boolean) = find(forward)

    fun clearFilter() = filterBar.clear()

    /** The filter as edited in this tab. */
    val filter: SavedFilter get() = filterBar.current()

    /** Cancels any running work and releases the file. */
    fun dispose() {
        stopProgress()
        templates.reset()
        variableCharts.reset()
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
            table?.let { rememberedWidths = it.resizedWidths }
            histogramBar = null
            chartPanel = null
            chartSplit = null
            rowsView = null
            rowsPanel = null
            templates.reset()
            variableCharts.reset()
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
            table?.recordsGrew(changed)
            changed.forEach(::refreshDetail)
        }

        override fun rowsReplaced(keepView: Boolean, oldSize: Int, recordBefore: (row: Int) -> Int) =
            showRows(keepView, oldSize, recordBefore)

        override fun rowsChanged(firstChanged: Int, oldSize: Int) {
            model?.rowsChangedFrom(firstChanged, oldSize)
            scrollPane?.rowsAppended()
            templates.refreshSoon()
            variableCharts.refreshSoon()
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
