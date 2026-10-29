package io.github.rastislavpapp.logxy.core.trace

import kotlin.math.exp
import kotlin.math.ln

/**
 * How many traces took how long: [counts] per bin, where bin `i` holds the durations from [from] up to, not
 * including, [to]. The bins grow by a constant factor, so a few far slower traces take the bins at the end and leave
 * the rest of the range with its shape. Durations of 0 have a bin of their own first, if there are any
 * ([hasZeroBin]), as no factor reaches them.
 */
class DurationHistogram private constructor(
    private val edges: LongArray,
    val counts: IntArray,
    val hasZeroBin: Boolean,
) {
    /** The shortest duration, in nanoseconds, that falls in [bin]. */
    fun from(bin: Int): Long = edges[bin]

    /** The first duration, in nanoseconds, above those of [bin]. */
    fun to(bin: Int): Long = edges[bin + 1]

    /** The bin that holds [duration], clamped to the first or last bin if it is outside the histogram. */
    fun binOf(duration: Long): Int {
        var low = 0
        var high = counts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (edges[middle] <= duration) low = middle else high = middle - 1
        }
        return low
    }

    companion object {
        /**
         * The histogram of [durations], in nanoseconds and none negative, with at most [maxBins] bins; null if there
         * are none. A range narrower than [maxBins] nanoseconds has a bin per whole nanosecond at most.
         */
        fun of(durations: LongArray, maxBins: Int): DurationHistogram? {
            if (durations.isEmpty()) return null
            var zeros = 0
            var shortest = Long.MAX_VALUE
            var longest = 0L
            for (duration in durations) {
                if (duration == 0L) zeros++ else shortest = minOf(shortest, duration)
                longest = maxOf(longest, duration)
            }
            val hasZeroBin = zeros > 0
            if (longest == 0L) return DurationHistogram(longArrayOf(0, 1), intArrayOf(zeros), true)
            val edges = growingEdges(shortest, longest + 1, maxOf(1, maxBins - if (hasZeroBin) 1 else 0), hasZeroBin)
            val histogram = DurationHistogram(edges, IntArray(edges.size - 1), hasZeroBin)
            for (duration in durations) histogram.counts[histogram.binOf(duration)]++
            return histogram
        }

        /**
         * The edges of at most [bins] bins from [low] to [high] (exclusive), each as many times the one before as the
         * next, rounded to whole nanoseconds and without repeats. With [zeroBin] the first bin ends at [low] and
         * holds the zeros.
         */
        private fun growingEdges(low: Long, high: Long, bins: Int, zeroBin: Boolean): LongArray {
            val edges = ArrayList<Long>(bins + 2)
            if (zeroBin) edges += 0L
            edges += low
            val logLow = ln(low.toDouble())
            val logHigh = ln(high.toDouble())
            for (i in 1..<bins) {
                val edge = Math.round(exp(logLow + (logHigh - logLow) * i / bins))
                if (edge > edges.last() && edge < high) edges += edge
            }
            edges += high
            return edges.toLongArray()
        }
    }
}
