package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Blocks the rows are summarized in; blocks cover more rows as rows are added. */
private const val MAX_BLOCKS = 1 shl 16

/** Rows summarized before the UI thread handles other events again. */
private const val STEP_ROWS = 1 shl 18

const val SEVERITY_WARN = 1
const val SEVERITY_ERROR = 2

/**
 * The most severe level of each block of a table's shown rows, for marks along its scroll bar. Built
 * in steps on the UI thread, which owns the records; [onChanged] is called as it gets further.
 */
class LevelMarks(
    private val model: LogTableModel,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) : RowMarks {

    private val hasLevels = model.records.schema.indexOf(Role.LEVEL) >= 0
    private val blocks = ByteArray(MAX_BLOCKS)
    private var shift = 0
    private var summarized = 0
    private var job: Job? = null

    /** All shown rows changed, e.g. by a new filter result. */
    fun reset() {
        shift = 0
        while (model.totalRowCount ushr shift >= MAX_BLOCKS) shift++
        summarized = 0
        update()
    }

    /** Shown rows from [row] on changed, or were added. */
    fun changedFrom(row: Int) {
        summarized = minOf(summarized, row ushr shift shl shift)
        update()
    }

    fun cancel() {
        job?.cancel()
    }

    override fun severity(from: Int, to: Int): Int {
        val end = minOf(to, summarized)
        if (from >= end) return 0
        var max = 0
        for (block in (from ushr shift)..((end - 1) ushr shift)) max = maxOf(max, blocks[block].toInt())
        return max
    }

    override fun first(from: Int, to: Int, severity: Int): Int {
        // Whole blocks, as [severity] answers for them: the marked row may be just outside the range.
        val start = from ushr shift shl shift
        val end = minOf(((to - 1) ushr shift) + 1 shl shift, summarized)
        for (row in start..<end) if (severityOf(row) == severity) return row
        return -1
    }

    private fun severityOf(row: Int) = when (model.records.level(model.recordAtIndex(row))) {
        Level.ERROR, Level.FATAL -> SEVERITY_ERROR
        Level.WARN -> SEVERITY_WARN
        else -> 0
    }

    private fun update() {
        onChanged()
        if (!hasLevels || job?.isActive == true) return
        job = scope.launch {
            while (summarized < model.totalRowCount) {
                summarize(minOf(model.totalRowCount, summarized + STEP_ROWS))
                onChanged()
                yield()
            }
        }
    }

    private fun summarize(end: Int) {
        for (row in summarized..<end) {
            if (row ushr shift >= MAX_BLOCKS) coarsen()
            val block = row ushr shift
            // Blocks are reused: one may still hold what was there before a reset.
            if (row and ((1 shl shift) - 1) == 0) blocks[block] = 0
            val severity = severityOf(row)
            if (severity > blocks[block]) blocks[block] = severity.toByte()
        }
        summarized = end
    }

    private fun coarsen() {
        for (i in 0..<MAX_BLOCKS / 2) blocks[i] = maxOf(blocks[2 * i], blocks[2 * i + 1])
        blocks.fill(0, MAX_BLOCKS / 2)
        shift++
    }
}
