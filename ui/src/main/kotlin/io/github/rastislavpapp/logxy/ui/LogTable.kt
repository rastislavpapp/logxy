package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.merge.TimestampShift
import io.github.rastislavpapp.logxy.core.query.Marker
import io.github.rastislavpapp.logxy.core.session.Selection
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.event.TableModelEvent
import javax.swing.plaf.basic.BasicGraphicsUtils
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

private const val CELL_PADDING = 12

/**
 * The last column (usually the message) keeps this many characters; when columns do not fit, the others shrink
 * first.
 */
private const val MIN_LAST_COLUMN_CHARS = 40

/** What an expanded record ends with when it is longer than [MAX_DETAIL_BYTES]. */
private const val CUT_MARK = "… cut at 1 MB"

/**
 * The tallest an expanded row may be, in pixels, so that the y of every row of the table's window fits in an int
 * however long the expanded records are.
 */
private const val MAX_ROW_HEIGHT = 1 shl 22

/** Background of text matched by the filter, translucent to suit light and dark looks. */
val MARK_COLOR = Color(255, 200, 0, 110)

/** Background of text the find bar found; unlike the filter's, also shown on selected rows. */
val FOUND_COLOR = Color(40, 200, 90, 130)

/** The text color of [level]: red for errors, yellow for warnings, dim for debug output, else [default]. */
fun levelColor(level: Level?, default: Color): Color = when (level) {
    Level.ERROR, Level.FATAL -> look.error
    Level.WARN -> look.warning
    Level.TRACE, Level.DEBUG -> UIManager.getColor("Label.disabledForeground")
    else -> default
}

/**
 * Table of log records; the last column always fills the viewport and long values are cut. A record of several lines,
 * or a first line too long for its cell, shows what fits, with an icon at the start of the last column that expands
 * its row to all of it.
 */
class LogTable(private val logModel: LogTableModel) : JTable(logModel) {

    private val rowSelection = WindowSelectionModel(logModel)

    /** Column widths by name, kept when columns are hidden or shown; not of the last column, which fills the rest. */
    private val widths = HashMap<Any, Int>()

    /** The columns of [widths] whose width the user dragged; the others' follow the records shown. */
    private val resized = HashSet<Any>()

    /** Records whose rows show all their lines; they stay so while rows come and go, e.g. by a filter. */
    private val expanded = HashSet<Int>()

    /** The text of each of [expanded], read once it is painted. */
    private val expandedTexts = HashMap<Int, ExpandedText>()

    /** Where the rows lie; null when they must be laid out again, e.g. after the rows or [expanded] changed. */
    private var rowHeights: RowHeights? = null

    /** The width of the last column [rowHeights] wrapped expanded rows to. */
    private var wrapWidth = -1

    private val expandedRenderer = ExpandedRenderer()

    /** Measures the cells' insets. */
    private val plainRenderer = DefaultTableCellRenderer()

    private val expandIcon = ExpandIcon()

    /**
     * Whether the last click on a row expanded or collapsed it, on its expand icon or hint, so the double click it
     * starts toggles nothing more.
     */
    private var clickedToggle = false

    /**
     * The widths in pixels the user dragged columns to, by field name, also of hidden columns. Widths set here count
     * as dragged too.
     */
    var resizedWidths: Map<String, Int>
        get() {
            rememberWidths()
            return resized.mapNotNull { id -> widths[id]?.let { "$id" to it } }.toMap()
        }
        set(value) {
            widths.putAll(value)
            resized.addAll(value.keys)
            restoreWidths()
        }

    /** Marks what the filter matched in the cells. */
    var matches: Marker = Marker.NONE
        set(value) {
            field = value
            repaint()
        }

    /** Marks what the find bar found in the cells, also of selected rows. */
    var found: Marker = Marker.NONE
        set(value) {
            field = value
            repaint()
        }

