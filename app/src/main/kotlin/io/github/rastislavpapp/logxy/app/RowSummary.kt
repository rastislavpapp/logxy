package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.session.TimeHistogram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Blocks the rows are summarized in; blocks cover more rows as rows are added. */
private const val MAX_BLOCKS = 1 shl 16

/** Rows summarized before the UI thread handles other events again. */
private const val STEP_ROWS = 1 shl 18

/** Rows at each end of the shown rows looked at for the times they cover. */
private const val RANGE_PROBE_ROWS = 1000

const val SEVERITY_WARN = 1
const val SEVERITY_ERROR = 2

/**
 * One pass over a table's shown rows: warnings and errors per block of rows, for marks along its scroll
 * bar, and the [histogram] of their times. Built in steps on the UI thread, which owns the records;
 * [onChanged] is called as it gets further.
 */
class RowSummary(
    private val model: LogTableModel,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) : RowMarks {

    private val records = model.records
    private val hasLevels = records.schema.indexOf(Role.LEVEL) >= 0
    private val hasTimestamps = records.schema.indexOf(Role.TIMESTAMP) >= 0
    private val warnings = IntArray(MAX_BLOCKS)
    private val errors = IntArray(MAX_BLOCKS)
    private var shift = 0
    private var summarized = 0
    private var job: Job? = null

    /** Shown rows per span of time; null if the records have no timestamps. */
    var histogram: TimeHistogram? = null
        private set

    // Rows counted in the histogram; the last one is kept, as appends may change it.
    private var counted = 0
    private var lastTime = Records.NO_TIMESTAMP
    private var lastLevel: Level? = null

    /** All shown rows changed, e.g. by a new filter result. */
    fun reset() {
        shift = 0
        while (model.totalRowCount ushr shift >= MAX_BLOCKS) shift++
        summarized = 0
        resetHistogram()
        update()
    }

    /** Shown rows from [row] on changed, or were added. */
    fun changedFrom(row: Int) {
        summarized = minOf(summarized, row ushr shift shl shift)
        val histogram = histogram
        if (histogram != null && row < counted) {
            if (row == counted - 1) {
                if (lastTime != Records.NO_TIMESTAMP) histogram.remove(lastTime, lastLevel)
                counted = row
            } else {
                // Rows further back are not kept; rare enough to count all again.
                summarized = 0
                resetHistogram()
            }
        }
        update()
    }

    fun cancel() {
        job?.cancel()
    }

    override fun count(from: Int, to: Int, severity: Int): Int {
        val end = minOf(to, summarized)
        if (from >= end) return 0
        val blocks = if (severity == SEVERITY_ERROR) errors else warnings
        var sum = 0
        for (block in (from ushr shift)..((end - 1) ushr shift)) sum += blocks[block]
        return sum
    }

    override fun first(from: Int, to: Int, severity: Int): Int {
        // Whole blocks, as [count] answers for them: the marked row may be just outside the range.
        val start = from ushr shift shl shift
        val end = minOf(((to - 1) ushr shift) + 1 shl shift, summarized)
        for (row in start..<end) if (severityOf(records.level(model.recordAtIndex(row))) == severity) return row
        return -1
    }

    private fun severityOf(level: Level?) = when (level) {
        Level.ERROR, Level.FATAL -> SEVERITY_ERROR
        Level.WARN -> SEVERITY_WARN
        else -> 0
    }

    private fun resetHistogram() {
        counted = 0
        if (!hasTimestamps) return
        val rows = model.totalRowCount
        val from = probeTime(0..<minOf(RANGE_PROBE_ROWS, rows))
        val to = probeTime(rows - 1 downTo maxOf(0, rows - RANGE_PROBE_ROWS))
        histogram = TimeHistogram(from, maxOf(from, to))
    }

    /** The first timestamp of the shown [rows]; 0 if none has one. */
    private fun probeTime(rows: IntProgression): Long {
        for (row in rows) {
            val time = records.timestampNanos(model.recordAtIndex(row))
            if (time != Records.NO_TIMESTAMP) return time
        }
        return 0
    }

    private fun update() {
        onChanged()
        if (!hasLevels && !hasTimestamps || job?.isActive == true) return
        job = scope.launch {
            while (summarized < model.totalRowCount) {
                summarize(minOf(model.totalRowCount, summarized + STEP_ROWS))
                onChanged()
                yield()
            }
        }
    }

    private fun summarize(end: Int) {
        val histogram = histogram
        for (row in summarized..<end) {
            if (row ushr shift >= MAX_BLOCKS) coarsen()
            val block = row ushr shift
            // Blocks are reused: one may still hold what was there before a reset.
            if (row and ((1 shl shift) - 1) == 0) {
                warnings[block] = 0
                errors[block] = 0
            }
            val record = model.recordAtIndex(row)
            val level = if (hasLevels) records.level(record) else null
            when (severityOf(level)) {
                SEVERITY_ERROR -> errors[block]++
                SEVERITY_WARN -> warnings[block]++
            }
            if (histogram != null && row >= counted) {
                val time = records.timestampNanos(record)
                if (time != Records.NO_TIMESTAMP) histogram.add(time, level)
                lastTime = time
                lastLevel = level
                counted = row + 1
            }
        }
        summarized = end
    }

    private fun coarsen() {
        for (i in 0..<MAX_BLOCKS / 2) {
            warnings[i] = warnings[2 * i] + warnings[2 * i + 1]
            errors[i] = errors[2 * i] + errors[2 * i + 1]
        }
        warnings.fill(0, MAX_BLOCKS / 2)
        errors.fill(0, MAX_BLOCKS / 2)
        shift++
    }
}
