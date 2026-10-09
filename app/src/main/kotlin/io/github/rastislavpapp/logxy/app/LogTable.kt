package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import java.awt.Component
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

private const val CELL_PADDING = 12

/** Table of log records; the last column always fills the viewport and long values are cut. */
class LogTable(private val logModel: LogTableModel) : JTable(logModel) {

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

    private fun updateLook() {
        val base = UIManager.getFont("monospaced.font") ?: Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Table.font").size)
        // A plain Font, not a FontUIResource, so a theme switch does not replace it.
        font = Font(base.name, base.style, base.size)
        rowHeight = getFontMetrics(font).height + 4
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
                else -> null
            }
            if (column == columnModel.columnCount - 1) continue
            val chars = when {
                field == null -> maxOf(logModel.totalRowCount.toString().length, 3)
                field.type == ColumnType.TIMESTAMP -> sampleLength(column, 23)
                field.role == Role.LEVEL -> 5
                field.role == Role.THREAD -> 18
                field.role == Role.LOGGER -> 36
                else -> 12
            }
            val width = chars * charWidth + CELL_PADDING
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

    /** Timestamps all have the layout's width; measure the first one. */
    private fun sampleLength(column: Int, fallback: Int): Int =
        (0..<minOf(rowCount, 100)).map { (getValueAt(it, column) as String).length }.firstOrNull { it > 0 } ?: fallback

    private inner class LevelRenderer : DefaultTableCellRenderer() {
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
