package io.github.rastislavpapp.logxy.core.session

/**
 * The rows the user marked in a shown log, by their row in the shown log, not among those a table holds at once
 * (see [TableWindow]). Edits follow Swing's `ListSelectionModel`: [anchor] is where the last edit started and
 * [lead] where it ended, both -1 if none. Not thread-safe.
 */
class Selection {

    /** The selected rows; change them only through this selection. */
    val rows = RowRanges()

    var anchor = -1
        private set

    var lead = -1
        private set

    val count: Int get() = rows.count

    fun isEmpty(): Boolean = rows.isEmpty()

    operator fun contains(row: Int): Boolean = row in rows

    /** Selects the rows from [anchor] to [lead] (both included, in either order) and nothing else. */
    fun select(anchor: Int, lead: Int) {
        rows.clear()
        add(anchor, lead)
    }

    /** Adds the rows from [anchor] to [lead] (both included, in either order). */
    fun add(anchor: Int, lead: Int) {
        rows.add(minOf(anchor, lead), maxOf(anchor, lead) + 1)
        moveTo(anchor, lead)
    }

    /** Deselects the rows from [anchor] to [lead] (both included, in either order). */
    fun remove(anchor: Int, lead: Int) {
        rows.remove(minOf(anchor, lead), maxOf(anchor, lead) + 1)
        moveTo(anchor, lead)
    }

    /** Selects the rows from the anchor to [row], and nothing else; without an anchor, [row] alone. */
    fun extendTo(row: Int) = select(if (anchor >= 0) anchor else row, row)

    /**
     * Selects the rows from the anchor to [row] if the anchor is selected, else deselects them; other rows stay
     * as they are, as with Ctrl+Shift+click. Without an anchor, adds [row].
     */
    fun extendKeepingOthers(row: Int) {
        if (anchor < 0) return add(row, row)
        if (anchor in rows) add(anchor, row) else remove(anchor, row)
    }

    /** The first selected row of [window], as its index there; -1 if none is. */
    fun minIn(window: TableWindow): Int = window.indexOf(rows.firstIn(window.start, window.end))

    /** The last selected row of [window], as its index there; -1 if none is. */
    fun maxIn(window: TableWindow): Int = window.indexOf(rows.lastIn(window.start, window.end))

    /** Selects the first [size] rows, those shown now: rows shown later are not selected. Anchor and lead stay. */
    fun selectAll(size: Int) {
        rows.clear()
        rows.add(0, size)
    }

    /** Deselects all rows; anchor and lead stay. */
    fun clear() = rows.clear()

    /** Sets anchor and lead without changing which rows are selected. */
    fun moveTo(anchor: Int, lead: Int) {
        this.anchor = anchor
        this.lead = lead
    }

    /**
     * Moves the lead to [row], making the rows from the anchor to it all selected if the anchor is, or all
     * deselected if not; the rows from the anchor to the old lead get the anchor's opposite state first.
     * Without an anchor, nothing changes.
     */
    fun setLead(row: Int) {
        if (row < 0 || anchor < 0) {
            if (row < 0 && anchor < 0) lead = row
            return
        }
        val old = if (lead < 0) row else lead
        lead = row
        val from = minOf(anchor, old)
        val to = maxOf(anchor, old) + 1
        if (anchor in rows) {
            rows.remove(from, to)
            rows.add(minOf(anchor, row), maxOf(anchor, row) + 1)
        } else {
            rows.add(from, to)
            rows.remove(minOf(anchor, row), maxOf(anchor, row) + 1)
        }
    }

    /** Rows were inserted at [at]: rows from there on move down by [count]; the new rows are not selected. */
    fun insert(at: Int, count: Int) {
        rows.insert(at, count)
        if (anchor >= at) anchor += count
        if (lead >= at) lead += count
    }

    /**
     * Rows [from] to [to] (exclusive) were taken out: the rows after them move up. An anchor or lead among them
     * moves to the row before them, as in Swing.
     */
    fun delete(from: Int, to: Int) {
        rows.delete(from, to)
        anchor = movedByDelete(anchor, from, to)
        lead = movedByDelete(lead, from, to)
    }

    private fun movedByDelete(row: Int, from: Int, to: Int) = when {
        row >= to -> row - (to - from)
        row >= from -> if (from == 0) 0 else from - 1
        else -> row
    }
}
