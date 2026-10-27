package io.github.rastislavpapp.logxy.ui

import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Space on each side of the bookmark icon, in unscaled pixels. */
private const val STRIP_PADDING = 2

/**
 * A narrow strip beside [table], as its scroll pane's row header, with a bookmark icon on each row of the table
 * that [isBookmarked] says has one, by the row of the table. A click calls [onToggle] with the row clicked. It leaves
 * the rows' own backgrounds alone, so row highlights and the selection read as before.
 */
class BookmarkStrip(
    private val table: JTable,
    private val isBookmarked: (row: Int) -> Boolean,
    private val onToggle: (row: Int) -> Unit,
) : JComponent() {

    init {
        // The strip is as high as the table, which grows and shrinks with the rows in it.
        table.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = revalidate()
        })
        // Other records come into the rows, e.g. when the table's window of rows moves, without a resize.
        table.model.addTableModelListener { repaint() }
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val row = table.rowAtPoint(Point(0, e.y))
                if (row >= 0) onToggle(row)
            }
        })
    }

    private val icon get() = Icons.bookmark

    override fun getPreferredSize() = Dimension(icon.iconWidth + 2 * look.scale(STRIP_PADDING), table.height)

    override fun paintComponent(g: Graphics) {
        g.color = UIManager.getColor("Table.background")
        val clip = g.clipBounds
        g.fillRect(clip.x, clip.y, clip.width, clip.height)
        if (table.rowCount == 0) return
        // Past the last row, rowAtPoint gives -1.
        val atTop = table.rowAtPoint(Point(0, clip.y))
        val atBottom = table.rowAtPoint(Point(0, clip.y + clip.height))
        val first = if (atTop >= 0) atTop else 0
        val last = if (atBottom >= 0) atBottom else table.rowCount - 1
        val icon = icon
        // In the row's first line, which an expanded row keeps at its top.
        val lineHeight = table.rowHeight
        for (row in first..last) {
            if (!isBookmarked(row)) continue
            val top = table.getCellRect(row, 0, true).y
            icon.paintIcon(this, g, (width - icon.iconWidth) / 2, top + (lineHeight - icon.iconHeight) / 2)
        }
    }
}
