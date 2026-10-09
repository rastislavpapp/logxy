package io.github.rastislavpapp.logxy.core.session

/** The records a filter lets through, in order; grows while the log is tailed. Used on one thread. */
class FilteredRows(rows: IntArray) {

    // May have room to grow beyond [size].
    private var rows = rows

    var size = rows.size
        private set

    /** The record at [index], which must be below [size]. */
    operator fun get(index: Int): Int = rows[index]

    /** Position of [record], or of the first record after it; -1 if there is none. */
    fun indexOf(record: Int): Int {
        val found = rows.binarySearch(record, 0, size)
        val index = if (found >= 0) found else -found - 1
        return if (index < size) index else -1
    }

    /**
     * Replaces the records from [from] on with [rows] (sorted, all `>= from`), e.g. a filter result
     * for appended records and the last one before them, which may have changed. Returns the position
     * where the replaced part starts.
     */
    fun replaceFrom(from: Int, rows: IntArray): Int {
        val found = this.rows.binarySearch(from, 0, size)
        val cut = if (found >= 0) found else -found - 1
        val newSize = cut + rows.size
        if (newSize > this.rows.size) this.rows = this.rows.copyOf(maxOf(newSize, this.rows.size * 2))
        System.arraycopy(rows, 0, this.rows, cut, rows.size)
        size = newSize
        return cut
    }
}
