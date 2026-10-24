package io.github.rastislavpapp.logxy.core.session

/**
 * A set of rows as sorted, disjoint, non-adjacent `[start, end)` ranges, e.g. a selection: all of 50 million rows
 * is one range. Ranges are kept as pairs in one array, so the cost follows the number of ranges, not of rows.
 * Not thread-safe.
 */
class RowRanges {

    // start0, end0, start1, end1, …; may have room beyond the first `2 * rangeCount`.
    private var bounds = IntArray(INITIAL_RANGES * 2)

    /** Number of ranges. */
    var rangeCount = 0
        private set

    /** Number of rows. */
    var count = 0
        private set

    private fun start(range: Int) = bounds[range * 2]

    private fun end(range: Int) = bounds[range * 2 + 1]

    /** Calls [action] with each range's start and end (exclusive), in order. */
    inline fun forEachRange(action: (start: Int, end: Int) -> Unit) {
        for (i in 0..<rangeCount) action(rangeStart(i), rangeEnd(i))
    }

    /** First row of range [range], which must be below [rangeCount]. */
    fun rangeStart(range: Int): Int = start(range)

    /** The row after the last of range [range], which must be below [rangeCount]. */
    fun rangeEnd(range: Int): Int = end(range)

    /** Adds rows [from] to [to] (exclusive). */
    fun add(from: Int, to: Int) {
        if (from >= to) return
        // Ranges touching [from, to), adjacent ones included, are merged with it.
        val first = firstEndingAtOrAfter(from)
        val last = firstStartingAfter(to)
        if (first == last) return replace(first, last, from, to)
        replace(first, last, minOf(from, start(first)), maxOf(to, end(last - 1)))
    }

    /** Removes rows [from] to [to] (exclusive); a range around them is split. */
    fun remove(from: Int, to: Int) {
        if (from >= to) return
        val first = firstEndingAtOrAfter(from + 1)
        val last = firstStartingAfter(to - 1)
        if (first == last) return
        val before = start(first)
        val after = end(last - 1)
        when {
            before < from && after > to -> replace(first, last, before, from, to, after)
            before < from -> replace(first, last, before, from)
            after > to -> replace(first, last, to, after)
            else -> replace(first, last)
        }
    }

    /**
     * Makes room for [count] new rows at [at]. The rows from [at] on move down by [count], and the new rows are not
     * added to the set.
     */
    fun insert(at: Int, count: Int) {
        if (count <= 0) return
        val split = firstEndingAtOrAfter(at + 1)
        if (split == rangeCount) return
        if (start(split) < at) {
            // [at] is inside this range: it splits around the new rows.
            val end = end(split)
            replace(split, split + 1, start(split), at, at + count, end + count)
            shiftFrom(split + 2, count)
        } else {
            shiftFrom(split, count)
        }
    }

    /** Takes out rows [from] to [to] (exclusive): rows after them move up. */
    fun delete(from: Int, to: Int) {
        if (from >= to) return
        remove(from, to)
        val after = firstStartingAfter(from - 1)
        shiftFrom(after, from - to)
        // The ranges on both sides now meet at [from].
        if (after in 1..<rangeCount && end(after - 1) == start(after)) {
            replace(after - 1, after + 1, start(after - 1), end(after))
        }
    }

    /** Moves ranges from [first] on by [delta] rows. */
    private fun shiftFrom(first: Int, delta: Int) {
        for (i in first * 2..<rangeCount * 2) bounds[i] += delta
    }

    /** The first row at [from] or later and before [to]; -1 if there is none. */
    fun firstIn(from: Int, to: Int): Int {
        val range = firstEndingAtOrAfter(from + 1)
        if (range == rangeCount) return -1
        val row = maxOf(from, start(range))
        return if (row < to) row else -1
    }

    /** The last row before [to] and at [from] or later; -1 if there is none. */
    fun lastIn(from: Int, to: Int): Int {
        val range = firstStartingAfter(to - 1) - 1
        if (range < 0) return -1
        val row = minOf(to, end(range)) - 1
        return if (row >= from) row else -1
    }

    fun copy(): RowRanges = RowRanges().also {
        it.bounds = bounds.copyOf(maxOf(rangeCount, INITIAL_RANGES) * 2)
        it.rangeCount = rangeCount
        it.count = count
    }

    fun clear() {
        rangeCount = 0
        count = 0
    }

    fun isEmpty(): Boolean = rangeCount == 0

    operator fun contains(row: Int): Boolean {
        val range = firstStartingAfter(row) - 1
        return range >= 0 && row < end(range)
    }

    /** The first row; -1 if there is none. */
    val min: Int get() = if (rangeCount == 0) -1 else start(0)

    /** The last row; -1 if there is none. */
    val max: Int get() = if (rangeCount == 0) -1 else end(rangeCount - 1) - 1

    /** The first range whose end is at or after [row]; [rangeCount] if none. */
    private fun firstEndingAtOrAfter(row: Int): Int {
        var low = 0
        var high = rangeCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (end(middle) < row) low = middle + 1 else high = middle
        }
        return low
    }

    /** The first range starting after [row]; [rangeCount] if none. */
    private fun firstStartingAfter(row: Int): Int {
        var low = 0
        var high = rangeCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (start(middle) <= row) low = middle + 1 else high = middle
        }
        return low
    }

    /** Replaces ranges [first] to [last] (exclusive) with the ranges in [pieces] (start, end pairs). */
    private fun replace(first: Int, last: Int, vararg pieces: Int) {
        for (i in first..<last) count -= end(i) - start(i)
        val newCount = rangeCount - (last - first) + pieces.size / 2
        if (newCount * 2 > bounds.size) bounds = bounds.copyOf(maxOf(newCount * 2, bounds.size * 2))
        System.arraycopy(bounds, last * 2, bounds, (first * 2) + pieces.size, (rangeCount - last) * 2)
        System.arraycopy(pieces, 0, bounds, first * 2, pieces.size)
        rangeCount = newCount
        for (i in pieces.indices step 2) count += pieces[i + 1] - pieces[i]
    }

    private companion object {
        const val INITIAL_RANGES = 4
    }
}
