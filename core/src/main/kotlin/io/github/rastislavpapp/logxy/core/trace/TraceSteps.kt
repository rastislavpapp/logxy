package io.github.rastislavpapp.logxy.core.trace

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * The steps of all traces summed by template pair: each pair is the message templates of a step's two records, and
 * the pairs are numbered from 0. Times are nanoseconds; a step whose time runs backwards, such as between two files of
 * a merge, counts as 0. A snapshot, readable on any thread.
 *
 * Counts and totals are exact. Percentiles are estimated from a histogram of each pair's step times, within 1% of
 * the exact nearest rank (see [DurationStatistics]), and never outside the pair's shortest and longest step, so a pair
 * whose steps all took the same time has it exactly.
 */
class TraceSteps internal constructor(
    /** The message templates by the session-wide ids the pairs refer to. */
    private val templates: List<String>,
    private val froms: IntArray,
    private val tos: IntArray,
    private val counts: IntArray,
    private val totals: LongArray,
    /**
     * Per pair, its shortest and longest step. Once a step was split in two, the pair may no longer have that step,
     * but its other steps still lie between the two.
     */
    private val mins: LongArray,
    private val maxes: LongArray,
    /** Per pair, how many of its steps fall in each [Histogram] bucket from the pair's [lows] on. */
    private val histograms: Array<IntArray>,
    private val lows: IntArray,
    /** Per pair, the traces of [longest] and the times of [longestTimes]. */
    private val longestTraces: Array<IntArray>,
    private val longestTimes: Array<LongArray>,
) {
    val size: Int get() = counts.size

    /** The time of all steps together, which is that of all traces less the time running backwards. */
    val total: Long = totals.sum()

    /** The message template of the first record of [pair]'s steps; null for records past the templates kept. */
    fun from(pair: Int): String? = templates.getOrNull(froms[pair])

    /** The message template of the second record of [pair]'s steps; null as for [from]. */
    fun to(pair: Int): String? = templates.getOrNull(tos[pair])

    /** The pair of steps from a record of template [from] to one of template [to], as [from] and [to] give them. */
    fun pairOf(from: String?, to: String?): Int = (0..<size).indexOfFirst { from(it) == from && to(it) == to }

    fun count(pair: Int): Int = counts[pair]

    fun total(pair: Int): Long = totals[pair]

    fun median(pair: Int): Long = percentile(pair, 50)

    fun p95(pair: Int): Long = percentile(pair, 95)

    /** The step time that [percent] percent of [pair]'s steps do not exceed, estimated. */
    private fun percentile(pair: Int, percent: Int): Long {
        val rank = (counts[pair].toLong() * percent + 99) / 100
        val histogram = histograms[pair]
        var index = 0
        var seen = histogram[0].toLong()
        while (seen < rank && index < histogram.size - 1) seen += histogram[++index]
        return Histogram.time(lows[pair] + index).coerceIn(mins[pair], maxes[pair])
    }

    /**
     * The traces where [pair]'s step took longest, each once and the longest first, at most [LONGEST] of them. A
     * trace can be missing when a merged file's last record, counted late, split its step.
     */
    fun longest(pair: Int): IntArray = longestTraces[pair].copyOf()

    /** The time of [pair]'s step in each trace of [longest], at the same index. */
    fun longestTimes(pair: Int): LongArray = longestTimes[pair].copyOf()

    /** The pairs by [total], the longest first. */
    fun order(): IntArray = (0..<size).sortedByDescending { totals[it] }.toIntArray()

    companion object {
        /** The most traces [longest] gives. */
        const val LONGEST = 50
    }
}

/**
 * The buckets of step times for estimating percentiles: bucket 0 holds the steps of 0, and bucket b above it those
 * from `RATIO^(b-1)` up to `RATIO^b` nanoseconds, so a bucket's middle is within 0.75% of each of its times.
 */
internal object Histogram {
    private const val RATIO = 1.015
    private val LN_RATIO = ln(RATIO)

    fun bucket(time: Long): Int = if (time <= 0) 0 else 1 + (ln(time.toDouble()) / LN_RATIO).toInt()

    /** The middle of [bucket], in the ratio of its ends. */
    fun time(bucket: Int): Long = if (bucket == 0) 0 else exp((bucket - 0.5) * LN_RATIO).roundToLong()
}

/**
 * Sums steps by template pair as they are added, in primitive arrays grown as pairs are added. Templates are
 * session-wide ids, -1 for a record past the templates kept.
 */
