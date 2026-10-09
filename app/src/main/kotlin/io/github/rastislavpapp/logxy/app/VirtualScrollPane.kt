package io.github.rastislavpapp.logxy.app

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListSelectionModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/** Rows the table holds at once; small enough that rows × row height fits Swing's int pixel coordinates. */
const val WINDOW_ROWS = 1_000_000

/** When the view gets this close to either end of the window, the window is moved. */
private const val EDGE_ROWS = 50_000

private val UPWARD_KEYS = setOf(KeyEvent.VK_UP, KeyEvent.VK_KP_UP, KeyEvent.VK_PAGE_UP, KeyEvent.VK_HOME)
private val DOWNWARD_KEYS = setOf(KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN, KeyEvent.VK_PAGE_DOWN)

/** A table model that shows [WINDOW_ROWS] rows of a larger list, starting at [windowStart]. */
interface RowWindow {
    val totalRowCount: Int

    /** Setting it must notify the table that all rows changed. */
    var windowStart: Int
}

/**
 * Scroll pane for tables with more rows than `JTable` can lay out (its height is an int number of
 * pixels). The table holds a window of rows, and a separate scroll bar counts rows of the whole list.
 */
class VirtualScrollPane(private val table: JTable, private val window: RowWindow) : JPanel(BorderLayout()) {

    private val scrollPane = JScrollPane(
        table, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        border = null
        isWheelScrollingEnabled = false
    }
    private val viewport get() = scrollPane.viewport
    private val scrollBar = JScrollBar(JScrollBar.VERTICAL)
    private var updatingScrollBar = false
    private var moveScheduled = false
    private var wheelRemainder = 0.0

    /** Keeps the end of the list in view as rows are appended; user scrolling upwards turns it off. */
    var follow = false
        set(value) {
            if (field == value) return
            field = value
            onFollowChanged(value)
            if (value) scrollToEnd()
        }

    var onFollowChanged: (Boolean) -> Unit = {}

    /** Whether the user scrolling to the end turns [follow] back on. */
    var resumeFollowAtEnd: () -> Boolean = { false }

    private val visibleRows get() = maxOf(1, viewport.extentSize.height / table.rowHeight)

    private val topRow get() = window.windowStart + viewport.viewPosition.y / table.rowHeight

    private val atEnd get() = topRow + visibleRows >= window.totalRowCount

    init {
        val headerSpacer = object : JComponent() {
            override fun getPreferredSize() = Dimension(0, table.tableHeader.preferredSize.height)
        }
        add(scrollPane, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(headerSpacer, BorderLayout.NORTH)
            add(scrollBar, BorderLayout.CENTER)
        }, BorderLayout.EAST)

