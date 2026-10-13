package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.log.Level

private const val MICROS = 1_000L
private const val MILLIS = 1_000_000L
private const val SECONDS = 1_000_000_000L
private const val MINUTES = 60 * SECONDS
private const val HOURS = 60 * MINUTES
private const val DAYS = 24 * HOURS

/** Bin widths to start from, so that bin edges fall on round times. */
private val ROUND_WIDTHS = longArrayOf(
    1, 2, 5, 10, 20, 50, 100, 200, 500,
    MICROS, 2 * MICROS, 5 * MICROS, 10 * MICROS, 20 * MICROS, 50 * MICROS, 100 * MICROS, 200 * MICROS, 500 * MICROS,
    MILLIS, 2 * MILLIS, 5 * MILLIS, 10 * MILLIS, 20 * MILLIS, 50 * MILLIS, 100 * MILLIS, 200 * MILLIS, 500 * MILLIS,
    SECONDS, 2 * SECONDS, 5 * SECONDS, 10 * SECONDS, 15 * SECONDS, 30 * SECONDS,
    MINUTES, 2 * MINUTES, 5 * MINUTES, 10 * MINUTES, 15 * MINUTES, 30 * MINUTES,
    HOURS, 2 * HOURS, 3 * HOURS, 6 * HOURS, 12 * HOURS, DAYS,
)

/**
 * Records per span of time, counted separately for errors, warnings and the rest. Covers [BINS] bins
 * of [width] nanoseconds from [start]; a time outside moves the bins or, if they cannot cover it
 * otherwise, doubles their width. Used on one thread.
 */
class TimeHistogram(
    /** Times the records are expected to cover, to pick the first width; any time can be added. */
    expectedFrom: Long,
    expectedTo: Long,
) {
    /** Epoch nanoseconds where bin 0 starts; a multiple of [width]. */
    var start = 0L
        private set

    var width = roundWidth(expectedTo - expectedFrom)
        private set

    /** Bins that have records: from [firstBin] to [lastBin]; [firstBin] > [lastBin] while empty. */
    var firstBin = BINS
        private set
    var lastBin = -1
        private set

    private var counts = Array(CATEGORIES) { IntArray(BINS) }

    init {
        start = Math.floorDiv(expectedFrom, width) * width
    }

    val isEmpty get() = firstBin > lastBin

    /** Records of [category] ([OTHER], [WARN] or [ERROR]) in [bin]. */
    fun count(category: Int, bin: Int): Int = counts[category][bin]

    /** The earliest time, in epoch nanoseconds, that falls into [bin]. */
    fun binStart(bin: Int): Long = start + bin * width

    /** Adds one record of [level] at [time], widening the bins if it falls outside them. */
    fun add(time: Long, level: Level?) = add(time, category(level), 1)

    /** Adds [count] records of [category] at [time]. */
    fun add(time: Long, category: Int, count: Int) {
        val bin = fit(time)
        counts[category][bin] += count
        if (bin < firstBin) firstBin = bin
        if (bin > lastBin) lastBin = bin
    }

    /** Makes the bins reach [time] without counting anything there, e.g. for times still to be counted. */
    fun cover(time: Long) {
        val bin = fit(time)
        if (bin < firstBin) firstBin = bin
        if (bin > lastBin) lastBin = bin
    }

    /** Adds the records counted in [other]. */
    fun addAll(other: TimeHistogram) {
        if (other.isEmpty) return
        // Each of the other's bins must fall into one of these: widen them to a multiple of its width.
        if (width % other.width != 0L) {
            val common = width / gcd(width, other.width) * other.width
            move(Math.floorDiv(start, common) * common, common)
        }
        for (bin in other.firstBin..other.lastBin) {
            for (category in 0..<CATEGORIES) {
                val count = other.counts[category][bin]
                if (count != 0) add(other.binStart(bin), category, count)
            }
        }
    }

    /** An independent copy, e.g. to add more counts to while keeping this one as it is. */
    fun copy(): TimeHistogram = TimeHistogram(start, start).also {
        it.start = start
        it.width = width
        it.firstBin = firstBin
        it.lastBin = lastBin
        it.counts = Array(CATEGORIES) { category -> counts[category].copyOf() }
    }

    /** The bin [time] falls into, making room for it first. */
    private fun fit(time: Long): Int {
        while (true) {
            val bin = Math.floorDiv(time - start, width)
            if (bin in 0..<BINS) return bin.toInt()
            if (isEmpty) {
                start = Math.floorDiv(time, width) * width
                continue
            }
            val low = minOf(bin, firstBin.toLong())
            val high = maxOf(bin, lastBin.toLong())
            // Moving costs a pass over the bins: move so that times keep coming in without another move soon.
            if (high - low < BINS) move(start + low * width, width)
            else move(Math.floorDiv(start, 2 * width) * 2 * width, 2 * width)
        }
    }

    private fun move(newStart: Long, newWidth: Long) {
        val moved = Array(CATEGORIES) { IntArray(BINS) }
        var first = BINS
        var last = -1
        for (bin in firstBin..lastBin) {
            val to = ((binStart(bin) - newStart) / newWidth).toInt()
            for (category in 0..<CATEGORIES) moved[category][to] += counts[category][bin]
            first = minOf(first, to)
            last = maxOf(last, to)
        }
        counts = moved
        start = newStart
        width = newWidth
        firstBin = first
        lastBin = last
    }

    companion object {
        const val BINS = 4096
        const val OTHER = 0
        const val WARN = 1
        const val ERROR = 2
        const val CATEGORIES = 3

        /** The category a record of [level] is counted in: errors (with fatal), warnings, or the rest (also no level). */
        fun category(level: Level?): Int = when (level) {
            Level.ERROR, Level.FATAL -> ERROR
            Level.WARN -> WARN
            else -> OTHER
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        /** The smallest round width that covers [span] in [BINS] bins. */
        private fun roundWidth(span: Long): Long {
            val needed = maxOf(1L, (span + BINS - 1) / BINS)
            ROUND_WIDTHS.firstOrNull { it >= needed }?.let { return it }
            var width = DAYS
            while (width < needed) width *= 2
            return width
        }
    }
}