internal class StepCounter private constructor(
    private val pairs: PairMap,
    private var froms: IntArray,
    private var tos: IntArray,
    private var counts: IntArray,
    private var totals: LongArray,
    /** Per pair, the shortest and the longest step [add] counted, which [remove] leaves as they are. */
    private var mins: LongArray,
    private var maxes: LongArray,
    /** Per pair, its steps by [Histogram] bucket from the pair's [lows] on, over the buckets it had steps in. */
    private var histograms: Array<IntArray?>,
    private var lows: IntArray,
    /**
     * Per pair, [TraceSteps.LONGEST] places for the traces where its step took longest, in no order. Each place
     * holds a trace and its longest time of the step. [longestCounts] tells how many of a pair's places are taken,
     * and once all are, [floors] holds the shortest of their times, which a step must beat to get in.
     */
    private var longestTraces: IntArray,
    private var longestTimes: LongArray,
    private var longestCounts: IntArray,
    private var floors: LongArray,
) {
    constructor() : this(
        PairMap(),
        IntArray(INITIAL_PAIRS),
        IntArray(INITIAL_PAIRS),
        IntArray(INITIAL_PAIRS),
        LongArray(INITIAL_PAIRS),
        LongArray(INITIAL_PAIRS),
        LongArray(INITIAL_PAIRS),
        arrayOfNulls(INITIAL_PAIRS),
        IntArray(INITIAL_PAIRS),
        IntArray(INITIAL_PAIRS * TraceSteps.LONGEST),
        LongArray(INITIAL_PAIRS * TraceSteps.LONGEST),
        IntArray(INITIAL_PAIRS),
        LongArray(INITIAL_PAIRS),
    )

    /**
     * Counts a step of [duration] nanoseconds in [trace], from a record of template [from] to one of template
     * [to].
     */
    fun add(from: Int, to: Int, duration: Long, trace: Int) {
        val pair = pairOf(from, to)
        val time = maxOf(0L, duration)
        counts[pair]++
        totals[pair] += time
        mins[pair] = minOf(mins[pair], time)
        maxes[pair] = maxOf(maxes[pair], time)
        countIn(pair, Histogram.bucket(time), 1)
        keepLongest(pair, trace, time)
    }

    /**
     * Takes back a step [add] counted, which a record found between its two records splits. The trace leaves the
     * pair's longest if it is there with this step.
     */
    fun remove(from: Int, to: Int, duration: Long, trace: Int) {
        val pair = pairOf(from, to)
        val time = maxOf(0L, duration)
        counts[pair]--
        totals[pair] -= time
        countIn(pair, Histogram.bucket(time), -1)
        val base = pair * TraceSteps.LONGEST
        val count = longestCounts[pair]
        for (place in base..<base + count) {
            if (longestTraces[place] != trace || longestTimes[place] != time) continue
            longestTraces[place] = longestTraces[base + count - 1]
            longestTimes[place] = longestTimes[base + count - 1]
            longestCounts[pair]--
            return
        }
    }

    /** Puts [trace] among [pair]'s longest if its step of [time] is longer than one there, or there is room. */
    private fun keepLongest(pair: Int, trace: Int, time: Long) {
        val count = longestCounts[pair]
        if (count == TraceSteps.LONGEST && time <= floors[pair]) return
        val base = pair * TraceSteps.LONGEST
        var place = base
        while (place < base + count && longestTraces[place] != trace) place++
        when {
            place < base + count -> if (time > longestTimes[place]) longestTimes[place] = time
            count < TraceSteps.LONGEST -> {
                longestTraces[place] = trace
                longestTimes[place] = time
                longestCounts[pair]++
            }
            else -> {
                val shortest = (base..<base + count).minBy { longestTimes[it] }
                longestTraces[shortest] = trace
                longestTimes[shortest] = time
            }
        }
        if (longestCounts[pair] == TraceSteps.LONGEST) {
            floors[pair] = (base..<base + TraceSteps.LONGEST).minOf { longestTimes[it] }
        }
    }

    /** Adds [delta] to [pair]'s steps in [bucket], growing its histogram to reach it. */
    private fun countIn(pair: Int, bucket: Int, delta: Int) {
        var histogram = histograms[pair]
        if (histogram == null) {
            histogram = IntArray(1)
            lows[pair] = bucket
        }
        val low = lows[pair]
        if (bucket < low || bucket >= low + histogram.size) {
            val newLow = minOf(low, bucket)
            val grown = IntArray(maxOf(low + histogram.size, bucket + 1) - newLow)
            histogram.copyInto(grown, low - newLow)
            histogram = grown
            lows[pair] = newLow
        }
        histograms[pair] = histogram
        histogram[bucket - lows[pair]] += delta
    }

    private fun pairOf(from: Int, to: Int): Int {
        val key = (from.toLong() shl Int.SIZE_BITS) or (to.toLong() and 0xFFFF_FFFFL)
        val found = pairs.get(key)
        if (found >= 0) return found
        val pair = pairs.size
        if (pair == counts.size) {
            val capacity = pair * 2
            froms = froms.copyOf(capacity)
            tos = tos.copyOf(capacity)
            counts = counts.copyOf(capacity)
            totals = totals.copyOf(capacity)
            mins = mins.copyOf(capacity)
            maxes = maxes.copyOf(capacity)
            histograms = histograms.copyOf(capacity)
            lows = lows.copyOf(capacity)
            longestTraces = longestTraces.copyOf(capacity * TraceSteps.LONGEST)
            longestTimes = longestTimes.copyOf(capacity * TraceSteps.LONGEST)
            longestCounts = longestCounts.copyOf(capacity)
            floors = floors.copyOf(capacity)
        }
        froms[pair] = from
        tos[pair] = to
        mins[pair] = Long.MAX_VALUE
        pairs.put(key, pair)
        return pair
    }

    fun copy() = StepCounter(
        pairs.copy(),
        froms.copyOf(),
        tos.copyOf(),
        counts.copyOf(),
        totals.copyOf(),
        mins.copyOf(),
        maxes.copyOf(),
        Array(histograms.size) { histograms[it]?.copyOf() },
        lows.copyOf(),
        longestTraces.copyOf(),
        longestTimes.copyOf(),
        longestCounts.copyOf(),
        floors.copyOf(),
    )

    /**
     * The steps summed so far, with [templates] the texts of the session-wide template ids; without the pairs whose
     * steps were all split by [remove].
     */
    fun toSteps(templates: List<String>): TraceSteps {
        val kept = (0..<pairs.size).filter { counts[it] > 0 }.toIntArray()
        val keptTraces = Array(kept.size) { IntArray(0) }
        val keptTimes = Array(kept.size) { LongArray(0) }
        for ((i, pair) in kept.withIndex()) {
            val places = longestPlaces(pair)
            keptTraces[i] = IntArray(places.size) { longestTraces[places[it]] }
            keptTimes[i] = LongArray(places.size) { longestTimes[places[it]] }
        }
        return TraceSteps(
            templates,
            IntArray(kept.size) { froms[kept[it]] },
            IntArray(kept.size) { tos[kept[it]] },
            IntArray(kept.size) { counts[kept[it]] },
            LongArray(kept.size) { totals[kept[it]] },
            LongArray(kept.size) { mins[kept[it]] },
            LongArray(kept.size) { maxes[kept[it]] },
            Array(kept.size) { histograms[kept[it]]!!.copyOf() },
            IntArray(kept.size) { lows[kept[it]] },
            keptTraces,
            keptTimes,
        )
    }

    /** The taken places of [pair]'s longest, the longest time first, and of equal times the trace numbered first. */
    private fun longestPlaces(pair: Int): List<Int> {
        val base = pair * TraceSteps.LONGEST
        val longestFirst = compareByDescending<Int> { longestTimes[it] }.thenBy { longestTraces[it] }
        return (base..<base + longestCounts[pair]).sortedWith(longestFirst)
    }

    private companion object {
        const val INITIAL_PAIRS = 64
    }
}

