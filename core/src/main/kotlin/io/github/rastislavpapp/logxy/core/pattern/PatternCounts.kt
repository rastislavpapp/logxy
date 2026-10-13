package io.github.rastislavpapp.logxy.core.pattern

import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Where a view's row comes from: which log of [PatternCounts.count]'s, and which record there. */
internal interface RowOrigin {
    fun part(row: Int): Int
    fun record(row: Int): Int
}

/** How many of the counted rows have each message template, in total and per level. */
class PatternCounts internal constructor(
    /** Templates, most frequent first; null stands for the records beyond [PatternIndex.MAX_TEMPLATES] distinct templates. */
    val templates: List<String?>,
    /** Per template, a count per [Level] by ordinal, then one for records without a level. */
    private val counts: IntArray,
    /** Rows counted. */
    val rows: Int,
) {
    private val totals = IntArray(templates.size) { t -> (0..<SLOTS).sumOf { counts[t * SLOTS + it] } }

    val size get() = templates.size

    fun total(index: Int): Int = totals[index]

    fun count(index: Int, level: Level): Int = counts[index * SLOTS + level.ordinal]

    /** Levels at least one counted row has, from least to most severe. */
    val levels: List<Level> = Level.entries.filter { level -> templates.indices.any { count(it, level) > 0 } }

    internal companion object {
        val LEVELS = Level.entries.size
        val SLOTS = LEVELS + 1

        val EMPTY = PatternCounts(emptyList(), IntArray(0), 0)

        /**
         * Counts [rowCount] rows: `rows[i]`, or `i` if [rows] is null. Each row is a record of the log that
         * [origin] tells (the first if null), whose snapshot and index are at that position in [snapshots] and
         * [indexes]. Indexes are updated first.
         */
        suspend fun count(
            indexes: List<PatternIndex>,
            snapshots: List<RecordSnapshot>,
            rows: IntArray?,
            rowCount: Int,
            origin: RowOrigin?,
        ): PatternCounts = withContext(Dispatchers.Default) {
            coroutineScope { indexes.indices.map { async { indexes[it].update(snapshots[it]) } }.awaitAll() }
            val texts = indexes.map { it.texts() }
            val levelFields = snapshots.map { s -> s.schema.indexOf(Role.LEVEL).takeIf { it >= 0 && s.schema.fields[it].type == ColumnType.LEVEL } ?: -1 }
            val levelOrdinals = snapshots.mapIndexed { p, s ->
                if (levelFields[p] < 0) IntArray(0) else s.dictionaryTexts(levelFields[p]).map { s.levels.of(it).ordinal }.toIntArray()
            }
            val tasks = maxOf(1, minOf(Runtime.getRuntime().availableProcessors(), rowCount / MIN_TASK_ROWS))
            val perTask = coroutineScope {
                (0..<tasks).map { task ->
                    async {
                        // Per part: counts by template id (the last slot for OTHER), SLOTS each.
                        val counts = arrayOfNulls<IntArray>(indexes.size)
                        val cursors = snapshots.map { it.cursor() }
                        val from = (rowCount.toLong() * task / tasks).toInt()
                        val to = (rowCount.toLong() * (task + 1) / tasks).toInt()
                        for (i in from..<to) {
                            if ((i and CHECK_MASK) == 0) ensureActive()
                            val row = rows?.get(i) ?: i
                            val p = origin?.part(row) ?: 0
                            val r = origin?.record(row) ?: row
                            val templateCount = texts[p].size
                            val id = indexes[p].id(r).let { if (it < 0 || it >= templateCount) templateCount else it }
                            val level = if (levelFields[p] < 0) {
                                LEVELS
                            } else {
                                val cursor = cursors[p]
                                cursor.moveTo(r)
                                levelOrdinals[p].getOrElse(cursor.levelId(levelFields[p])) { LEVELS }
                            }
                            val partCounts = counts[p] ?: IntArray((templateCount + 1) * SLOTS).also { counts[p] = it }
                            partCounts[id * SLOTS + level]++
                        }
                        counts
                    }
                }.awaitAll()
            }
            // Parts number templates each their own way, so they are joined by text.
            val joined = LinkedHashMap<String?, IntArray>()
            for ((p, partTexts) in texts.withIndex()) {
                for (task in perTask) {
                    val counts = task[p] ?: continue
                    for (id in 0..partTexts.size) {
                        var any = false
                        for (s in 0..<SLOTS) if (counts[id * SLOTS + s] != 0) any = true
                        if (!any) continue
                        val target = joined.getOrPut(partTexts.getOrNull(id)) { IntArray(SLOTS) }
                        for (s in 0..<SLOTS) target[s] += counts[id * SLOTS + s]
                    }
                }
            }
            val order = joined.entries.sortedByDescending { it.value.sum() }
            val counts = IntArray(order.size * SLOTS)
            order.forEachIndexed { i, entry -> entry.value.copyInto(counts, i * SLOTS) }
            PatternCounts(order.map { it.key }, counts, rowCount)
        }

        private const val MIN_TASK_ROWS = 1 shl 16
        private const val CHECK_MASK = (1 shl 16) - 1
    }
}