        viewport.addChangeListener { onViewChanged() }
        scrollBar.addAdjustmentListener {
            if (!updatingScrollBar) {
                if (it.value + visibleRows < window.totalRowCount) follow = false
                scrollToRow(it.value)
                resumeFollowIfAtEnd()
            }
        }
        scrollPane.addMouseWheelListener(::onWheel)
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode in UPWARD_KEYS) follow = false
                // Listeners run before the key's action has moved the view.
                if (e.keyCode in DOWNWARD_KEYS) SwingUtilities.invokeLater(::resumeFollowIfAtEnd)
            }
        })
        installJumpActions()
    }

    fun scrollToEnd() = scrollToRow(window.totalRowCount)

    private fun resumeFollowIfAtEnd() {
        if (!follow && atEnd && resumeFollowAtEnd()) follow = true
    }

    /** Call after the list was replaced, e.g. by a new filter result; shows the top. */
    fun rowsReplaced() {
        scrollPane.validate()
        setViewY(0)
        updateScrollBar()
        if (follow) scrollToEnd()
    }

    /** Index of the topmost visible row in the whole list. */
    val firstVisibleRow get() = topRow

    /** Selects [row] of the whole list; with [scroll], first scrolls it into the middle of the view. */
    fun selectRow(row: Int, scroll: Boolean = true) {
        if (row < 0 || row >= window.totalRowCount) return
        if (scroll) scrollToRow(row - visibleRows / 2)
        val local = row - window.windowStart
        if (local in 0..<table.rowCount) table.selectionModel.setSelectionInterval(local, local)
    }

    /** Call after rows were appended to the list. */
    fun rowsAppended() {
        if (follow) scrollToEnd() else updateScrollBar()
    }

    /** Scrolls so that [row] (of the whole list) is the top visible row, as far as possible. */
    fun scrollToRow(row: Int) {
        // The table may just have grown; its height must be current before positioning the view.
        scrollPane.validate()
        val top = row.coerceIn(0, maxOf(0, window.totalRowCount - visibleRows))
        if (top < window.windowStart || top + visibleRows > window.windowStart + table.rowCount) {
            moveWindow(top - WINDOW_ROWS / 2)
        }
        setViewY((top - window.windowStart) * table.rowHeight)
    }

    private fun onViewChanged() {
        updateScrollBar()
        val localTop = topRow - window.windowStart
        val nearStart = localTop < EDGE_ROWS && window.windowStart > 0
        val nearEnd = localTop + visibleRows > table.rowCount - EDGE_ROWS &&
            window.windowStart + table.rowCount < window.totalRowCount
        // Deferred: this may run in the middle of a table action that is still updating the selection.
        if ((nearStart || nearEnd) && !moveScheduled) {
            moveScheduled = true
            SwingUtilities.invokeLater {
                moveScheduled = false
                moveWindow(topRow - WINDOW_ROWS / 2)
            }
        }
    }

    private fun updateScrollBar() {
        updatingScrollBar = true
        try {
            scrollBar.setValues(topRow, visibleRows, 0, window.totalRowCount)
            scrollBar.unitIncrement = 1
            scrollBar.blockIncrement = visibleRows
        } finally {
            updatingScrollBar = false
        }
    }

    /** Moves the window to start near [start] without changing what is on screen. */
    private fun moveWindow(start: Int) {
        val newStart = start.coerceIn(0, maxOf(0, window.totalRowCount - table.rowCount))
        val delta = newStart - window.windowStart
        if (delta == 0) return
        val viewY = viewport.viewPosition.y - delta * table.rowHeight
        shiftSelection(delta)
        window.windowStart = newStart
        setViewY(viewY)
    }

    // Keeps the same rows selected after the window moves by `delta` rows; rows leaving the window are deselected.
    private fun shiftSelection(delta: Int) {
        val selection = table.selectionModel as DefaultListSelectionModel
        val rows = table.rowCount
        fun shifted(index: Int) = if (index < 0) -1 else (index - delta).coerceIn(0, rows - 1)
        val anchor = shifted(selection.anchorSelectionIndex)
        val lead = shifted(selection.leadSelectionIndex)
        val selected = selection.selectedIndices
        selection.valueIsAdjusting = true
        selection.clearSelection()
        var i = 0
        while (i < selected.size) {
            // Re-add contiguous runs as intervals.
            var j = i
            while (j + 1 < selected.size && selected[j + 1] == selected[j] + 1) j++
            val from = maxOf(0, selected[i] - delta)
            val to = minOf(rows - 1, selected[j] - delta)
            if (from <= to) selection.addSelectionInterval(from, to)
            i = j + 1
        }
        selection.anchorSelectionIndex = anchor
        selection.moveLeadSelectionIndex(lead)
        selection.valueIsAdjusting = false
    }

    private fun setViewY(y: Int) {
        val maxY = maxOf(0, table.height - viewport.extentSize.height)
        viewport.viewPosition = Point(0, y.coerceIn(0, maxY))
    }

    private fun onWheel(e: MouseWheelEvent) {
        val perNotch = if (e.scrollType == MouseWheelEvent.WHEEL_UNIT_SCROLL) e.scrollAmount else visibleRows
        val rows = e.preciseWheelRotation * perNotch + wheelRemainder
        val whole = rows.toInt()
        wheelRemainder = rows - whole
        if (rows < 0) follow = false
        if (whole != 0) scrollToRow(topRow + whole)
        if (rows > 0) resumeFollowIfAtEnd()
        e.consume()
    }

    // JTable's own jumps only reach the ends of the window. There are no columns to navigate, so Home/End jump too.
    private fun installJumpActions() {
        fun bind(keys: String, name: String, toEnd: Boolean, extend: Boolean) {
            table.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    if (window.totalRowCount == 0) return
                    val row = if (toEnd) window.totalRowCount - 1 else 0
                    scrollToRow(row)
                    table.changeSelection(row - window.windowStart, 0, false, extend)
                    if (toEnd) resumeFollowIfAtEnd()
                }
            })
            val inputMap = table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            keys.split(",").forEach { inputMap.put(KeyStroke.getKeyStroke(it.trim()), name) }
        }
        bind("HOME, ctrl HOME", "logxyFirstRow", toEnd = false, extend = false)
        bind("END, ctrl END", "logxyLastRow", toEnd = true, extend = false)
        bind("shift HOME, shift ctrl HOME", "logxyFirstRowExtend", toEnd = false, extend = true)
        bind("shift END, shift ctrl END", "logxyLastRowExtend", toEnd = true, extend = true)
    }
}
