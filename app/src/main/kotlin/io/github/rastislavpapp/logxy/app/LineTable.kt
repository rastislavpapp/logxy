package io.github.rastislavpapp.logxy.app

import java.awt.Font
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

private const val CELL_PADDING = 12

/** Table of line numbers and line text; the line column always fits the viewport and cuts long lines. */
class LineTable(model: LineTableModel) : JTable(model) {

    init {
        autoResizeMode = AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        fillsViewportHeight = true
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        tableHeader.reorderingAllowed = false
        (tableHeader.defaultRenderer as? JLabel)?.horizontalAlignment = SwingConstants.LEADING
        updateFont()
    }

    override fun updateUI() {
        super.updateUI()
        // Also called from JTable's constructor, before the columns exist.
        if (columnModel?.columnCount == 2) updateFont()
    }

    private fun updateFont() {
        val base = UIManager.getFont("monospaced.font") ?: Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Table.font").size)
        // A plain Font, not a FontUIResource, so a theme switch does not replace it.
        font = Font(base.name, base.style, base.size)
        rowHeight = getFontMetrics(font).height + 4

        val digits = maxOf((model as LineTableModel).totalRowCount.toString().length, 3)
        val numberWidth = digits * getFontMetrics(font).charWidth('0') + CELL_PADDING
        columnModel.getColumn(0).apply {
            minWidth = numberWidth
            maxWidth = numberWidth
            preferredWidth = numberWidth
            cellRenderer = DefaultTableCellRenderer().apply {
                horizontalAlignment = SwingConstants.RIGHT
                foreground = UIManager.getColor("Label.disabledForeground")
            }
        }
    }
}
