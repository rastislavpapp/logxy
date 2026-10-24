package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.session.Selection
import io.github.rastislavpapp.logxy.core.session.TableWindow
import javax.swing.ListSelectionModel
import javax.swing.event.EventListenerList
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

/**
 * The selection model of a table that holds a [window] of a shown log's rows: it passes JTable's edits on to the
 * [selection], which is kept in rows of the shown log, so it reaches beyond the window and stays as the window
 * moves. Indices here are those of the window (see [TableWindow]); extending from an anchor outside it goes
 * through [extendTo] and [extendKeepingOthers], as JTable cannot see that anchor.
 */
class WindowSelectionModel(private val window: RowWindow) : ListSelectionModel {

    val selection = Selection()

    private val listeners = EventListenerList()
    private var adjusting = false
    private var changedWhileAdjusting = false
    private var kept = false

    private val rows: TableWindow
        get() {
            val start = window.windowStart
            return TableWindow(start, start + minOf(window.totalRowCount - start, WINDOW_ROWS))
        }

    private fun row(index: Int) = rows.rowAt(index)

    /** Selects from the anchor to the row at [index], wherever the anchor is. */
    fun extendTo(index: Int) = change { selection.extendTo(row(index)) }

    /** Like [extendTo], but other rows stay selected; see [Selection.extendKeepingOthers]. */
    fun extendKeepingOthers(index: Int) = change { selection.extendKeepingOthers(row(index)) }

    /** Runs [block] ignoring all edits and adjusting, so the selection stays as it is and no listener is told. */
    fun keeping(block: () -> Unit) {
        kept = true
        try {
            block()
        } finally {
            kept = false
        }
    }

    /** Selects all rows of the shown log there are now. */
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

    override fun getMinSelectionIndex() = selection.minIn(rows)

    override fun getMaxSelectionIndex() = selection.maxIn(rows)

    override fun isSelectedIndex(index: Int) = index >= 0 && row(index) in selection

    override fun getAnchorSelectionIndex() = rows.indexOf(selection.anchor)

    override fun setAnchorSelectionIndex(index: Int) = change { selection.moveTo(row(index), selection.lead) }

    override fun getLeadSelectionIndex() = rows.indexOf(selection.lead)

    override fun setLeadSelectionIndex(index: Int) = change { selection.setLead(row(index)) }

    override fun clearSelection() = change { selection.clear() }

    override fun isSelectionEmpty() = selection.isEmpty()

    override fun insertIndexInterval(index: Int, length: Int, before: Boolean) =
        change { selection.insert(row(if (before) index else index + 1), length) }

    override fun removeIndexInterval(index0: Int, index1: Int) =
        change { selection.delete(row(minOf(index0, index1)), row(maxOf(index0, index1)) + 1) }

    override fun setValueIsAdjusting(valueIsAdjusting: Boolean) {
        if (kept) return
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

    private inline fun change(edit: () -> Unit) {
        if (kept) return
        edit()
        fire()
    }

    // The whole window is reported as changed: the table repaints only what is on screen of it. Like Swing's
    // models, the listener added last hears first.
    private fun fire() {
        changedWhileAdjusting = adjusting
        val rows = rows
        val event = ListSelectionEvent(this, 0, maxOf(0, rows.end - rows.start - 1), adjusting)
        val listeners = listeners.getListeners(ListSelectionListener::class.java)
        for (listener in listeners.reversed()) listener.valueChanged(event)
    }
}
