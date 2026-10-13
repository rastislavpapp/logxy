package io.github.rastislavpapp.logxy.core.session

/**
 * The record of [records] (sorted, the first [size] count) after [current], or before it unless [forward],
 * wrapping around; with [inclusive], [current] itself counts if it is one of them. -1 if there are none.
 */
internal fun nextRecord(records: IntArray, size: Int, current: Int, forward: Boolean, inclusive: Boolean): Int {
    if (size == 0) return -1
    val found = records.binarySearch(current, 0, size)
    if (found >= 0 && inclusive) return current
    // The index of the first record after [current].
    val after = if (found >= 0) found + 1 else -found - 1
    val at = if (forward) {
        after
    } else if (found >= 0) {
        found - 1
    } else {
        after - 1
    }
    return records[Math.floorMod(at, size)]
}

/**
 * Records in order, e.g. those a filter lets through or a search found; grows while the log is tailed.
 * Used on one thread.
 */
internal class FilteredRows(rows: IntArray) {

    // May have room to grow beyond [size].
    private var rows = rows

    var size = rows.size
        private set

    /** The record at [index], which must be below [size]. */
    operator fun get(index: Int): Int = rows[index]

    /**
     * The rows below [size], for reading on another thread. Only the part [replaceFrom] replaces may change
     * under the reader, so a result for the rows read then is complete up to there.
     */
    internal fun array(): IntArray = rows

    /** Position of [record], or of the first record after it; -1 if there is none. */
    fun indexOf(record: Int): Int {
        val found = rows.binarySearch(record, 0, size)
        val index = if (found >= 0) found else -found - 1
        return if (index < size) index else -1
    }

    /** 1-based position of [record], 0 if it is not one of the rows. */
    fun positionOf(record: Int): Int = rows.binarySearch(record, 0, size).let { if (it >= 0) it + 1 else 0 }

    /** The record after [current], or before it unless [forward], wrapping around; see [nextRecord]. */
    fun next(current: Int, forward: Boolean, inclusive: Boolean): Int = nextRecord(rows, size, current, forward, inclusive)

    /**
     * Replaces the records from [from] on with [rows] (sorted, all `>= from`), e.g. a filter result
     * for appended records and the last one before them, which may have changed. Returns the first
     * position whose record changed, or where rows were added or removed.
     */
    fun replaceFrom(from: Int, rows: IntArray): Int {
        val found = this.rows.binarySearch(from, 0, size)
        val cut = if (found >= 0) found else -found - 1
        val newSize = cut + rows.size
        var changed = cut
        while (changed < minOf(size, newSize) && this.rows[changed] == rows[changed - cut]) changed++
        if (newSize > this.rows.size) this.rows = this.rows.copyOf(maxOf(newSize, this.rows.size * 2))
        System.arraycopy(rows, 0, this.rows, cut, rows.size)
        size = newSize
        return changed
    }
}