/** A map from long keys to ints 0, 1, 2, … in the order they were put, without boxing; open addressing. */
private class PairMap(private var keys: LongArray, private var values: IntArray, size: Int) {
    constructor() : this(LongArray(INITIAL_SLOTS) { EMPTY }, IntArray(INITIAL_SLOTS), 0)

    var size = size
        private set

    /** The value of [key]; -1 if it has none. */
    fun get(key: Long): Int {
        var slot = slotOf(key)
        while (true) {
            val at = keys[slot]
            if (at == key) return values[slot]
            if (at == EMPTY) return -1
            slot = (slot + 1) and (keys.size - 1)
        }
    }

    /** Puts [key], which has no value yet, with [value]. */
    fun put(key: Long, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        var slot = slotOf(key)
        while (keys[slot] != EMPTY) slot = (slot + 1) and (keys.size - 1)
        keys[slot] = key
        values[slot] = value
        size++
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = IntArray(keys.size)
        size = 0
        for (slot in oldKeys.indices) if (oldKeys[slot] != EMPTY) put(oldKeys[slot], oldValues[slot])
    }

    // Spreads the bits of both templates over the slots: a pair's two ids differ mostly in their low bits.
    private fun slotOf(key: Long): Int = (java.lang.Long.hashCode(key * MIX) and (keys.size - 1))

    fun copy() = PairMap(keys.copyOf(), values.copyOf(), size)

    private companion object {
        const val INITIAL_SLOTS = 128

        /** No template pair packs to it: that would need a template id of [Int.MIN_VALUE]. */
        const val EMPTY = Long.MIN_VALUE

        const val MIX = -0x61c8864680b583ebL
    }
}
