package io.github.rastislavpapp.logxy.core.trace

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role

/** What [Traces.order] sorts by. */
enum class TraceColumn { ID, START, END, DURATION, RECORDS, LEVEL, SOURCES }

/**
 * The traces of a session's records by the trace ids in [column], numbered from 0 in the order their first record
 * was read; see `GLOSSARY.md`. Times are epoch nanoseconds, with a merged file's timestamp shift added. A snapshot:
 * it does not change as records are appended, and can be read on any thread.
 */
class Traces internal constructor(
    val column: String,
    private val keys: KeyView,
    /** The trace ids of the traces numbered from [KeyView.count] on, whose records may still change. */
    private val newKeys: List<ByteArray>,
    private val starts: LongArray,
    private val ends: LongArray,
    private val counts: IntArray,
    /** Per trace, its worst level as by [severity], or [NO_LEVEL]. */
    private val levels: ByteArray,
    private val firsts: IntArray,
    /** Per trace, [sourceWords] longs with a bit set for each file its records come from. */
    private val sources: LongArray,
    /** The names of a merged session's files; empty for a single file. */
    private val sourceNames: List<String>,
) {
    private val sourceWords = (sourceNames.size + Long.SIZE_BITS - 1) / Long.SIZE_BITS

    val size: Int get() = counts.size

    /** Whether the records come from a merge's files, which [sources] names. */
    val isMerged: Boolean get() = sourceNames.isNotEmpty()

    /** The duration statistics over the traces with times; null if no trace has one. */
    val statistics: DurationStatistics? = DurationStatistics.of(timedDurations())

    private fun timedDurations(): LongArray {
        val durations = LongArray(size, ::duration)
        var timed = 0
        for (duration in durations) if (duration != NO_DURATION) durations[timed++] = duration
        return durations.copyOf(timed)
    }

    fun id(trace: Int): String = String(key(trace), Charsets.UTF_8)

    private fun key(trace: Int): ByteArray = if (trace < keys.count) keys.key(trace) else newKeys[trace - keys.count]

    /** The earliest time among the records of [trace]; [Records.NO_TIMESTAMP] if none of them has one. */
    fun start(trace: Int): Long = starts[trace]

    /** The latest time among the records of [trace]; [Records.NO_TIMESTAMP] if none of them has one. */
    fun end(trace: Int): Long = ends[trace]

    /** Nanoseconds from [start] to [end], 0 for a trace of one record; [NO_DURATION] if it has no times. */
    fun duration(trace: Int): Long =
        if (starts[trace] == Records.NO_TIMESTAMP) NO_DURATION else ends[trace] - starts[trace]

    fun records(trace: Int): Int = counts[trace]

    /** The most severe level among the records of [trace], [Level.UNKNOWN] counting least; null if none has one. */
    fun worstLevel(trace: Int): Level? = when (val severity = levels[trace].toInt()) {
        NO_LEVEL.toInt() -> null
        0 -> Level.UNKNOWN
        else -> Level.entries[severity - 1]
    }

    /** The record number, among the session's records, of the first record of [trace]. */
    fun firstRecord(trace: Int): Int = firsts[trace]

    /** The names of the files the records of [trace] come from, in the session's order; empty for a single file. */
    fun sources(trace: Int): List<String> = sourceNames.filterIndexed { part, _ -> hasSource(trace, part) }

    private fun hasSource(trace: Int, part: Int): Boolean =
        sources[trace * sourceWords + part / Long.SIZE_BITS] and (1L shl part) != 0L

    /**
     * The trace numbers sorted by [column], with ties in trace order. Traces without the value, such as a time,
     * come first ascending and last descending.
     */
    fun order(column: TraceColumn, descending: Boolean): IntArray {
        val compare: (Int, Int) -> Int = when (column) {
            TraceColumn.ID -> ::compareIds
            TraceColumn.START -> { a, b -> starts[a].compareTo(starts[b]) }
            TraceColumn.END -> { a, b -> ends[a].compareTo(ends[b]) }
            TraceColumn.DURATION -> { a, b -> duration(a).compareTo(duration(b)) }
            TraceColumn.RECORDS -> { a, b -> counts[a].compareTo(counts[b]) }
            TraceColumn.LEVEL -> { a, b -> levels[a].compareTo(levels[b]) }
            TraceColumn.SOURCES -> ::compareSources
        }
        val sign = if (descending) -1 else 1
        return mergeSort(IntArray(size) { it }) { a, b -> sign * compare(a, b) }
    }

    private fun compareIds(a: Int, b: Int): Int {
        if (a < keys.count && b < keys.count) return keys.compare(a, b)
        return java.util.Arrays.compareUnsigned(key(a), key(b))
    }

    // By the first file, then the next ones, as the column lists them.
    private fun compareSources(a: Int, b: Int): Int {
        for (word in 0..<sourceWords) {
            // Reversed bits put the lowest file first, as an unsigned number.
            val x = java.lang.Long.reverse(sources[a * sourceWords + word])
            val y = java.lang.Long.reverse(sources[b * sourceWords + word])
            if (x != y) return -java.lang.Long.compareUnsigned(x, y)
        }
        return 0
    }

    companion object {
        /** What [duration] gives for a trace without times. */
        const val NO_DURATION = -1L

        internal const val NO_LEVEL: Byte = -1

        /** [level] as a byte that orders levels by severity, [Level.UNKNOWN] least: 0 for it, else ordinal + 1. */
        internal fun severity(level: Level): Byte =
            if (level == Level.UNKNOWN) 0 else (level.ordinal + 1).toByte()

        /**
         * Whether the trace ids can come from [field]: a text field shown as a column, not the timestamp, level,
         * message or a merge's source.
         */
        fun canHoldTraceIds(field: Field): Boolean =
            !field.hidden && (field.type == ColumnType.DICT || field.type == ColumnType.SPAN) &&
                field.role != Role.MESSAGE && field.role != Role.SOURCE
    }
}

