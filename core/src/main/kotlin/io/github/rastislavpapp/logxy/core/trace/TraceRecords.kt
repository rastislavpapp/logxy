package io.github.rastislavpapp.logxy.core.trace

import io.github.rastislavpapp.logxy.core.log.Records

/**
 * The records of one trace in the session's order, for showing the steps between them. Each record has its record
 * number, its time and the first line of its message, and its message template names its steps.
 *
 * Times are epoch nanoseconds, with a merged file's timestamp shift added. A record without a time has no step;
 * the step before it reaches over it to the next record with one, as in [TraceSteps]. Unlike there, a step whose
 * time runs backwards keeps its negative time, which shows where the files of a merge disagree.
 */
class TraceRecords internal constructor(
    private val records: IntArray,
    private val times: LongArray,
    private val messages: List<String>,
    private val templates: List<String>,
    /** The trace's duration in nanoseconds, which [share] divides by; see [Traces.duration]. */
    private val duration: Long,
) {
    private val steps = LongArray(records.size) { Traces.NO_DURATION }.also { steps ->
        var last = -1
        for (i in records.indices) {
            if (times[i] == Records.NO_TIMESTAMP) continue
            if (last >= 0) steps[last] = times[i] - times[last]
            last = i
        }
    }

    val size: Int get() = records.size

    /** The record number of the [n]th record, counted from 0. */
    fun record(n: Int): Int = records[n]

    /** The time of the [n]th record, or [Records.NO_TIMESTAMP]. */
    fun time(n: Int): Long = times[n]

    fun message(n: Int): String = messages[n]

    fun template(n: Int): String = templates[n]

    /**
     * Nanoseconds from the [n]th record to the next one with a time; [Traces.NO_DURATION] if it has no time or
     * none follows.
     */
    fun step(n: Int): Long = steps[n]

    /** The share of the trace's duration the [n]th record's step takes, 1 for all of it; 0 for a trace of no time. */
    fun share(n: Int): Double =
        if (steps[n] == Traces.NO_DURATION || duration <= 0) 0.0 else steps[n].toDouble() / duration

    companion object {
        /** The most records of a trace that [io.github.rastislavpapp.logxy.core.session.LogSession] gives. */
        const val MOST = 1000
    }
}
