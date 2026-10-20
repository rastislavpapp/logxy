package io.github.rastislavpapp.logxy.ui

/**
 * Where a table's rows lie when all are [base] pixels high but a few: [tallRows] (any order) are [tallHeights]
 * high. Lookups take a binary search over the tall rows, so many plain rows cost nothing.
 */
internal class RowHeights(private val base: Int, tallRows: IntArray, tallHeights: IntArray) {

    private val rows: IntArray
    private val heights: IntArray

    /**
     * Extra pixels of the tall rows before each of [rows], and of all of them at the end; at most [MAX_EXTRA], so the
     * rows' y stays an int however many tall rows there are. Rows past that limit overlap.
     */
    private val extraBefore: IntArray

    init {
        val order = tallRows.indices.sortedBy { tallRows[it] }
        rows = IntArray(order.size) { tallRows[order[it]] }
        heights = IntArray(order.size) { tallHeights[order[it]] }
        extraBefore = IntArray(order.size + 1)
        for (i in order.indices) {
            val sum = extraBefore[i].toLong() + heights[i] - base
            extraBefore[i + 1] = minOf(sum, MAX_EXTRA.toLong()).toInt()
        }
    }

    val isUniform: Boolean get() = rows.isEmpty()

    fun height(row: Int): Int {
        val i = rows.binarySearch(row)
        return if (i >= 0) heights[i] else base
    }

    /** The y of [row]'s top; of the end of the rows for the row past the last. */
    fun top(row: Int): Int = row * base + extraBefore[tallBefore(row)]

    /** The row of [rowCount] at [y]; -1 if none is there. */
    fun rowAt(y: Int, rowCount: Int): Int {
        if (y < 0 || rowCount <= 0 || y >= top(rowCount)) return -1
        var low = 0
        var high = rowCount - 1
        // The last row whose top is at y or above.
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (top(middle) <= y) low = middle else high = middle - 1
        }
        return low
    }

    /** How many of [rows] lie before [row]. */
    private fun tallBefore(row: Int): Int {
        val i = rows.binarySearch(row)
        return if (i >= 0) i else -i - 1
    }

    private companion object {
        /** Leaves room in an int for the rows of a whole window at their plain height. */
        const val MAX_EXTRA = Int.MAX_VALUE / 2
    }
}