/**
 * The spread of trace durations in nanoseconds. A percentile is the nearest rank: the smallest duration that at least
 * that share of the traces does not exceed, so the median of 1, 2, 3, 4 is 2.
 */
class DurationStatistics private constructor(
    val count: Int,
    val min: Long,
    val median: Long,
    val p90: Long,
    val p95: Long,
    val p99: Long,
    val max: Long,
) {
    companion object {
        /** The statistics of [durations]; null if there are none. */
        fun of(durations: LongArray): DurationStatistics? {
            if (durations.isEmpty()) return null
            val sorted = durations.sortedArray()
            fun percentile(percent: Int): Long {
                val rank = (sorted.size.toLong() * percent + 99) / 100
                return sorted[(rank - 1).toInt()]
            }
            return DurationStatistics(
                sorted.size,
                sorted.first(),
                percentile(50),
                percentile(90),
                percentile(95),
                percentile(99),
                sorted.last(),
            )
        }
    }
}

/** Sorts [items] by [compare], keeping the order of equal ones; without boxing them. */
private inline fun mergeSort(items: IntArray, compare: (Int, Int) -> Int): IntArray {
    var source = items
    var target = IntArray(items.size)
    var width = 1
    while (width < items.size) {
        var from = 0
        while (from < items.size) {
            val middle = minOf(from + width, items.size)
            val to = minOf(from + 2 * width, items.size)
            var i = from
            var j = middle
            var k = from
            // The right run's item goes first only if smaller, so equal ones keep their order.
            while (i < middle && j < to) {
                target[k++] = if (compare(source[j], source[i]) < 0) source[j++] else source[i++]
            }
            while (i < middle) target[k++] = source[i++]
            while (j < to) target[k++] = source[j++]
            from = to
        }
        val swap = source
        source = target
        target = swap
        width *= 2
    }
    return source
}
