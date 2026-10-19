package io.github.rastislavpapp.logxy.core.merge

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.filter.ChunkListener
import io.github.rastislavpapp.logxy.core.filter.Condition
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.FilterResult
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.Narrowing
import io.github.rastislavpapp.logxy.core.filter.NumberCondition
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.PresenceCondition
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.filter.RecordTextCondition
import io.github.rastislavpapp.logxy.core.filter.TemplateCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextMatcher
import io.github.rastislavpapp.logxy.core.filter.TimeCondition
import io.github.rastislavpapp.logxy.core.filter.VariableCondition
import io.github.rastislavpapp.logxy.core.template.RowOrigin
import io.github.rastislavpapp.logxy.core.template.TemplateCounts
import io.github.rastislavpapp.logxy.core.template.VariableSeries
import io.github.rastislavpapp.logxy.core.template.VariableValues
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

    private val origin = object : RowOrigin {
        override fun part(row: Int) = log.part(row)
        override fun record(row: Int) = log.record(row)
    }

    override fun prepare(filter: Filter, from: Int, within: Narrowing?): PreparedFilter {
        val count = log.recordCount
        val start = from.coerceIn(0, count)
        val indexedSize = log.indexedSize
        // Records a part got but that are not merged yet are left out.
        val merged = IntArray(log.parts.size) { log.mergedCount(it) }
        val narrowings = within?.let { partNarrowings(it) }
        val evaluations = log.parts.indices.map { part ->
            engines[part].prepare(partFilter(filter, part), log.firstRecordFrom(part, start), narrowings?.get(part))
        }
        val weights = merged.map { it.toDouble() / maxOf(1, merged.sum()) }
        return object : PreparedFilter {
            // The parts' records are not the merge's: nothing is reported before the result.
            override suspend fun run(onChunk: ChunkListener?): FilterResult {
                val results = coroutineScope { evaluations.map { async { it.run() } }.awaitAll() }
                val rows = withContext(Dispatchers.Default) {
                    toPositions(results.map { it.rows }, merged, start, count)
                }
                return FilterResult(rows, start, count, indexedSize)
            }

            override val progress get() = evaluations.indices.sumOf { evaluations[it].progress * weights[it] }
        }
    }

    /** [within], whose merged positions are sorted, as one narrowing per part, on the part's own records. */
    private fun partNarrowings(within: Narrowing): List<Narrowing> {
        val sizes = IntArray(log.parts.size)
        for (i in 0..<within.size) {
            val position = within.records[i]
            if (position >= within.upTo) break
            sizes[log.part(position)]++
        }
        val records = List(log.parts.size) { IntArray(sizes[it]) }
        sizes.fill(0)
        for (i in 0..<within.size) {
            val position = within.records[i]
            if (position >= within.upTo) break
            val part = log.part(position)
            records[part][sizes[part]++] = log.record(position)
        }
        return log.parts.indices.map { Narrowing(records[it], sizes[it], log.firstRecordFrom(it, within.upTo)) }
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

    // Templates are counted per part and joined by their text.
    override suspend fun countTemplates(rows: IntArray?, rowCount: Int): TemplateCounts {
        val snapshots = log.parts.map { it.filterSnapshot() }
        return TemplateCounts.count(engines.map { it.templates }, snapshots, rows, rowCount, origin)
    }

    override val templateProgress get() = engines.sumOf { it.templates.progress } / engines.size

    override suspend fun variableSeries(rows: IntArray?, rowCount: Int, template: String, variables: IntArray) =
        VariableSeries.collect(
            engines.map { it.templates },
            log.parts.map { it.filterSnapshot() },
            rows,
            rowCount,
            origin,
            template,
            variables,
        )

    override suspend fun variableValues(rows: IntArray?, rowCount: Int, template: String, variable: Int) =
        VariableValues.count(
            engines.map { it.templates },
            log.parts.map { it.filterSnapshot() },
            rows,
            rowCount,
            origin,
            template,
            variable,
        )

    /**
     * The merged positions, in order, of each part's sorted [rows], leaving out records beyond its [merged]
     * count. Positions are distinct and in [start, count), so a bitset sorts them in linear time.
     */
    private fun toPositions(rows: List<IntArray>, merged: IntArray, start: Int, count: Int): IntArray {
        val bits = LongArray((count - start + 63) / 64)
        var total = 0
        for ((part, partRows) in rows.withIndex()) {
            for (row in partRows) {
                if (row >= merged[part]) break
                val bit = log.position(part, row) - start
                bits[bit ushr 6] = bits[bit ushr 6] or (1L shl bit)
                total++
            }
        }
        val out = IntArray(total)
        var i = 0
        for (w in bits.indices) {
            var mask = bits[w]
            while (mask != 0L) {
                out[i++] = start + w * 64 + java.lang.Long.numberOfTrailingZeros(mask)
                mask = mask and (mask - 1)
            }
        }
        return out
    }

    /** [filter] in terms of [part]'s fields. */
    private fun partFilter(filter: Filter, part: Int): Filter = when (filter) {
        is Filter.And -> Filter.And(filter.parts.map { partFilter(it, part) })
        is Filter.Or -> Filter.Or(filter.parts.map { partFilter(it, part) })
        is Filter.Not -> Filter.Not(partFilter(filter.part, part))
        is RecordTextCondition -> filter
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
        is NumberCondition -> constant(condition.matches(Bytes.parseNumber(name)))
        is LevelCondition, is TimeCondition, is TemplateCondition, is VariableCondition, is RecordTextCondition ->
            constant(false)
    }

    private fun renamed(condition: Condition, field: String): Condition = when (condition) {
        is TextCondition -> condition.copy(field = field)
        is PresenceCondition -> condition.copy(field = field)
        is LevelCondition -> condition.copy(field = field)
        is TimeCondition -> condition.copy(field = field)
        is TemplateCondition -> condition.copy(field = field)
        is VariableCondition -> condition.copy(field = field)
        is NumberCondition -> condition.copy(field = field)
        is RecordTextCondition -> condition
    }

    private fun constant(matches: Boolean): Filter = if (matches) Filter.And(emptyList()) else Filter.Or(emptyList())

    private companion object {
        const val MISSING_FIELD = "\u0000missing"
    }
}
