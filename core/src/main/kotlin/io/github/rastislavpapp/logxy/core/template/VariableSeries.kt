package io.github.rastislavpapp.logxy.core.template

import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The values of one variable of one template over time: per counted row of the template that has a time, the row,
 * its time in epoch nanoseconds and the variable's value (see [Templates.variableNumber]), NaN where the variable
 * holds no number. In row order. Variables read in one pass share their rows and times.
 */
class VariableSeries internal constructor(
    private val rows: IntArray,
    private val times: LongArray,
    private val values: DoubleArray,
    val size: Int,
) {
    /** Values that are numbers. */
    val numbers: Int

    /** Of the numbers; NaN if there are none. */
    val min: Double
    val max: Double
    val average: Double

    init {
        var numbers = 0
        var min = Double.NaN
        var max = Double.NaN
        var sum = 0.0
        for (i in 0..<size) {
            val value = values[i]
            if (value.isNaN()) continue
            if (numbers++ == 0 || value < min) min = value
            if (numbers == 1 || value > max) max = value
            sum += value
        }
        this.numbers = numbers
        this.min = min
        this.max = max
        average = if (numbers > 0) sum / numbers else Double.NaN
    }

    /** The row, as given to [collect]: a record of the shown log. */
    fun row(index: Int): Int = rows[index]

    fun time(index: Int): Long = times[index]

    fun value(index: Int): Double = values[index]

    /**
     * The index of the value from [from] to [to] (exclusive, epoch nanoseconds) closest to [target], the first in row
     * order of equally close ones; -1 if no number is in that time.
     */
    fun closest(from: Long, to: Long, target: Double): Int {
        var best = -1
        var distance = Double.POSITIVE_INFINITY
        for (i in 0..<size) {
            if (times[i] !in from..<to || values[i].isNaN()) continue
            val d = abs(values[i] - target)
            if (d < distance) {
                best = i
                distance = d
            }
        }
        return best
    }

    /**
     * Sums up the values in [count] slices of time of [width] nanoseconds each, the first starting at [from]; values
     * outside them are left out. Sorts each slice's numbers, so it takes a while for many values: call it off the
     * UI thread.
     */
    fun slices(from: Long, width: Long, count: Int): VariableSlices {
        val slices = VariableSlices(from, width, count)
        fun sliceOf(i: Int): Int = Math.floorDiv(times[i] - from, width).let { if (it in 0..<count) it.toInt() else -1 }
        // Where each slice's numbers start in one array of them all.
        val starts = IntArray(count + 1)
        for (i in 0..<size) {
            val slice = sliceOf(i)
            if (slice < 0) continue
            slices.records[slice]++
            if (values[i].isNaN()) slices.withoutNumber[slice]++ else starts[slice + 1]++
        }
        for (slice in 0..<count) starts[slice + 1] += starts[slice]
        val numbers = DoubleArray(starts[count])
        val next = starts.copyOf()
        for (i in 0..<size) {
            val slice = sliceOf(i)
            if (slice >= 0 && !values[i].isNaN()) numbers[next[slice]++] = values[i]
        }
        for (slice in 0..<count) {
            val start = starts[slice]
            val n = starts[slice + 1] - start
            if (n == 0) continue
            var sum = 0.0
            for (i in start..<start + n) sum += numbers[i]
            numbers.sort(start, start + n)
            // Nearest rank: the smallest value at least that share of the numbers is not above.
            fun percentile(percent: Int) = numbers[start + ((percent.toLong() * n + 99) / 100).toInt() - 1]
            slices.min[slice] = numbers[start]
            slices.p50[slice] = percentile(50)
            slices.p99[slice] = percentile(99)
            slices.max[slice] = numbers[start + n - 1]
            slices.sum[slice] = sum
            slices.average[slice] = sum / n
        }
        return slices
    }

    internal companion object {
        /**
         * Reads [rowCount] rows (`rows[i]`, or `i` if [rows] is null) whose template is [template]: each a record
         * of the log that [origin] tells (the first if null), whose snapshot and index are at that position in
         * [snapshots] and [indexes]. Indexes are updated first. Reads all [variables] in one pass, and returns their
         * values in that order.
         */
        suspend fun collect(
            indexes: List<TemplateIndex>,
            snapshots: List<RecordSnapshot>,
            rows: IntArray?,
            rowCount: Int,
            origin: RowOrigin?,
            template: String,
            variables: IntArray,
        ): List<VariableSeries> = withContext(Dispatchers.Default) {
            val matching = coroutineScope {
                indexes.indices.map { async { indexes[it].matching(snapshots[it], setOf(template)) } }.awaitAll()
            }
            val timeFields = snapshots.map { it.schema.indexOf(Role.TIMESTAMP) }
            val messageFields = snapshots.map { it.schema.indexOf(Role.MESSAGE) }
            val tasks = maxOf(1, minOf(Runtime.getRuntime().availableProcessors(), rowCount / MIN_TASK_ROWS))
            val perTask = coroutineScope {
                (0..<tasks).map { task ->
                    async {
                        val found = Found(variables.size)
                        val cursors = snapshots.map { it.cursor() }
                        val readers = arrayOfNulls<MessageReader>(snapshots.size)
                        val out = ByteArray(Templates.OUT_SIZE)
                        val places = IntArray(Templates.PLACES_SIZE)
                        val from = (rowCount.toLong() * task / tasks).toInt()
                        val to = (rowCount.toLong() * (task + 1) / tasks).toInt()
                        for (i in from..<to) {
                            if ((i and CHECK_MASK) == 0) ensureActive()
                            val row = rows?.get(i) ?: i
                            val p = origin?.part(row) ?: 0
                            val r = origin?.record(row) ?: row
                            if (timeFields[p] < 0 || !matching[p].getOrElse(indexes[p].id(r)) { false }) continue
                            val cursor = cursors[p]
                            cursor.moveTo(r)
                            val time = cursor.timestamp(timeFields[p])
                            if (time == Records.NO_TIMESTAMP) continue
                            val snapshot = snapshots[p]
                            val reader = readers[p]
                                ?: MessageReader(snapshot, messageFields[p], snapshot.channels.forTask(task))
                                    .also { readers[p] = it }
                            // Adding may grow the values, so they are taken after.
                            val at = found.add(row, time)
                            reader.variableNumbers(r, variables, at, found.values, out, places)
                        }
                        found
                    }
                }.awaitAll()
            }
            val size = perTask.sumOf { it.size }
            val shared = IntArray(size)
            val times = LongArray(size)
            var at = 0
            for (found in perTask) {
                found.rows.copyInto(shared, at, 0, found.size)
                found.times.copyInto(times, at, 0, found.size)
                at += found.size
            }
            variables.indices.map { s ->
                val values = DoubleArray(size)
                var i = 0
                for (found in perTask) {
                    for (j in 0..<found.size) values[i++] = found.values[j * variables.size + s]
                }
                VariableSeries(shared, times, values, size)
            }
        }

        private const val MIN_TASK_ROWS = 1 shl 16
        private const val CHECK_MASK = (1 shl 16) - 1
    }

    /** Rows, times and values found by one task, growing as needed; the values of each row's variables side by side. */
    private class Found(private val variables: Int) {
        var rows = IntArray(INITIAL_SIZE)
        var times = LongArray(INITIAL_SIZE)
        var values = DoubleArray(INITIAL_SIZE * variables)
        var size = 0

        /** Adds [row] at [time]; returns where its values go in [values]. */
        fun add(row: Int, time: Long): Int {
            if (size == times.size) {
                rows = rows.copyOf(size * 2)
                times = times.copyOf(size * 2)
                values = values.copyOf(size * 2 * variables)
            }
            rows[size] = row
            times[size] = time
            return size++ * variables
        }

        private companion object {
            const val INITIAL_SIZE = 1024
        }
    }
}