    init {
        autoResizeMode = AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        fillsViewportHeight = true
        selectionModel = rowSelection
        tableHeader.reorderingAllowed = false
        (tableHeader.defaultRenderer as? JLabel)?.horizontalAlignment = SwingConstants.LEADING
        updateLook()
        installExpandKeys()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val row = rowAtPoint(e.point)
                if (row < 0) return
                if (e.clickCount == 1) {
                    clickedToggle = isOnToggle(row, e.point)
                    if (clickedToggle) toggleExpanded(row)
                } else if (e.clickCount == 2 && !clickedToggle) {
                    toggleExpanded(row)
                }
            }
        })
    }

    /** The selected rows of the shown log, not only of those the table holds. */
    val selection: Selection get() = rowSelection.selection

    // Selects all rows of the shown log, not only those the table holds.
    override fun selectAll() = rowSelection.selectAll()

    // Extending goes from the anchor wherever it is; outside the rows the table holds, JTable would not see it.
    // An expanded row scrolls only its first line into view: all of it would move the view under a click on it.
    override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
        val scroll = autoscrolls
        autoscrolls = false
        try {
            if (extend) {
                if (toggle) rowSelection.extendKeepingOthers(rowIndex) else rowSelection.extendTo(rowIndex)
                columnModel.selectionModel.setSelectionInterval(columnIndex, columnIndex)
            } else {
                super.changeSelection(rowIndex, columnIndex, toggle, extend)
            }
        } finally {
            autoscrolls = scroll
        }
        if (!scroll) return
        val cell = getCellRect(rowIndex, columnIndex, false)
        cell.height = minOf(cell.height, rowHeight)
        scrollRectToVisible(cell)
    }

    // JTable spreads spare width over all columns; give it all to the last one, unless the user is
    // dragging a column border (then JTable keeps the dragged widths as the new preferred ones).
    override fun doLayout() {
        tableHeader?.resizingColumn?.let { resized += it.identifier }
        if (tableHeader?.resizingColumn == null && columnModel.columnCount > 0) {
            val last = columnModel.getColumn(columnModel.columnCount - 1)
            val others = (0..<columnModel.columnCount - 1).sumOf { columnModel.getColumn(it).preferredWidth }
            last.preferredWidth = maxOf(last.minWidth, width - others)
        }
        super.doLayout()
        // Expanded rows wrap to the last column: as it gets wider or narrower, they get lower or higher.
        val column = expandingColumn()
        if (expanded.isNotEmpty() && column >= 0 && columnModel.getColumn(column).width != wrapWidth) layOutRowsAgain()
    }

    override fun updateUI() {
        super.updateUI()
        // Also called from JTable's constructor, before the model is set.
        @Suppress("SENSELESS_COMPARISON")
        if (logModel != null) updateLook()
    }

    // JTable clears the selection when the model's columns change, e.g. one is hidden; the rows stay, so it stays.
    override fun tableChanged(e: TableModelEvent?) {
        // Also called from JTable's constructor, before the selection model is set.
        @Suppress("SENSELESS_COMPARISON")
        val columnsChanged = rowSelection != null && (e == null || e.firstRow == TableModelEvent.HEADER_ROW)

        // Any change may move the expanded rows, e.g. when the window moves; JTable asks where rows are meanwhile.
        @Suppress("SENSELESS_COMPARISON")
        val hasExpanded = expanded != null && expanded.isNotEmpty()
        if (hasExpanded) rowHeights = null
        if (columnsChanged) rowSelection.keeping { super.tableChanged(e) } else super.tableChanged(e)
        if (hasExpanded) layOutRowsAgain()
    }

    // Columns are created again when the model's columns change, e.g. some are hidden.
    override fun createDefaultColumnsFromModel() {
        // Also called from JTable's constructor, before the model is set.
        @Suppress("SENSELESS_COMPARISON")
        val ready = logModel != null
        if (ready) rememberWidths()
        super.createDefaultColumnsFromModel()
        if (ready) {
            setUpColumns()
            restoreWidths()
            // The expanded text starts with the last column's value, which may now be another field's.
            expandedTexts.clear()
        }
    }

    // Rows are rowHeight high but the expanded ones: JTable's own per-row heights would be lost each time the
    // window moves or the rows are replaced, so the rows are laid out here.
    override fun getRowHeight(row: Int): Int = rowLayout()?.height(row) ?: super.getRowHeight(row)

    override fun getCellRect(row: Int, column: Int, includeSpacing: Boolean): Rectangle {
        val rect = super.getCellRect(row, column, includeSpacing)
        val heights = rowLayout() ?: return rect
        // JTable puts each row at row × its own height.
        if (row in 0..<rowCount) rect.y += heights.top(row) - row * heights.height(row)
        return rect
    }

    override fun rowAtPoint(point: Point): Int {
        val heights = rowLayout() ?: return super.rowAtPoint(point)
        return heights.rowAt(point.y, rowCount)
    }

    override fun getCellRenderer(row: Int, column: Int): TableCellRenderer =
        if (expanded.isNotEmpty() && column == expandingColumn() && isExpanded(row)) {
            expandedRenderer
        } else {
            super.getCellRenderer(row, column)
        }

    /** Whether [row] of the window has more to show expanded: later lines, or a first line too long for its cell. */
    fun isExpandable(row: Int): Boolean {
        val column = expandingColumn()
        return column >= 0 && (logModel.moreLines(row) > 0 || overflows(row, column))
    }

    /** Whether the first line of [row]'s text in [column] is wider than the column shows. */
    private fun overflows(row: Int, column: Int): Boolean =
        CellCut(this, textWidth(row, column)).isCut(getValueAt(row, column) as String)

    /** Whether [row] of the window shows all lines of its record. */
    fun isExpanded(row: Int): Boolean = logModel.recordAt(row) in expanded

    /** Shows all lines of the record at [row] of the window, if it has several, or only its first. */
    fun setExpanded(row: Int, expand: Boolean) {
        if (expand && !isExpandable(row)) return
        val record = logModel.recordAt(row)
        val changed = if (expand) expanded.add(record) else expanded.remove(record)
        if (!changed) return
        if (!expand) expandedTexts.remove(record)
        layOutRowsAgain()
    }

    private fun toggleExpanded(row: Int) = setExpanded(row, !isExpanded(row))

    /** Call after the [changed] records got more text, e.g. a stack trace written after them. */
    fun recordsGrew(changed: IntArray) {
        for (record in changed) expandedTexts.remove(record)
        if (expanded.isNotEmpty()) layOutRowsAgain()
    }

    /**
     * Expands [row] of the window when the find bar's hits in it are hidden while it is collapsed: in a later line of
     * its record, or past the "..." of a first line too long for its cell. Returns the y, within the row, of the
     * wrapped line with the first hit, to scroll to; -1 when no scrolling is needed, e.g. the hit is on the first line.
     */
    fun showFound(row: Int): Int {
        val column = expandingColumn()
        if (column < 0 || found.isEmpty || !isExpandable(row)) return -1
        val collapsed = !isExpanded(row)
        if (collapsed && isFoundShown(row, column)) return -1
        val field = logModel.fieldAt(column)!!.name
        val ranges = expandedText(row, column).found(found, field)
        if (ranges.isEmpty()) return -1
        val line = wrapped(row, column).lineOf(ranges[0])
        if (collapsed) {
            setExpanded(row, true)
        } else if (line == 0) {
            return -1
        }
        return firstLineTop() + line * lineHeight()
    }

    /**
     * Whether a find bar hit in the collapsed [row] is visible. A hit in any cell counts, except that in [column] a hit
     * past the "..." of a cut value is hidden.
     */
    private fun isFoundShown(row: Int, column: Int): Boolean = (0..<columnCount).any { cell ->
        val field = logModel.fieldAt(cell) ?: return@any false
        val ranges = found.ranges(field.name, getValueAt(row, cell) as String)
        // Ranges are sorted: the first decides whether any is before the cut.
        ranges.isNotEmpty() && (cell != column || ranges[0] < shownLength(row, cell))
    }

    /** How many characters of the value in the collapsed cell at [row], [column] are visible, before any "...". */
    private fun shownLength(row: Int, column: Int): Int {
        val renderer = prepareRenderer(getCellRenderer(row, column), row, column) as JLabel
        renderer.setBounds(0, 0, columnModel.getColumn(column).width, rowHeight)
        return layOut(renderer, renderer.text, Rectangle())
    }

    /** The last column, where expanded rows show their records; -1 if it shows no field, e.g. the line numbers. */
    private fun expandingColumn(): Int {
        val column = columnCount - 1
        // While the model's columns change, e.g. one is hidden, the table still has the old ones: none fits yet.
        if (column < 0 || column >= logModel.columnCount) return -1
        return if (logModel.fieldAt(column) != null) column else -1
    }

    /** Lays out the rows again soon: [expanded] or what they show changed. */
    private fun layOutRowsAgain() {
        rowHeights = null
        revalidate()
        repaint()
    }

    /** Where the rows lie; null while all are rowHeight high, so JTable lays them out. */
    private fun rowLayout(): RowHeights? {
        // Also called from JTable's constructor, before the expanded records are set.
        @Suppress("SENSELESS_COMPARISON")
        if (expanded == null || expanded.isEmpty()) return null
        val known = rowHeights
        if (known != null) return if (known.isUniform) null else known
        val column = expandingColumn()
        val rows = ArrayList<Int>()
        val heights = ArrayList<Int>()
        if (column >= 0) {
            for (record in expanded) {
                val index = logModel.indexOfRecord(record)
                if (index < 0 || logModel.recordAtIndex(index) != record) continue
                val row = index - logModel.windowStart
                if (row !in 0..<rowCount) continue
                rows += row
                heights += expandedHeight(row, column)
            }
        }
        wrapWidth = if (column >= 0) columnModel.getColumn(column).width else -1
        val laidOut = RowHeights(rowHeight, rows.toIntArray(), heights.toIntArray())
        rowHeights = laidOut
        return if (laidOut.isUniform) null else laidOut
    }

    private fun expandedHeight(row: Int, column: Int): Int {
        val lines = wrapped(row, column).lineCount.toLong()
        val height = lines * lineHeight() + rowHeight - lineHeight()
        return minOf(height, MAX_ROW_HEIGHT.toLong()).toInt()
    }

    private fun lineHeight() = getFontMetrics(font).height

    /** The y of the first line's text in a row, as a label centers it in a row of rowHeight. */
    private fun firstLineTop() = (rowHeight - lineHeight()) / 2

    private fun expandedText(row: Int, column: Int): ExpandedText {
        val record = logModel.recordAt(row)
        return expandedTexts.getOrPut(record) {
            val laterLines = logModel.shown.laterLines(record, MAX_DETAIL_BYTES, CUT_MARK)
            ExpandedText.of(logModel.detailValueAt(row, column), laterLines)
        }
    }

    /** The expanded text at [row] wrapped to the width of [column]. */
    private fun wrapped(row: Int, column: Int): WrappedText {
        val chars = columnChars(row, column)
        return expandedText(row, column).wrapped(chars, chars)
    }

    /** How many characters of the monospaced font fit in [column] at [row]; see [textWidth]. */
    private fun columnChars(row: Int, column: Int): Int = textWidth(row, column) / getFontMetrics(font).charWidth('0')

    /**
     * The width in pixels left for text in [column] at [row]: the column's width minus the cell's insets and, in the
     * column that expands, minus the room for the expand icon.
     */
    private fun textWidth(row: Int, column: Int): Int {
        plainRenderer.getTableCellRendererComponent(this, "", false, false, row, column)
        val insets = plainRenderer.insets
        val indent = if (column == expandingColumn()) expandIcon.indent() else 0
        return columnModel.getColumn(column).width - insets.left - insets.right - indent
    }

    /** Whether a click at [point] expands or collapses [row]: on its expand icon, or on its "… +N lines" hint. */
    private fun isOnToggle(row: Int, point: Point): Boolean {
        val column = columnAtPoint(point)
        if (column != expandingColumn()) return false
        val prepared = prepareRenderer(getCellRenderer(row, column), row, column)
        val renderer = prepared as? CellRenderer ?: return false
        val cell = getCellRect(row, column, false)
        renderer.setBounds(0, 0, cell.width, cell.height)
        return renderer.togglesAt(point.x - cell.x, point.y - cell.y)
    }

    // → and ← would move between cells, which the table does not select: they expand and collapse the lead row.
    private fun installExpandKeys() {
        fun bind(keys: String, name: String, expand: Boolean) {
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val row = selection.lead - logModel.windowStart
                    if (row in 0..<rowCount && selection.lead in selection) setExpanded(row, expand)
                }
            })
            val inputMap = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            keys.split(",").forEach { inputMap.put(KeyStroke.getKeyStroke(it.trim()), name) }
        }
        bind("RIGHT, KP_RIGHT", "logxyExpandRow", expand = true)
        bind("LEFT, KP_LEFT", "logxyCollapseRow", expand = false)
    }

    /** Heads the columns with their names again, e.g. after a rename; widths stay. */
    fun columnNamesChanged() {
        for (column in 0..<columnModel.columnCount) {
            columnModel.getColumn(column).headerValue = logModel.getColumnName(column)
        }
        tableHeader.repaint()
    }

    private fun updateLook() {
        val base = look.monospacedFont
        // A plain Font, not a FontUIResource, so a theme switch does not replace it.
        font = Font(base.name, base.style, base.size)
        rowHeight = getFontMetrics(font).height + 4
        expandIcon.reload()
        // Widths are measured in the new font; only the dragged ones stay.
        widths.keys.retainAll(resized)
        setUpColumns()
        restoreWidths()
        rowHeights = null
    }

    private fun rememberWidths() {
        for (column in 0..<columnModel.columnCount - 1) {
            val tableColumn = columnModel.getColumn(column)
            widths[tableColumn.identifier] = tableColumn.preferredWidth
        }
    }

    private fun restoreWidths() {
        for (column in 0..<columnModel.columnCount - 1) {
            val tableColumn = columnModel.getColumn(column)
            val width = widths[tableColumn.identifier] ?: continue
            if (tableColumn.minWidth == tableColumn.maxWidth) continue
            tableColumn.preferredWidth = maxOf(width, tableColumn.minWidth)
            tableColumn.width = tableColumn.preferredWidth
        }
    }

    /** Renderers and widths by what each column shows. */
    private fun setUpColumns() {
        val charWidth = getFontMetrics(font).charWidth('0')

        for (column in 0..<columnModel.columnCount) {
            val field = logModel.fieldAt(column)
            val tableColumn = columnModel.getColumn(column)
            // By the field, not the header, so a renamed column keeps its width.
            tableColumn.identifier = field?.name ?: "#"
            tableColumn.cellRenderer = when {
                field == null -> CellRenderer().apply {
                    horizontalAlignment = SwingConstants.RIGHT
                    foreground = UIManager.getColor("Label.disabledForeground")
                }
                field.role == Role.LEVEL -> LevelRenderer()
                else -> MarkRenderer()
            }
            if (column == columnModel.columnCount - 1) {
                tableColumn.minWidth = MIN_LAST_COLUMN_CHARS * charWidth
                continue
            }
            val chars = when {
                field == null -> maxOf(logModel.totalRowCount.toString().length, 3)
                field.type == ColumnType.TIMESTAMP -> sampleLength(column, 23) + shiftMarkChars(field.role)
                field.role == Role.LEVEL -> 5
                field.role == Role.SOURCE -> sampleWidest(column).coerceIn(6, 32) + shiftMarkChars(field.role)
                field.role == Role.THREAD -> 18
                field.role == Role.LOGGER -> 36
                // E.g. MDC keys: short values (ids, numbers) leave more room for the message.
                else -> sampleWidest(column).coerceIn(maxOf(4, field.name.length), 20)
            }
            // Themes differ in cell margins; without them, timestamps and levels would be cut.
            val margins = look.cellMargins
            val width = chars * charWidth + CELL_PADDING + margins.left + margins.right
            tableColumn.preferredWidth = width
            tableColumn.width = width
            if (field == null || field.type == ColumnType.TIMESTAMP) {
                tableColumn.minWidth = width
                tableColumn.maxWidth = width
            }
            // Narrow windows shrink the other columns; a cut level would be unreadable.
            if (field?.role == Role.LEVEL) tableColumn.minWidth = width
        }
    }

    /**
     * The room the longest timestamp shift mark takes after a value of [role], in characters: ` +5:30` after a time,
     * ` (+5:30)` after a source; 0 if no file is shifted.
     */
    private fun shiftMarkChars(role: Role?): Int {
        val longest = logModel.shown.timestampShifts.maxOfOrNull { TimestampShift.format(it).length } ?: 0
        return when {
            longest == 0 -> 0
            role == Role.SOURCE -> longest + 3
            else -> longest + 1
        }
    }

    /** The longest of the first values, e.g. file names. */
    private fun sampleWidest(column: Int): Int =
        (0..<minOf(rowCount, 1000)).maxOfOrNull { logModel.sampleText(it, column).length } ?: 0

    /** Timestamps all have the layout's width; measure the first one. */
    private fun sampleLength(column: Int, fallback: Int): Int =
        (0..<minOf(rowCount, 100)).map { logModel.sampleText(it, column).length }.firstOrNull { it > 0 } ?: fallback

    /**
     * The character of the text in the cell at [row] and [column] that is painted at [x]; -1 if none is, e.g. past
     * its end or where a long value is cut off.
     */
    fun textOffsetAt(row: Int, column: Int, x: Int): Int {
        val renderer = prepareRenderer(getCellRenderer(row, column), row, column) as? JLabel ?: return -1
        val text = renderer.text ?: return -1
        val cell = getCellRect(row, column, false)
        renderer.setBounds(0, 0, cell.width, cell.height)
        val textBounds = Rectangle()
        val visible = layOut(renderer, text, textBounds)
        val metrics = renderer.getFontMetrics(renderer.font)
        var right = cell.x + textBounds.x
        if (x < right) return -1
        for (i in 0..<visible) {
            right += metrics.charWidth(text[i])
            if (x < right) return i
        }
        return -1
    }

    /**
     * Lays out [text] in [label] as the label paints it, and puts where the text goes into [textBounds]. Returns how
     * many of its characters are shown, before the "..." of a cut value.
     */
    private fun layOut(label: JLabel, text: String, textBounds: Rectangle): Int {
        val insets = label.insets
        val view = Rectangle(
            insets.left,
            insets.top,
            label.width - insets.left - insets.right,
            label.height - insets.top - insets.bottom,
        )
        val displayed = SwingUtilities.layoutCompoundLabel(
            label, label.getFontMetrics(label.font), text, null, label.verticalAlignment, label.horizontalAlignment,
            label.verticalTextPosition, label.horizontalTextPosition, view, Rectangle(), textBounds, 0,
        )
        return CellCut.shownLength(text, displayed)
    }

    /**
     * Fills with [color] the parts of [ranges] that fall in `[from, to)`, the marks on one line of [text]; the line
     * is drawn from [x], [y].
     */
    private fun mark(g: Graphics, text: String, from: Int, to: Int, ranges: IntArray, x: Int, y: Int, color: Color) {
        if (ranges.isEmpty()) return
        val metrics = g.fontMetrics
        g.color = color
        // The first range ending past from: ranges are sorted and do not overlap.
        var low = 0
        var high = ranges.size / 2
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ranges[middle * 2 + 1] <= from) low = middle + 1 else high = middle
        }
        var i = low * 2
        while (i < ranges.size && ranges[i] < to) {
            val start = maxOf(ranges[i], from)
            val end = minOf(ranges[i + 1], to)
            val left = x + metrics.stringWidth(text.substring(from, start))
            g.fillRect(left, y, metrics.stringWidth(text.substring(start, end)), metrics.height + 2)
            i += 2
        }
    }

    /**
     * Renders a cell, on the color of its row's row highlight unless the row is selected. In a row taller than
     * rowHeight, it puts the text at the top, where it would be in a row rowHeight high.
     */
    private open inner class CellRenderer : DefaultTableCellRenderer() {
        protected var isSelected = false

        /** Whether this cell shows the [ExpandIcon]: it is in the column that expands, and its row can expand. */
        private var showsExpandIcon = false
        private var rowExpanded = false

        /** The x the [ExpandIcon] is drawn at: the cell's left inset. */
        private var expandIconX = 0

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            // Set before, as the background of an unselected cell, which the superclass keeps; null takes the
            // table's own.
            background = logModel.rowHighlight(row)?.let(look::rowHighlight)
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            this.isSelected = isSelected
            showsExpandIcon = false
            if (column == expandingColumn()) {
                // Read before the room for the icon is added to the insets below.
                expandIconX = insets.left
                rowExpanded = isExpanded(row)
                showsExpandIcon = rowExpanded || isExpandable(row)
                border = CompoundBorder(border, EmptyBorder(0, expandIcon.indent(), 0, 0))
            }
            val tall = getRowHeight(row) > rowHeight
            verticalAlignment = if (tall) TOP else CENTER
            if (tall) {
                val insets = insets
                val top = (rowHeight - insets.top - insets.bottom - getFontMetrics(font).height) / 2
                border = CompoundBorder(border, EmptyBorder(top, 0, 0, 0))
            }
            return this
        }

        /** Whether a click at [x], [y] in the cell expands or collapses its row: in [ExpandIcon.hitArea]. */
        open fun togglesAt(x: Int, y: Int): Boolean =
            showsExpandIcon && expandIcon.hitArea(expandIconX, rowHeight).contains(x, y)

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (showsExpandIcon) expandIcon.paint(this, g, expandIconX, rowHeight, rowExpanded, isSelected, foreground)
        }

        /** Draws [hint] dimmed, unless the row is selected, in the font [g] has. */
        protected fun drawHint(g: Graphics, hint: String, x: Int, baseline: Int) {
            g.color = if (isSelected) foreground else UIManager.getColor("Label.disabledForeground")
            BasicGraphicsUtils.drawString(this, g as Graphics2D, hint, x.toFloat(), baseline.toFloat())
        }
    }

    /** Paints the matches behind the text of left-aligned cells; ends a record's first line with how many follow. */
    private open inner class MarkRenderer : CellRenderer() {
        private var ranges = IntArray(0)
        private var foundRanges = IntArray(0)

        /** After the text of a record with more lines: how many there are. */
        private var more = ""

        /** After the time or source of a record whose file has a timestamp shift: the shift, e.g. ` +2h`. */
        private var shiftMark = ""

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val field = logModel.fieldAt(column)
            val shift = when (field?.role) {
                Role.TIMESTAMP, Role.SOURCE -> TimestampShift.format(logModel.timestampShift(row))
                else -> ""
            }
            shiftMark = when {
                shift.isEmpty() -> ""
                field?.role == Role.SOURCE -> " ($shift)"
                else -> " $shift"
            }
            toolTipText = if (shift.isEmpty()) null else "This file's times are shifted by $shift"
            ranges = if (field == null || value !is String || isSelected) {
                IntArray(0)
            } else {
                matches.ranges(field.name, value)
            }
            foundRanges = if (field == null || value !is String) IntArray(0) else found.ranges(field.name, value)
            val moreLines = if (column == expandingColumn()) logModel.moreLines(row) else 0
            more = when (moreLines) {
                0 -> ""
                1 -> " … +1 line"
                else -> " … +$moreLines lines"
            }
            // The text is cut before the hints rather than under them.
            val hints = shiftMark + more
            if (hints.isNotEmpty()) border = CompoundBorder(border, EmptyBorder(0, 0, 0, hintWidth(hints)))
            return this
        }

        private fun hintWidth(hint: String) = getFontMetrics(font).stringWidth(hint)

        // A click on the "… +N lines" hint toggles too: it is a bigger target than the icon.
        override fun togglesAt(x: Int, y: Int): Boolean {
            if (super.togglesAt(x, y)) return true
            if (more.isEmpty()) return false
            val textBounds = Rectangle()
            layOut(this, text, textBounds)
            val moreX = textBounds.x + textBounds.width + hintWidth(shiftMark)
            return Rectangle(moreX, 0, hintWidth(more), rowHeight).contains(x, y)
        }

        override fun paintComponent(g: Graphics) {
            val marked = ranges.isNotEmpty() || foundRanges.isNotEmpty()
            val hints = shiftMark + more
            if (!marked && hints.isEmpty()) return super.paintComponent(g)
            var cleared = false
            // Marks stop where a cut value's "..." begins.
            val textBounds = Rectangle()
            val visible = layOut(this, text, textBounds)
            if (marked) {
                // An opaque cell, e.g. a selected one, would paint its background over the marks: paint it first.
                if (isOpaque) {
                    g.color = background
                    g.fillRect(0, 0, width, height)
                    isOpaque = false
                    cleared = true
                }
                g.font = font
                mark(g, text, 0, visible, ranges, textBounds.x, textBounds.y - 1, MARK_COLOR)
                mark(g, text, 0, visible, foundRanges, textBounds.x, textBounds.y - 1, FOUND_COLOR)
            }
            super.paintComponent(g)
            if (cleared) isOpaque = true
            if (hints.isNotEmpty()) {
                g.font = font
                val baseline = textBounds.y + getFontMetrics(font).ascent
                drawHint(g, hints, textBounds.x + textBounds.width, baseline)
            }
        }
    }

    private inner class LevelRenderer : MarkRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) foreground = levelColor(logModel.level(row), table.foreground)
            return this
        }
    }

    /** All lines of an expanded row's record, wrapped to the column; paints only the lines in view. */
    private inner class ExpandedRenderer : CellRenderer() {
        private var wrapped: WrappedText? = null
        private var ranges = IntArray(0)
        private var foundRanges = IntArray(0)

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            // The lines are painted here, not by the label.
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
            val field = logModel.fieldAt(column)!!.name
            val text = expandedText(row, column)
            wrapped = wrapped(row, column)
            ranges = if (isSelected) IntArray(0) else text.matches(matches, field)
            foundRanges = text.found(found, field)
            return this
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val wrapped = wrapped ?: return
            val text = wrapped.text
            val metrics = getFontMetrics(font)
            val lineHeight = metrics.height
            val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
            val top = insets.top
            val left = insets.left
            val first = ((clip.y - top) / lineHeight).coerceAtLeast(0)
            val last = minOf(wrapped.lineCount - 1, (clip.y + clip.height - top) / lineHeight)
            g.font = font
            for (line in first..last) {
                val y = top + line * lineHeight
                val from = wrapped.start(line)
                val to = wrapped.end(line)
                mark(g, text, from, to, ranges, left, y - 1, MARK_COLOR)
                mark(g, text, from, to, foundRanges, left, y - 1, FOUND_COLOR)
                g.color = foreground
                val shown = text.substring(from, to)
                val baseline = y + metrics.ascent
                BasicGraphicsUtils.drawString(this, g as Graphics2D, shown, left.toFloat(), baseline.toFloat())
            }
        }
    }
}
