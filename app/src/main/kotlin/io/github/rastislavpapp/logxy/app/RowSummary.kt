package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.session.TimeHistogram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Rows counted before the UI thread handles other events again. */
private const val STEP_ROWS = 1 shl 18

/** Rows at each end of the shown rows looked at for the times they cover. */
private const val RANGE_PROBE_ROWS = 1000

/**
 * The [histogram] of the times of a table's shown rows, which must have timestamps. Built in steps on
 * the UI thread, which owns the records; [onChanged] is called as it gets further.
 */
class RowSummary(
    private val model: LogTableModel,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {

    private val records = model.records
    private val hasLevels = records.schema.indexOf(Role.LEVEL) >= 0
    private var job: Job? = null

    var histogram = newHistogram()
        private set

    // Rows counted; the last one is kept, as appends may change it.
    private var counted = 0
    private var lastTime = Records.NO_TIMESTAMP
    private var lastLevel: Level? = null

    /** All shown rows changed, e.g. by a new filter result. */
    fun reset() {
        histogram = newHistogram()
        counted = 0
        update()
    }

    /** Shown rows from [row] on changed, or were added. */
    fun changedFrom(row: Int) {
        if (row < counted) {
            if (row == counted - 1) {
                if (lastTime != Records.NO_TIMESTAMP) histogram.remove(lastTime, lastLevel)
                counted = row
            } else {
                // Rows further back are not kept; rare enough to count all again.
                return reset()
            }
        }
        update()
    }

    fun cancel() {
        job?.cancel()
    }

    private fun newHistogram(): TimeHistogram {
        val rows = model.totalRowCount
        val from = probeTime(0..<minOf(RANGE_PROBE_ROWS, rows))
        val to = probeTime(rows - 1 downTo maxOf(0, rows - RANGE_PROBE_ROWS))
        return TimeHistogram(from, maxOf(from, to))
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
        if (job?.isActive == true) return
        job = scope.launch {
            while (counted < model.totalRowCount) {
                count(minOf(model.totalRowCount, counted + STEP_ROWS))
                onChanged()
                yield()
            }
        }
    }

    private fun count(end: Int) {
        val histogram = histogram
        for (row in counted..<end) {
            val record = model.recordAtIndex(row)
            val level = if (hasLevels) records.level(record) else null
            val time = records.timestampNanos(record)
            if (time != Records.NO_TIMESTAMP) histogram.add(time, level)
            lastTime = time
            lastLevel = level
        }
        counted = end
    }
}
