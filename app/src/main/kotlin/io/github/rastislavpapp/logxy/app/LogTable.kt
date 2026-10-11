package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.query.Highlighter
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

private const val CELL_PADDING = 12

/** What Swing ends a label's text with when it does not fit. */
private const val CLIP = "..."

/** The last column (usually the message) keeps this many characters; when columns do not fit, the others shrink first. */
private const val MIN_LAST_COLUMN_CHARS = 40

/** Table of log records; the last column always fills the viewport and long values are cut. */
class LogTable(private val logModel: LogTableModel) : JTable(logModel) {

    /** Column widths by name, kept when columns are hidden or shown; not of the last column, which fills the rest. */
    private val widths = HashMap<Any, Int>()

    /** Marks what the filter matched in the cells. */
    var matches: Highlighter = Highlighter.NONE
        set(value) {
            field = value
            repaint()
        }

    init {
        autoResizeMode = AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        fillsViewportHeight = true
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        tableHeader.reorderingAllowed = false
        (tableHeader.defaultRenderer as? JLabel)?.horizontalAlignment = SwingConstants.LEADING
        updateLook()
    }

    // JTable spreads spare width over all columns; give it all to the last one, unless the user is
    // dragging a column border (then JTable keeps the dragged widths as the new preferred ones).
    override fun doLayout() {
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

    private fun updateLook() {
        val base = UIManager.getFont("monospaced.font") ?: Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Table.font").size)
        // A plain Font, not a FontUIResource, so a theme switch does not replace it.
        font = Font(base.name, base.style, base.size)
        rowHeight = getFontMetrics(font).height + 4
        // Widths are measured in the new font.
        widths.clear()
        setUpColumns()
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
            val margins = UIManager.getInsets("Table.cellMargins")
            val width = chars * charWidth + CELL_PADDING + (margins?.let { it.left + it.right } ?: 0)
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

    /** Paints the matches behind the text of left-aligned cells. */
    private open inner class HighlightRenderer : DefaultTableCellRenderer() {
        private var ranges = IntArray(0)

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val field = logModel.fieldAt(column)
            ranges = if (field == null || value !is String || isSelected) IntArray(0) else matches.ranges(field.name, value)
            return this
        }

        override fun paintComponent(g: Graphics) {
            if (ranges.isNotEmpty()) {
                val metrics = getFontMetrics(font)
                val insets = insets
                val view = Rectangle(insets.left, insets.top, width - insets.left - insets.right, height - insets.top - insets.bottom)
                val textBounds = Rectangle()
                // Laid out as the label paints it, so marks stop where a cut value's "..." begins.
                val shown = SwingUtilities.layoutCompoundLabel(
                    this, metrics, text, null, verticalAlignment, horizontalAlignment,
                    verticalTextPosition, horizontalTextPosition, view, Rectangle(), textBounds, 0,
                )
                val visible = if (shown == text) text.length else (shown.length - CLIP.length).coerceAtLeast(0)
                g.color = HIGHLIGHT_COLOR
                for (i in ranges.indices step 2) {
                    val start = ranges[i]
                    val end = minOf(ranges[i + 1], visible)
                    if (start >= end) break
                    val x = textBounds.x + metrics.stringWidth(text.substring(0, start))
                    g.fillRect(x, 1, metrics.stringWidth(text.substring(start, end)), height - 2)
                }
            }
            super.paintComponent(g)
        }
    }

    private inner class LevelRenderer : HighlightRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) {
                foreground = when (logModel.level(row)) {
                    Level.ERROR, Level.FATAL -> UIManager.getColor("Actions.Red")
                    Level.WARN -> UIManager.getColor("Actions.Yellow")
                    Level.TRACE, Level.DEBUG -> UIManager.getColor("Label.disabledForeground")
                    else -> table.foreground
                }
            }
            return this
        }
    }
}
