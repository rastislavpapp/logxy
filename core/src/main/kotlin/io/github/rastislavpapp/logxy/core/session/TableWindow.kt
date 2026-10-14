package io.github.rastislavpapp.logxy.core.session

/**
 * The rows of a shown log that a table holds at once, rows [start] until [end]: a table cannot lay out tens of
 * millions. The table knows a row by its index here.
 */
class TableWindow(val start: Int, val end: Int) {

    /** The row at [index]; -1 for -1, which a table uses for none. */
    fun rowAt(index: Int): Int = if (index < 0) -1 else start + index

    /**
     * The index of [row]; -1 for a row outside, which a table reads as none. So a table can only be told
     * about rows inside: an anchor above the window would be no anchor to it.
     */
    fun indexOf(row: Int): Int = if (row in start..<end) row - start else -1
}
