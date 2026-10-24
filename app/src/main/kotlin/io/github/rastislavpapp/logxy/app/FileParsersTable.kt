package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.parse.mismatch
import io.github.rastislavpapp.logxy.ui.Icons
import io.github.rastislavpapp.logxy.ui.PopupItem
import io.github.rastislavpapp.logxy.ui.escapeHtml
import io.github.rastislavpapp.logxy.ui.showPopupList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.nio.file.Path
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * A table of [files], shown by their [names] with their [descriptions] in tooltips, and the parser for each,
 * null for Auto-detect. A parser is chosen per file, or for all files at once through a combo in the parser
 * column's header. Each file is checked right away against the start of the file, with its chosen parser or,
 * on Auto-detect, with the parser detection picks. A check mark shows that the parser fits, a warning that it
 * does not. If [excludable], a checkbox per file leaves it out. [onChange] is called on any change. Call
 * [dispose] when done.
 */
class FileParsersTable(
    private val files: List<Path>,
    private val names: List<String>,
    private val descriptions: List<String>,
    initial: List<Parser?> = emptyList(),
    private val excludable: Boolean = false,
    private val onChange: () -> Unit = {},
) {
    private val parsers: List<Parser?> = listOf(null) + AppSettings.current.knownParsers
    private val chosen = HashMap<Path, Parser?>().apply {
        files.forEachIndexed { i, file -> put(file, initial.getOrNull(i)) }
    }
    private val fileColumn = if (excludable) 1 else 0
    private val parserColumn = fileColumn + 1
    private val excluded = HashSet<Path>()
    private val model = FilesModel()
    private val table = JTable(model)

    /** Painted in the parser column's header; a click there sets the parser of all files. */
    private val headerCombo = JComboBox<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val checks = HashMap<Path, Job>()

    /** Why a file's chosen parser does not fit it, for the files it does not. */
    private val problems = HashMap<Path, String>()

    /** Files whose parser was checked and fits. */
    private val fitting = HashSet<Path>()

    /** The parser detection picked, for Auto-detect files. */
    private val detected = HashMap<Path, Parser>()

    // Always there, so that showing a warning does not resize the dialog.
    private val warning = JLabel(" ").apply { horizontalAlignment = SwingConstants.LEADING }
    private val scrollPane = JScrollPane(table)

    val component = JPanel(MigLayout("fill, insets 0, wrap 1", "[grow, fill]", "[grow][]")).apply {
        add(scrollPane, "grow")
        add(warning)
    }

    /** The files listed, e.g. all but those in subfolders; [included] and [chosenParsers] are of these. */
    var shown: List<Path> = files
        set(value) {
            field = value
            changed()
        }

    /** The files of [shown] not left out. */
    val included: List<Path> get() = shown.filter { it !in excluded }

    /** The parser chosen for each of [included], null where it is detected. */
    val chosenParsers: List<Parser?> get() = included.map { chosen[it] }

    /** Whether every file included was checked and fits its parser, so the files can be opened. */
    val allFit: Boolean get() = included.isNotEmpty() && included.all { it in fitting }

    /** Why the files cannot be opened yet; null if [allFit]. */
    val notReady: String?
        get() = when {
            included.isEmpty() -> "No files to open"
            included.any { it in problems } && excludable ->
                "A file does not match its parser; choose another or leave the file out"
            included.any { it in problems } -> "A file does not match its parser"
            !allFit -> "Checking the files"
            else -> null
        }

    init {
        table.apply {
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
            fillsViewportHeight = true
            columnModel.getColumn(fileColumn).preferredWidth = fileWidth()
            columnModel.getColumn(fileColumn).cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
                ): Component {
                    // Before super, which takes it as the unselected color; null for the table's own.
                    foreground = if (shown[row] in excluded) UIManager.getColor("Label.disabledForeground") else null
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    toolTipText = descriptions[files.indexOf(shown[row])]
                    return this
                }
            }
            columnModel.getColumn(parserColumn).preferredWidth = parserWidth()
            columnModel.getColumn(parserColumn).headerRenderer = ParserHeader()
            val combo = JComboBox(parsers.toTypedArray()).apply { renderer = ParserRenderer() }
            columnModel.getColumn(parserColumn).cellEditor = DefaultCellEditor(combo).apply { clickCountToStart = 1 }
            if (excludable) {
                columnModel.getColumn(0).apply {
                    val width = tableHeader.getFontMetrics(tableHeader.font).stringWidth("Open") + 16
                    minWidth = width
                    maxWidth = width
                }
            }
            columnModel.getColumn(parserColumn).cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
                ): Component {
                    val file = shown[row]
                    foreground = if (file in excluded) UIManager.getColor("Label.disabledForeground") else null
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    val parser = value as Parser?
                    val auto = if (parser == null) detected[file]?.let { "Auto: ${it.name}" } else null
                    text = (auto ?: parserName(parser)) + "  ⌄"
                    icon = when {
                        file in problems -> Icons.warning
                        file in fitting -> Icons.fits
                        else -> null
                    }
                    horizontalTextPosition = LEADING
                    toolTipText = when {
                        file in problems -> htmlLines(problems.getValue(file))
                        file in fitting -> "The parser fits the start of the file"
                        else -> "Checking"
                    }
                    return this
                }
            }
        }
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (table.tableHeader.columnAtPoint(e.point) == parserColumn && files.size > 1) chooseForAll()
            }
        })
        fun height(rows: Int) = table.tableHeader.preferredSize.height + table.rowHeight * rows + 4
        val width = (0..<table.columnCount).sumOf { table.columnModel.getColumn(it).preferredWidth }
        val scrollBar = scrollPane.verticalScrollBar.preferredSize.width
        scrollPane.preferredSize = Dimension(width + scrollBar + 4, height(files.size.coerceIn(3, 12)))
        scrollPane.minimumSize = Dimension(0, height(3))
        files.forEach(::check)
    }

    /** Stops checks still running. */
    fun dispose() {
        scope.cancel()
    }

    /**
     * Checks [file] against its parser off the UI thread: the chosen one, or for Auto-detect the one detection
     * picks.
     */
    private fun check(file: Path) {
        checks.remove(file)?.cancel()
        problems.remove(file)
        fitting.remove(file)
        detected.remove(file)
        val chosen = chosen[file]
        val preferred = AppSettings.current.parsers
        val builtIn = AppSettings.current.builtInParsers
        checks[file] = scope.launch {
            var found: Parser? = null
            val problem = withContext(Dispatchers.IO) {
                try {
                    val parser = chosen ?: Parser.detect(file, preferred, builtIn).also { found = it }
                    parser.mismatch(file)
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            found?.let { detected[file] = it }
            if (problem != null) problems[file] = problem else fitting.add(file)
            checks.remove(file)
            changed()
        }
    }

    /** Wide enough for the longest parser name, as "Auto: name ⌄" with a check mark, so none is cut. */
    private fun parserWidth(): Int {
        val metrics = table.getFontMetrics(table.font)
        val longest = parsers.filterNotNull().maxOf { metrics.stringWidth("Auto: ${it.name}  ⌄") }
        return longest + Icons.fits.iconWidth + CELL_PADDING
    }

    /** Wide enough for the file names, within reason. */
    private fun fileWidth(): Int {
        val metrics = table.getFontMetrics(table.font)
        return (names.maxOfOrNull { metrics.stringWidth(it) } ?: 0).plus(CELL_PADDING).coerceIn(240, 480)
    }

    /** Ends editing a cell, so its value counts. */
    fun stopEditing() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
    }

    private fun changed() {
        model.fireTableDataChanged()
        table.tableHeader.repaint()
        val mismatched = included.filter { it in problems }
        warning.icon = if (mismatched.isEmpty()) null else Icons.warning
        warning.text = when {
            mismatched.size == 1 -> {
                val name = names[files.indexOf(mismatched.single())]
                "$name: its first entries do not look like its parser expects."
            }
            mismatched.size > 1 ->
                "${mismatched.size} files: their first entries do not look like their parsers expect."
            included.any { it in checks } -> "Checking the files…"
            else -> " "
        }
        warning.toolTipText = mismatched.firstOrNull()?.let { problems[it] }?.let(::htmlLines)
        onChange()
    }

    /** Sets the parser of all files listed. */
    private fun chooseForAll() {
        stopEditing()
        val listed = listedParsers()
        val items = parsers.map { parser ->
            PopupItem(parserName(parser), selected = listed.size == 1 && listed.single() == parser) {
                shown.forEach {
                    chosen[it] = parser
                    check(it)
                }
                changed()
            }
        }
        val header = table.tableHeader
        showPopupList(header, items, header.getHeaderRect(parserColumn).x)
    }

    /** The parsers of the listed files, each once; null is Auto-detect. */
    private fun listedParsers(): List<Parser?> = shown.map { chosen[it] }.distinct()

    private inner class FilesModel : AbstractTableModel() {
        override fun getRowCount() = shown.size
        override fun getColumnCount() = parserColumn + 1
        override fun getColumnName(column: Int) = when (column) {
            fileColumn -> "File"
            parserColumn -> "Parser"
            else -> "Open"
        }
        override fun getColumnClass(column: Int): Class<*> =
            if (column < fileColumn) Boolean::class.javaObjectType else Any::class.java
        override fun isCellEditable(row: Int, column: Int) = column != fileColumn

        override fun getValueAt(row: Int, column: Int): Any? {
            val file = shown[row]
            return when (column) {
                fileColumn -> names[files.indexOf(file)]
                parserColumn -> chosen[file]
                else -> file !in excluded
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val file = shown[row]
            if (column < fileColumn) {
                if (value == true) excluded.remove(file) else excluded.add(file)
                changed()
                return
            }
            if (chosen[file] == value) return
            chosen[file] = value as Parser?
            check(file)
            // Later: setValueAt runs while the editor is still being removed.
            SwingUtilities.invokeLater { changed() }
        }
    }

    /** "Parser" and a combo showing the parser of all files (or "Mixed"), which a click on the header opens. */
    private inner class ParserHeader : TableCellRenderer {
        private val panel = JPanel(MigLayout("insets 2 6 2 4, gap 8", "[][grow, fill]")).apply {
            add(JLabel("Parser"))
            add(headerCombo)
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val listed = listedParsers()
            val label = if (listed.size == 1) parserName(listed.single()) else "Mixed"
            headerCombo.model = DefaultComboBoxModel(arrayOf(label))
            headerCombo.isVisible = files.size > 1
            panel.background = table.tableHeader.background
            return panel
        }
    }

    private class ParserRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component =
            super.getListCellRendererComponent(list, parserName(value as Parser?), index, isSelected, cellHasFocus)
    }

    private companion object {
        const val CELL_PADDING = 24

        fun parserName(parser: Parser?) = parser?.name ?: "Auto-detect"

        /** [text] as a tooltip keeping its line breaks. */
        fun htmlLines(text: String): String = "<html>" + escapeHtml(text).replace("\n", "<br>") + "</html>"
    }
}
