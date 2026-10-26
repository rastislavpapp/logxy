package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.index.ByteChunkList
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.query.QueryCompiler
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The row highlight of each record of a load, as one byte per record: the index of the first row highlight the
 * record matches, plus one, so 0 means none. Records past [size] are not evaluated yet. Use it on the session's
 * thread.
 */
internal class RecordHighlights {

    private var indexes = ByteChunkList()

    val size: Int get() = indexes.size

    /** The index of [record]'s row highlight; -1 for none, also for a record not evaluated yet. */
    fun of(record: Int): Int = if (record < indexes.size) (indexes[record].toInt() and BYTE_MASK) - 1 else -1

    /** Replaces the row highlights from the first record of [part] on, which is at most [size], with its own. */
    fun replace(part: HighlightsPart) {
        val from = part.from
        require(from <= size) { "Part from $from, size $size" }
        if (from == 0) {
            indexes = part.indexes
            return
        }
        while (indexes.size > from) indexes.removeLast()
        indexes.addAll(part.indexes)
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}

/**
 * The row highlights of the records from [from] on, as [RecordHighlights] keeps them, for the records the
 * evaluation covered: [recordCount] records and [indexedSize] bytes of the files.
 */
internal class HighlightsPart(val from: Int, val indexes: ByteChunkList, val recordCount: Int, val indexedSize: Long)

/**
 * The row highlights compiled for one load's records, in order: null for one that does not fit them, such as one on
 * a field they lack, or one whose query has only empty values.
 */
internal fun compileHighlights(queries: List<String>, compiler: QueryCompiler): List<Filter?> = queries.map { text ->
    val filter = try {
        val query = QueryParser.parse(text)
        compiler.compile(query)
    } catch (_: QueryException) {
        return@map null
    }
    if (filter == Filter.And(emptyList())) null else filter
}

/**
 * Prepares [filters], row highlights from [compileHighlights], for the records from [from] on; null for one that
 * is null or does not prepare, such as one with an invalid regex. Call on the thread that owns the records.
 */
internal fun prepareHighlights(engine: RecordFilter, filters: List<Filter?>, from: Int): List<PreparedFilter?> =
    filters.map { filter ->
        try {
            filter?.let { engine.prepare(it, from) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

/**
 * Runs [evaluations], from [prepareHighlights] for the records from [from] on, one after the other, and gives each
 * record the first row highlight it matches. Null if none of them runs. Cancelling the caller stops it.
 */
internal suspend fun runHighlights(from: Int, evaluations: List<PreparedFilter?>): HighlightsPart? {
    if (evaluations.all { it == null }) return null
    val results = evaluations.map { it?.run() }
    // Prepared at once, they cover the same records; the minimum only guards against a merge that differs.
    val covered = results.filterNotNull()
    val end = covered.minOf { it.recordCount }
    val indexedSize = covered.minOf { it.indexedSize }
    val indexes = withContext(Dispatchers.Default) {
        val indexes = ByteChunkList()
        repeat(end - from) { indexes.add(0) }
        // The last row highlight first, so that the first one a record matches is written over the others.
        for (i in results.indices.reversed()) {
            val rows = results[i]?.rows ?: continue
            val index = (i + 1).toByte()
            for (record in rows) {
                if (record < end) indexes[record - from] = index
            }
        }
        indexes
    }
    return HighlightsPart(from, indexes, end, indexedSize)
}

/**
 * Evaluates [queries], row highlights in order, over all records of [engine] as a session does; for measuring that.
 * Returns how many records the evaluation covered. Call on the thread that owns the records, whose [schema] the
 * queries are compiled for.
 */
suspend fun evaluateRowHighlights(engine: RecordFilter, schema: Schema, queries: List<String>, from: Int = 0): Int {
    val filters = compileHighlights(queries, QueryCompiler(schema))
    return runHighlights(from, prepareHighlights(engine, filters, from))?.recordCount ?: 0
}
