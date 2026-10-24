package io.github.rastislavpapp.logxy.core.template

import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.log.ByteDictionary
import io.github.rastislavpapp.logxy.core.log.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How often each value of one template variable occurs in the counted rows of its template. */
class VariableValues internal constructor(
    /** Distinct values, most frequent first; equally frequent ones in the order first seen. */
    val values: List<String>,
    private val counts: IntArray,
    /** Rows of the template counted, with a value or without (a literal `{}`). */
    val records: Int,
    /** Rows whose value was first seen once the limit of different values was reached, so it is not in [values]. */
    val other: Int,
    /** How many values start with a number; [min], [max] and [average] are of those numbers, NaN if none do. */
    val numbers: Int,
    val min: Double,
    val max: Double,
    val average: Double,
) {
    /** Rows with the value at [index] of [values]. */
    fun count(index: Int): Int = counts[index]

    internal companion object {
        /** Different values kept per variable; ids can make no end of them. */
        const val MAX_VALUES = 100_000

        /**
         * Counts the variable values at template variable [variable], counted from 0, of those of the first
         * [rowCount] rows whose template is [template]. Row i is `rows[i]`, or i if [rows] is null. [origin] tells
         * which log each row is a record of, and which record; without it, row r is record r of the first log. A
         * log's snapshot and index are at its position in [snapshots] and [indexes]. Updates the indexes first.
         * Values first seen after [maxValues] different ones count as [other].
         */
        suspend fun count(
            indexes: List<TemplateIndex>,
            snapshots: List<RecordSnapshot>,
            rows: IntArray?,
            rowCount: Int,
            origin: RowOrigin?,
            template: String,
            variable: Int,
            maxValues: Int = MAX_VALUES,
        ): VariableValues = withContext(Dispatchers.Default) {
            val matching = coroutineScope {
                indexes.indices.map { async { indexes[it].matching(snapshots[it], setOf(template)) } }.awaitAll()
            }
            val messageFields = snapshots.map { it.schema.indexOf(Role.MESSAGE) }
            val tasks = maxOf(1, minOf(Runtime.getRuntime().availableProcessors(), rowCount / MIN_TASK_ROWS))
            val perTask = coroutineScope {
                (0..<tasks).map { task ->
                    async {
                        val tally = Tally(maxValues)
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
                            if (!matching[p].getOrElse(indexes[p].id(r)) { false }) continue
                            val snapshot = snapshots[p]
                            val reader = readers[p]
                                ?: MessageReader(snapshot, messageFields[p], snapshot.channels.forTask(task))
                                    .also { readers[p] = it }
                            tally.records++
                            reader.variableWord(r, variable, out, places, tally)
                        }
                        tally
                    }
                }.awaitAll()
            }
            join(perTask, maxValues)
        }

        /** The tallies of all tasks, in task order, so values are first seen in row order. */
        private fun join(tallies: List<Tally>, maxValues: Int): VariableValues {
            val all = Tally(maxValues)
            for (tally in tallies) {
                all.records += tally.records
                all.other += tally.other
                all.numbers += tally.numbers
                all.sum += tally.sum
                if (tally.numbers > 0) {
                    all.min = if (all.min.isNaN()) tally.min else minOf(all.min, tally.min)
                    all.max = if (all.max.isNaN()) tally.max else maxOf(all.max, tally.max)
                }
                for (id in 0..<tally.values.size) {
                    val key = tally.values.key(id)
                    all.add(key, 0, key.size, tally.counts[id])
                }
            }
            val order = (0..<all.values.size).sortedByDescending { all.counts[it] }
            return VariableValues(
                order.map { all.values.text(it) },
                IntArray(order.size) { all.counts[order[it]] },
                all.records,
                all.other,
                all.numbers,
                all.min,
                all.max,
                if (all.numbers > 0) all.sum / all.numbers else Double.NaN,
            )
        }

        private const val MIN_TASK_ROWS = 1 shl 16
        private const val CHECK_MASK = (1 shl 16) - 1
    }

    /** The values one task found, with their counts, growing as needed. */
    private class Tally(private val maxValues: Int) : MessageReader.WordVisitor {
        val values = ByteDictionary()
        var counts = IntArray(INITIAL_SIZE)
        var records = 0
        var other = 0
        var numbers = 0
        var sum = 0.0
        var min = Double.NaN
        var max = Double.NaN

        override fun visit(bytes: ByteArray, from: Int, to: Int) {
            add(bytes, from, to, 1)
            val number = Templates.leadingNumber(bytes, from, to)
            if (number.isNaN()) return
            numbers++
            sum += number
            if (min.isNaN() || number < min) min = number
            if (max.isNaN() || number > max) max = number
        }

        /** Counts the value `bytes[from, to)` [n] more times, as [other] once there are [maxValues] different ones. */
        fun add(bytes: ByteArray, from: Int, to: Int, n: Int) {
            val id = if (values.size < maxValues) values.idOf(bytes, from, to) else values.find(bytes, from, to)
            if (id < 0) {
                other += n
                return
            }
            if (id == counts.size) counts = counts.copyOf(counts.size * 2)
            counts[id] += n
        }

        private companion object {
            const val INITIAL_SIZE = 64
        }
    }
}
