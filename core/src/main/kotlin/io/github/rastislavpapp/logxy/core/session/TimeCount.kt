package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rows counted per parallel task. */
private const val TASK_ROWS = 1 shl 20

/** Counts the times of all records of [log], as a session does; for measuring it. Call on the thread that owns [log]. */
suspend fun countTimes(log: LogData): TimeHistogram = countAll(SingleLoad(log))

/** Counts the times of all records of [log], as a session does; for measuring it. Call on the thread that owns [log]. */
suspend fun countTimes(log: MergedLog): TimeHistogram = countAll(MergedLoad(log))

private suspend fun countAll(loaded: LoadedLog): TimeHistogram {
    val records = loaded.records
    val count = records.recordCount
    val from = if (count == 0) 0 else records.timestampNanos(0)
    val to = if (count == 0) 0 else records.timestampNanos(count - 1)
    val (before, after) = TimeCount(loaded, null, 0, count, count, from, maxOf(from, to)).run()
    return before.apply { addAll(after) }
}

/**
 * Counts the times of the shown rows from [from] until [to] (`rows[i]`, or the records themselves if
 * [rows] is null) into [TimeHistogram]s expected to cover [expectedFrom] to [expectedTo]: one of the
 * rows before [split], one of the rest. Created on the thread that owns [loaded]; [run] reads
 * snapshots of its parts on other threads, in parallel.
 */
internal class TimeCount(
    private val loaded: LoadedLog,
    private val rows: IntArray?,
    from: Int,
    to: Int,
    private val split: Int,
    private val expectedFrom: Long,
    private val expectedTo: Long,
    taskRows: Int = TASK_ROWS,
) {
    private val parts = loaded.parts
    private val snapshots = parts.map { it.filterSnapshot() }

    // Per part, by level id: the category its records are counted in.
    private val categories = snapshots.map { snapshot ->
        val categories = IntArray(256)
        val field = snapshot.schema.indexOf(Role.LEVEL)
        if (field >= 0) {
            snapshot.dictionaryTexts(field).take(Columns.MAX_LEVEL_ID + 1).forEachIndexed { id, text ->
                categories[id] = TimeHistogram.category(snapshot.levels.of(text))
            }
        }
        categories
    }

    /** Where the tasks start, in row order, which is about time order; the last is [to]. */
    private val bounds = ((from..<to step taskRows).toList() + split.coerceIn(from, to) + to).distinct().sorted().toIntArray()

    // All rows are each part's records in a range: read straight through, with no positions to look up.
    private val partBounds = if (rows == null) {
        Array(parts.size) { part -> IntArray(bounds.size) { loaded.firstRecordFrom(part, bounds[it]) } }
    } else {
        null
    }

    /**
     * Counts the rows before [split] and the rest. [onCounted] gets, as the tasks finish, the rows counted
     * so far from the first on, reaching all expected times, to show them filling up; on any thread.
     * Cancelling the calling coroutine stops it.
     */
    suspend fun run(onCounted: ((TimeHistogram) -> Unit)? = null): Pair<TimeHistogram, TimeHistogram> = withContext(Dispatchers.Default) {
        val tasks = bounds.size - 1
        val counted = arrayOfNulls<TimeHistogram>(tasks)
        val before = newHistogram()
        val after = newHistogram()
        var next = 0
        for (task in 0..<tasks) {
            launch {
                val histogram = count(task)
                synchronized(counted) {
                    counted[task] = histogram
                    val first = next
                    while (next < tasks) {
                        val done = counted[next] ?: break
                        (if (bounds[next] < split) before else after).addAll(done)
                        counted[next++] = null
                    }
                    if (onCounted != null && next > first && next < tasks) {
                        onCounted(before.copy().apply { addAll(after); cover(expectedFrom); cover(expectedTo) })
                    }
                }
            }
        }
        before to after
    }

    // All tasks start from the same bins, so adding their results up rarely has to move any.
    private fun newHistogram() = TimeHistogram(expectedFrom, expectedTo)

    private suspend fun count(task: Int): TimeHistogram {
        currentCoroutineContext().ensureActive()
        val histogram = newHistogram()
        val start = bounds[task]
        val end = bounds[task + 1]
        val rows = rows
        val origin = loaded.origin
        if (rows == null) {
            for (part in parts.indices) {
                val reader = Reader(part)
                for (record in partBounds!![part][task]..<partBounds[part][task + 1]) reader.addTo(histogram, record)
            }
        } else if (origin == null) {
            val reader = Reader(0)
            for (i in start..<end) reader.addTo(histogram, rows[i])
        } else {
            val readers = arrayOfNulls<Reader>(parts.size)
            for (i in start..<end) {
                val position = rows[i]
                val part = origin.part(position)
                (readers[part] ?: Reader(part).also { readers[part] = it }).addTo(histogram, origin.record(position))
            }
        }
        return histogram
    }

    /** Reads the records of one part; one per task, as cursors are. */
    private inner class Reader(part: Int) {
        private val snapshot = snapshots[part]
        private val cursor = snapshot.cursor()
        private val timeField = snapshot.schema.indexOf(Role.TIMESTAMP)
        private val levelField = snapshot.schema.indexOf(Role.LEVEL)
        private val categories = this@TimeCount.categories[part]

        fun addTo(histogram: TimeHistogram, record: Int) {
            if (timeField < 0) return
            cursor.moveTo(record)
            val time = cursor.timestamp(timeField)
            if (time == Records.NO_TIMESTAMP) return
            histogram.add(time, if (levelField < 0) TimeHistogram.OTHER else categories[cursor.levelId(levelField)], 1)
        }
    }
}
