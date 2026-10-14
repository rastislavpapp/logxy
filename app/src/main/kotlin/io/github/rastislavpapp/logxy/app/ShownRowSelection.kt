package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.session.Selection
import javax.swing.ListSelectionModel
import javax.swing.event.EventListenerList
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

/**
 * The selection model of a table that holds a [window] of the shown rows. The [selection] is kept in rows of all
 * shown rows, so it reaches beyond the window and stays as the window moves; the indices JTable passes and gets
 * here are rows of the window. Anchor and lead outside the window read as -1: JTable takes an anchor of -1 as
 * none, so extending a selection goes through [extendTo] rather than through these indices.
 */
class ShownRowSelection(private val window: RowWindow) : ListSelectionModel {

    val selection = Selection()

    private val listeners = EventListenerList()
    private var adjusting = false
    private var changedWhileAdjusting = false

    private val start get() = window.windowStart

    /** The row after the window's last. */
    private val end get() = start + minOf(window.totalRowCount - start, WINDOW_ROWS)

    private fun row(index: Int) = if (index < 0) -1 else start + index

    private fun index(row: Int) = if (row in start..<end) row - start else -1

    /** Selects from the anchor to [index], wherever the anchor is: in the window or not. */
    fun extendTo(index: Int) = change { selection.extendTo(row(index)) }

    /** Selects all shown rows there are now. */
    fun selectAll() = change { selection.selectAll(window.totalRowCount) }

    override fun setSelectionInterval(index0: Int, index1: Int) {
        if (index0 >= 0 && index1 >= 0) change { selection.select(row(index0), row(index1)) }
    }

    override fun addSelectionInterval(index0: Int, index1: Int) {
        if (index0 >= 0 && index1 >= 0) change { selection.add(row(index0), row(index1)) }
    }

    override fun removeSelectionInterval(index0: Int, index1: Int) {
        if (index0 >= 0 && index1 >= 0) change { selection.remove(row(index0), row(index1)) }
    }

    override fun getMinSelectionIndex() = index(selection.rows.firstIn(start, end))

    override fun getMaxSelectionIndex() = index(selection.rows.lastIn(start, end))

    override fun isSelectedIndex(index: Int) = index >= 0 && row(index) in selection

    override fun getAnchorSelectionIndex() = index(selection.anchor)

    override fun setAnchorSelectionIndex(index: Int) = change { selection.moveTo(row(index), selection.lead) }

    override fun getLeadSelectionIndex() = index(selection.lead)

    override fun setLeadSelectionIndex(index: Int) = change { selection.setLead(row(index)) }

    override fun clearSelection() = change { selection.clear() }

    override fun isSelectionEmpty() = selection.isEmpty()

    override fun insertIndexInterval(index: Int, length: Int, before: Boolean) =
        change { selection.insert(row(if (before) index else index + 1), length) }

    override fun removeIndexInterval(index0: Int, index1: Int) =
        change { selection.delete(row(minOf(index0, index1)), row(maxOf(index0, index1)) + 1) }

    override fun setValueIsAdjusting(valueIsAdjusting: Boolean) {
        adjusting = valueIsAdjusting
        if (!adjusting && changedWhileAdjusting) fire()
    }

    override fun getValueIsAdjusting() = adjusting

    // Always several intervals: the table sets it once, and nothing else is supported.
    override fun setSelectionMode(selectionMode: Int) = Unit

    override fun getSelectionMode() = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

    override fun addListSelectionListener(listener: ListSelectionListener) =
        listeners.add(ListSelectionListener::class.java, listener)

    override fun removeListSelectionListener(listener: ListSelectionListener) =
        listeners.remove(ListSelectionListener::class.java, listener)

    fun getListSelectionListeners(): Array<ListSelectionListener> =
        listeners.getListeners(ListSelectionListener::class.java)

    private inline fun change(edit: () -> Unit) {
        edit()
        fire()
    }

    // The whole window is reported as changed: the table repaints only what is on screen of it.
    private fun fire() {
        changedWhileAdjusting = adjusting
        val event = ListSelectionEvent(this, 0, maxOf(0, end - start - 1), adjusting)
        for (listener in getListSelectionListeners().reversed()) listener.valueChanged(event)
    }
}
