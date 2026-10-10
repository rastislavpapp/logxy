package io.github.rastislavpapp.logxy.core.merge

import io.github.rastislavpapp.logxy.core.filter.Condition
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.FilterResult
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.PresenceCondition
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextMatcher
import io.github.rastislavpapp.logxy.core.filter.TimeCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Filters a [MergedLog]: each part by its own [FilterEngine] (with its own cache), on the part's field
 * names, then the matches are mapped to merged positions. Conditions on the source field are decided
 * per part, as the whole part matches or not.
 */
class MergedFilter(private val log: MergedLog) : RecordFilter {

    private val engines = log.parts.map { FilterEngine(it) }

    override fun prepare(filter: Filter, from: Int): PreparedFilter {
        val count = log.recordCount
        val start = from.coerceIn(0, count)
        val indexedSize = log.indexedSize
        // Records a part got but that are not merged yet are left out.
        val merged = IntArray(log.parts.size) { log.mergedCount(it) }
        val evaluations = log.parts.indices.map { part ->
            engines[part].prepare(partFilter(filter, part), log.firstRecordFrom(part, start))
        }
        val weights = merged.map { it.toDouble() / maxOf(1, merged.sum()) }
        return object : PreparedFilter {
            override suspend fun run(): FilterResult {
                val results = coroutineScope { evaluations.map { async { it.run() } }.awaitAll() }
                val rows = withContext(Dispatchers.Default) {
                    mergeSorted(results.mapIndexed { part, result -> toPositions(part, result.rows, merged[part]) })
                }
                return FilterResult(rows, start, count, indexedSize)
            }

            override val progress get() = evaluations.indices.sumOf { evaluations[it].progress * weights[it] }
        }
    }

    // The last record of every part may still grow; the earliest of them decides.
    override fun changedFrom(coveredRecords: Int): Int {
        var from = coveredRecords
        for (part in log.parts.indices) {
            val firstAfter = log.firstRecordFrom(part, coveredRecords)
            if (firstAfter > 0) from = minOf(from, log.position(part, firstAfter - 1))
        }
        return from
    }

    private fun toPositions(part: Int, rows: IntArray, merged: Int): IntArray {
        var count = 0
        while (count < rows.size && rows[count] < merged) count++
        return IntArray(count) { log.position(part, rows[it]) }
    }

    /** One sorted array of sorted [lists]; there are few of them. */
    private fun mergeSorted(lists: List<IntArray>): IntArray {
        val out = IntArray(lists.sumOf { it.size })
        val next = IntArray(lists.size)
        for (i in out.indices) {
            var best = -1
            for (list in lists.indices) {
                if (next[list] < lists[list].size && (best < 0 || lists[list][next[list]] < lists[best][next[best]])) best = list
            }
            out[i] = lists[best][next[best]++]
        }
        return out
    }

    /** [filter] in terms of [part]'s fields. */
    private fun partFilter(filter: Filter, part: Int): Filter = when (filter) {
        is Filter.And -> Filter.And(filter.parts.map { partFilter(it, part) })
        is Filter.Or -> Filter.Or(filter.parts.map { partFilter(it, part) })
        is Filter.Not -> Filter.Not(partFilter(filter.part, part))
        is Condition -> {
            val field = log.schema.fields.indexOfFirst { it.name == filter.field }
            if (field == MergedSchema.SOURCE) {
                sourceCondition(filter, log.names[part])
            } else {
                val partField = if (field < 0) -1 else log.partField(part, field)
                // A field the part lacks is absent in all its records; the engine treats unknown names so.
                renamed(filter, if (partField < 0) MISSING_FIELD else log.parts[part].schema.fields[partField].name)
            }
        }
    }

    private fun sourceCondition(condition: Condition, name: String): Filter = when (condition) {
        is TextCondition -> constant(TextMatcher(condition).matches(name))
        is PresenceCondition -> constant(condition.present)
        is LevelCondition, is TimeCondition -> constant(false)
    }

    private fun renamed(condition: Condition, field: String): Condition = when (condition) {
        is TextCondition -> condition.copy(field = field)
        is PresenceCondition -> condition.copy(field = field)
        is LevelCondition -> condition.copy(field = field)
        is TimeCondition -> condition.copy(field = field)
    }

    private fun constant(matches: Boolean): Filter = if (matches) Filter.And(emptyList()) else Filter.Or(emptyList())

    private companion object {
        const val MISSING_FIELD = "\u0000missing"
    }
}
