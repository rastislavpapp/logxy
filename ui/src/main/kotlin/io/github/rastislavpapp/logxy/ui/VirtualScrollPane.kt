package io.github.rastislavpapp.logxy.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.border.Border

/** Rows the table holds at once; small enough that rows × row height fits Swing's int pixel coordinates. */
const val WINDOW_ROWS = 1_000_000

/** When the view gets this close to either end of the window, the window is moved. */
private const val EDGE_ROWS = 50_000

private val UPWARD_KEYS = setOf(KeyEvent.VK_UP, KeyEvent.VK_KP_UP, KeyEvent.VK_PAGE_UP, KeyEvent.VK_HOME)
private val DOWNWARD_KEYS = setOf(KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN, KeyEvent.VK_PAGE_DOWN)

/**
 * An empty border for a scroll pane around a table. Not null: a table replaces a null border with the look's own, an
 * outline.
 */
internal val NO_OUTLINE: Border = BorderFactory.createEmptyBorder()

/** A table model that shows [WINDOW_ROWS] rows of a larger list, starting at [windowStart]. */
interface RowWindow {
    val totalRowCount: Int

    /** Setting it must notify the table that all rows changed. */
    var windowStart: Int
}

/**
 * Scroll pane for tables with more rows than `JTable` can lay out (its height is an int number of
 * pixels). The table holds a window of rows, and a separate scroll bar counts rows of the whole list. Rows may differ
 * in height: where they lie is the table's to tell, by `getCellRect` and `rowAtPoint`.
 */
class VirtualScrollPane(private val table: JTable, private val window: RowWindow) : JPanel(BorderLayout()) {

    private val scrollPane = JScrollPane(
        table, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        border = NO_OUTLINE
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

    /** Called whenever the rows in view may have changed. */
    var onRowsInViewChanged: () -> Unit = {}

    /** Whether the user scrolling to the end turns [follow] back on. */
    var resumeFollowAtEnd: () -> Boolean = { false }

    private val viewY get() = viewport.viewPosition.y

    private val viewHeight get() = viewport.extentSize.height

    /** Rows of the table at least partly in view; at least 1. */
    private val visibleRows get() = maxOf(1, localRowAt(viewY + viewHeight - 1) - localRowAt(viewY) + 1)

    private val topRow get() = window.windowStart + maxOf(0, localRowAt(viewY))

    private val atEnd
        get() = window.windowStart + table.rowCount >= window.totalRowCount && viewY + viewHeight >= table.height

    /** The row of the table at [y]: the first or the last one when [y] is above or below them; -1 if none. */
    private fun localRowAt(y: Int): Int {
        if (table.rowCount == 0) return -1
        val row = table.rowAtPoint(Point(0, y))
        return when {
            row >= 0 -> row
            y < 0 -> 0
            else -> table.rowCount - 1
        }
    }

    /** The y of the table's [row]. */
    private fun rowTop(row: Int) = table.getCellRect(row, 0, true).y

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
                // The bar's end shows the end of the last row, which may be taller than the view.
                if (it.value + scrollBar.visibleAmount >= scrollBar.maximum) scrollToEnd() else scrollToRow(it.value)
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

    /**
     * Shows [header] beside the table's rows, scrolling with them, e.g. a [BookmarkStrip]; above it, beside the
     * table's header, the header's background.
     */
    fun setRowHeader(header: JComponent) {
        scrollPane.setRowHeaderView(header)
        val corner = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                g.color = table.tableHeader.background
                g.fillRect(0, 0, width, height)
            }
        }
        scrollPane.setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, corner)
    }

    /** Scrolls to the bottom of the last row, also of one taller than the view. */
    fun scrollToEnd() {
        scrollToRow(window.totalRowCount)
        setViewY(table.height)
    }

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

    /**
     * Index of the topmost visible row in the whole list. Never past its last row: when the list is replaced,
     * the table tells its listeners before [rowsReplaced] moves the view, which may still be further down.
     */
    val firstVisibleRow get() = minOf(topRow, maxOf(0, window.totalRowCount - 1))

    /** Index of the lowest visible row in the whole list; below [firstVisibleRow] if there are no rows. */
    val lastVisibleRow get() = minOf(topRow + visibleRows, window.totalRowCount) - 1

    /**
     * Selects [row] of the whole list; with [scroll], first scrolls it into the middle of the view, or its top to the
     * top of the view if it is taller.
     */
    fun selectRow(row: Int, scroll: Boolean = true) {
        if (row < 0 || row >= window.totalRowCount) return
        if (scroll) {
            scrollToRow(row)
            val cell = table.getCellRect(row - window.windowStart, 0, true)
            setViewY(cell.y - maxOf(0, (viewHeight - cell.height) / 2))
        }
        val local = row - window.windowStart
        if (local in 0..<table.rowCount) table.selectionModel.setSelectionInterval(local, local)
    }

    /** Scrolls the part of [row] of the whole list from [y] to [y] + [height], e.g. one line, into view if needed. */
    fun showInRow(row: Int, y: Int, height: Int) {
        scrollPane.validate()
        val local = row - window.windowStart
        if (local !in 0..<table.rowCount) return
        val top = rowTop(local) + y
        if (top < viewY || top + height > viewY + viewHeight) setViewY(top - (viewHeight - height) / 2)
    }

    /** Call after rows were appended to the list. */
    fun rowsAppended() {
        if (follow) scrollToEnd() else updateScrollBar()
    }

    /** Scrolls so that [row] (of the whole list) is the top visible row, as far as possible. */
    fun scrollToRow(row: Int) {
        // The table may just have grown; its height must be current before positioning the view.
        scrollPane.validate()
        val top = row.coerceIn(0, maxOf(0, window.totalRowCount - 1))
        // Rows of rowHeight would fill the view; taller ones fill it with fewer.
        val rowsInView = maxOf(1, viewHeight / table.rowHeight)
        if (top < window.windowStart || top + rowsInView > window.windowStart + table.rowCount) {
            moveWindow(top - WINDOW_ROWS / 2)
        }
        val local = top - window.windowStart
        if (local in 0..<table.rowCount) setViewY(rowTop(local))
    }

    private fun onViewChanged() {
        updateScrollBar()
        onRowsInViewChanged()
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
        if (newStart == window.windowStart) return
        // The row at the top of the view, and how far into it the view starts, stay.
        val localTop = maxOf(0, localRowAt(viewY))
        val offset = if (table.rowCount > 0) viewY - rowTop(localTop) else 0
        val top = window.windowStart + localTop
        window.windowStart = newStart
        // Rows of other heights may have come into the window.
        scrollPane.validate()
        val local = (top - newStart).coerceIn(0, maxOf(0, table.rowCount - 1))
        setViewY(if (table.rowCount > 0) rowTop(local) + offset else 0)
    }

    private fun setViewY(y: Int) {
        val maxY = maxOf(0, table.height - viewport.extentSize.height)
        viewport.viewPosition = Point(0, y.coerceIn(0, maxY))
    }

    // Scrolls by pixels: each row's worth of wheel turn moves the view by rowHeight, so the wheel also scrolls
    // through a row taller than the view.
    private fun onWheel(e: MouseWheelEvent) {
        val perNotch = if (e.scrollType == MouseWheelEvent.WHEEL_UNIT_SCROLL) e.scrollAmount else visibleRows
        val rows = e.preciseWheelRotation * perNotch + wheelRemainder
        val whole = rows.toInt()
        wheelRemainder = rows - whole
        if (rows < 0) follow = false
        if (whole != 0) {
            scrollPane.validate()
            setViewY(viewY + whole * table.rowHeight)
        }
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
