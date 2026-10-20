package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.query.Highlighter
import io.github.rastislavpapp.logxy.core.session.Selection
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer

private const val CELL_PADDING = 12

/** What Swing ends a label's text with when it does not fit. */
private const val CLIP = "..."

/**
 * The last column (usually the message) keeps this many characters; when columns do not fit, the others shrink
 * first.
 */
private const val MIN_LAST_COLUMN_CHARS = 40

/** The text color of [level]: red for errors, yellow for warnings, dim for debug output, else [default]. */
fun levelColor(level: Level?, default: Color): Color = when (level) {
    Level.ERROR, Level.FATAL -> look.error
    Level.WARN -> look.warning
    Level.TRACE, Level.DEBUG -> UIManager.getColor("Label.disabledForeground")
    else -> default
}

/** Table of log records; the last column always fills the viewport and long values are cut. */
class LogTable(private val logModel: LogTableModel) : JTable(logModel) {

    private val rowSelection = WindowSelectionModel(logModel)

    /** Column widths by name, kept when columns are hidden or shown; not of the last column, which fills the rest. */
    private val widths = HashMap<Any, Int>()

    /** The columns of [widths] whose width the user dragged; the others' follow the records shown. */
    private val resized = HashSet<Any>()

    /**
     * The widths in pixels the user dragged columns to, by field name, also of hidden columns; set ones count as
     * dragged.
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
    var matches: Highlighter = Highlighter.NONE
        set(value) {
            field = value
            repaint()
        }

    /** Marks what the find bar found in the cells, also of selected rows. */
    var found: Highlighter = Highlighter.NONE
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
    }

    /** The selected rows of the shown log, not only of those the table holds. */
    val selection: Selection get() = rowSelection.selection

    // Selects all rows of the shown log, not only those the table holds.
    override fun selectAll() = rowSelection.selectAll()

    // Extending goes from the anchor wherever it is; outside the rows the table holds, JTable would not see it.
    override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
        if (!extend) return super.changeSelection(rowIndex, columnIndex, toggle, extend)
        if (toggle) rowSelection.extendKeepingOthers(rowIndex) else rowSelection.extendTo(rowIndex)
        columnModel.selectionModel.setSelectionInterval(columnIndex, columnIndex)
        if (autoscrolls) scrollRectToVisible(getCellRect(rowIndex, columnIndex, false))
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
        if (columnsChanged) rowSelection.keeping { super.tableChanged(e) } else super.tableChanged(e)
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
        }
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
        // Widths are measured in the new font; only the dragged ones stay.
        widths.keys.retainAll(resized)
        setUpColumns()
        restoreWidths()
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
                field == null -> DefaultTableCellRenderer().apply {
                    horizontalAlignment = SwingConstants.RIGHT
                    foreground = UIManager.getColor("Label.disabledForeground")
                }
                field.role == Role.LEVEL -> LevelRenderer()
                else -> HighlightRenderer()
            }
            if (column == columnModel.columnCount - 1) {
                tableColumn.minWidth = MIN_LAST_COLUMN_CHARS * charWidth
                continue
            }
            val chars = when {
                field == null -> maxOf(logModel.totalRowCount.toString().length, 3)
                field.type == ColumnType.TIMESTAMP -> sampleLength(column, 23)
                field.role == Role.LEVEL -> 5
                field.role == Role.SOURCE -> sampleWidest(column).coerceIn(6, 32)
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

    /** The longest of the first values, e.g. file names. */
    private fun sampleWidest(column: Int): Int =
        (0..<minOf(rowCount, 1000)).maxOfOrNull { (getValueAt(it, column) as String).length } ?: 0

    /** Timestamps all have the layout's width; measure the first one. */
    private fun sampleLength(column: Int, fallback: Int): Int =
        (0..<minOf(rowCount, 100)).map { (getValueAt(it, column) as String).length }.firstOrNull { it > 0 } ?: fallback

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
     * Lays out [text] in [label] as the label paints it, putting where it goes into [textBounds]; the number of its
     * characters shown, before a cut value's "...".
     */
    private fun layOut(label: JLabel, text: String, textBounds: Rectangle): Int {
        val insets = label.insets
        val view = Rectangle(
            insets.left,
            insets.top,
            label.width - insets.left - insets.right,
            label.height - insets.top - insets.bottom,
        )
        val shown = SwingUtilities.layoutCompoundLabel(
            label, label.getFontMetrics(label.font), text, null, label.verticalAlignment, label.horizontalAlignment,
            label.verticalTextPosition, label.horizontalTextPosition, view, Rectangle(), textBounds, 0,
        )
        return if (shown == text) text.length else (shown.length - CLIP.length).coerceAtLeast(0)
    }

    /** Paints the matches behind the text of left-aligned cells. */
    private open inner class HighlightRenderer : DefaultTableCellRenderer() {
        private var ranges = IntArray(0)
        private var foundRanges = IntArray(0)

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val field = logModel.fieldAt(column)
            ranges = if (field == null || value !is String || isSelected) {
                IntArray(0)
            } else {
                matches.ranges(field.name, value)
            }
            foundRanges = if (field == null || value !is String) IntArray(0) else found.ranges(field.name, value)
            return this
        }

        override fun paintComponent(g: Graphics) {
            var cleared = false
            if (ranges.isNotEmpty() || foundRanges.isNotEmpty()) {
                // An opaque cell, e.g. a selected one, would paint its background over the marks: paint it first.
                if (isOpaque) {
                    g.color = background
                    g.fillRect(0, 0, width, height)
                    isOpaque = false
                    cleared = true
                }
                val metrics = getFontMetrics(font)
                val textBounds = Rectangle()
                // Marks stop where a cut value's "..." begins.
                val visible = layOut(this, text, textBounds)
                fun mark(ranges: IntArray, color: Color) {
                    g.color = color
                    for (i in ranges.indices step 2) {
                        val start = ranges[i]
                        val end = minOf(ranges[i + 1], visible)
                        if (start >= end) break
                        val x = textBounds.x + metrics.stringWidth(text.substring(0, start))
                        g.fillRect(x, 1, metrics.stringWidth(text.substring(start, end)), height - 2)
                    }
                }
                mark(ranges, HIGHLIGHT_COLOR)
                mark(foundRanges, FOUND_COLOR)
            }
            super.paintComponent(g)
            if (cleared) isOpaque = true
        }
    }

    private inner class LevelRenderer : HighlightRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) foreground = levelColor(logModel.level(row), table.foreground)
            return this
        }
    }
}
