package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.readFully
import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.LogData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections

/**
 * The records from [from] on that a filter lets through, in order, out of the first [recordCount]
 * records, which ended at [indexedSize] bytes of the file.
 */
class FilterResult(val rows: IntArray, val from: Int, val recordCount: Int, val indexedSize: Long)

/** Evaluates [Filter]s over [io.github.rastislavpapp.logxy.core.log.Records]. Use it on the thread that owns them. */
interface RecordFilter {
    /**
     * Prepares [filter] for the records from [from] on, e.g. only appended ones. Throws
     * IllegalArgumentException for an invalid regex.
     */
    fun prepare(filter: Filter, from: Int = 0): PreparedFilter

    /**
     * The first record whose result may differ from a result computed when there were [coveredRecords]
     * records: records that were still growing then (the last one of a log) and everything after.
     */
    fun changedFrom(coveredRecords: Int): Int
}

/** A filter ready to run; [run] may be called on any thread. */
fun interface PreparedFilter {
    /** Computes the matching records; cancelling the calling coroutine stops it. */
    suspend fun run(): FilterResult
}

/**
 * Evaluates [Filter]s over one log into bitsets, one bit per record. Dictionary fields test each
 * distinct value once; text in the file is only read for records the cheaper conditions left open.
 * Leaf results are cached per condition, so changing one condition or appending records only
 * computes what is new.
 */
class FilterEngine(private val log: LogData, private val maxCacheBytes: Long = DEFAULT_CACHE_BYTES) : RecordFilter {

    /** Bits of one condition for the [coverage] rows it was computed for; other bits are unknown. */
    private class Entry(val bits: LongArray, val coverage: LongArray) {
        val bytes get() = (bits.size + coverage.size) * 8L
    }

    // In access order, so the conditions used least recently are dropped first.
    private val cache: MutableMap<Condition, Entry> = Collections.synchronizedMap(LinkedHashMap(16, 0.75f, true))

    internal val cachedConditions get() = cache.size
    @Volatile
    private var cachedLevels: LevelMapping? = null

    /**
     * Snapshots the log for [filter], to evaluate records from [from] on (e.g. only appended ones).
     * Call on the thread that owns the log; the returned evaluation may run on any thread. Throws
     * IllegalArgumentException for an invalid regex.
     */
    override fun prepare(filter: Filter, from: Int): Evaluation {
        val snapshot = log.filterSnapshot()
        if (snapshot.levels != cachedLevels) {
            cache.keys.removeIf { it is LevelCondition }
            cachedLevels = snapshot.levels
        }
        return Evaluation(compile(filter, snapshot), snapshot, from.coerceIn(0, snapshot.count))
    }

    // Only the last record of a log grows.
    override fun changedFrom(coveredRecords: Int) = maxOf(0, coveredRecords - 1)

    /** Caches [entry], dropping the least recently used others while the cache is over [maxCacheBytes]. */
    private fun store(condition: Condition, entry: Entry) = synchronized(cache) {
        cache[condition] = entry
        var bytes = cache.values.sumOf { it.bytes }
        val oldest = cache.values.iterator()
        while (bytes > maxCacheBytes && cache.size > 1) {
            bytes -= oldest.next().bytes
            oldest.remove()
        }
    }

    private fun compile(filter: Filter, snapshot: RecordSnapshot): Node = when (filter) {
        is Filter.And -> AndNode(filter.parts.map { compile(it, snapshot) }.sortedBy { it.cost })
        is Filter.Or -> OrNode(filter.parts.map { compile(it, snapshot) }.sortedBy { it.cost })
        is Filter.Not -> NotNode(compile(filter.part, snapshot))
        is Condition -> Leaf.of(filter, snapshot)
    }

    inner class Evaluation internal constructor(
        private val root: Node,
        private val snapshot: RecordSnapshot,
        private val from: Int,
    ) : PreparedFilter {
        private val words = (snapshot.count + 63) / 64

        /** The records evaluated: from [from] to the end of the snapshot. */
        private val all = LongArray(words).also { bits ->
            for (w in from / 64..<words) bits[w] = -1L
            if (from % 64 != 0) bits[from / 64] = -1L shl (from % 64)
            if (snapshot.count % 64 != 0) bits[words - 1] = bits[words - 1] and (1L shl (snapshot.count % 64)) - 1
        }

        override suspend fun run(): FilterResult = withContext(Dispatchers.Default) {
            FilterResult(rows(evaluate(root, null)), from, snapshot.count, snapshot.end)
        }

        /** The records of [candidates] (all if null) that match [node]. */
        private suspend fun evaluate(node: Node, candidates: LongArray?): LongArray = when (node) {
            is AndNode -> {
                var result = candidates
                for (part in node.parts) result = evaluate(part, result)
                result ?: all
            }
            is OrNode -> {
                val result = LongArray(words)
                val open = (candidates ?: all).copyOf()
                for (part in node.parts) {
                    val matched = evaluate(part, open)
                    for (w in 0..<words) {
                        result[w] = result[w] or matched[w]
                        open[w] = open[w] and matched[w].inv()
                    }
                }
                result
            }
            is NotNode -> {
                val base = candidates ?: all
                val inner = evaluate(node.part, candidates)
                LongArray(words) { base[it] and inner[it].inv() }
            }
            is Leaf -> leaf(node, candidates ?: all)
        }

        private suspend fun leaf(leaf: Leaf, candidates: LongArray): LongArray {
            val entry = cache[leaf.condition]
            val need = LongArray(words)
            var anyNeeded = false
            for (w in 0..<words) {
                val covered = entry?.coverage?.getOrElse(w) { 0L } ?: 0L
                need[w] = candidates[w] and covered.inv()
                if (need[w] != 0L) anyNeeded = true
            }
            var bits = entry?.bits
            if (anyNeeded) {
                val computed = compute(leaf, need)
                val oldCoverage = entry?.coverage
                bits = LongArray(words) {
                    ((bits?.getOrElse(it) { 0L } ?: 0L) and (oldCoverage?.getOrElse(it) { 0L } ?: 0L)) or computed[it]
                }
                val coverage = LongArray(words) { (oldCoverage?.getOrElse(it) { 0L } ?: 0L) or need[it] }
                // The last record may still grow while tailing, so it is computed again every time.
                if (snapshot.count > 0) {
                    val last = snapshot.count - 1
                    coverage[last ushr 6] = coverage[last ushr 6] and (1L shl (last and 63)).inv()
                }
                store(leaf.condition, Entry(bits, coverage))
            }
            return LongArray(words) { (bits?.getOrElse(it) { 0L } ?: 0L) and candidates[it] }
        }

        private suspend fun compute(leaf: Leaf, need: LongArray): LongArray = coroutineScope {
            val out = LongArray(words)
            // Only chunks with records to test, e.g. just the appended ones while tailing.
            val chunks = (0..<words step CHUNK_WORDS).filter { first ->
                (first..<minOf(words, first + CHUNK_WORDS)).any { need[it] != 0L }
            }
            chunks.mapIndexed { task, first ->
                async {
                    ensureActive()
                    val cursor = snapshot.cursor()
                    val reader = if (leaf.readsFile) SpanReader(snapshot.channels.forTask(task), snapshot.end) else null
                    for (w in first..<minOf(words, first + CHUNK_WORDS)) {
                        var mask = need[w]
                        while (mask != 0L) {
                            val bit = java.lang.Long.numberOfTrailingZeros(mask)
                            cursor.moveTo(w * 64 + bit)
                            if (leaf.test(cursor, reader)) out[w] = out[w] or (1L shl bit)
                            mask = mask and (mask - 1)
                        }
                    }
                }
            }.awaitAll()
            out
        }

        private fun rows(bits: LongArray): IntArray {
            val rows = IntArray(bits.sumOf { java.lang.Long.bitCount(it) })
            var i = 0
            for (w in from / 64..<bits.size) {
                var mask = bits[w]
                while (mask != 0L) {
                    rows[i++] = w * 64 + java.lang.Long.numberOfTrailingZeros(mask)
                    mask = mask and (mask - 1)
                }
            }
            return rows
        }
    }

    private companion object {
        /** Records per parallel task: 4096 words of 64. */
        const val CHUNK_WORDS = 4096

        /** About 25 conditions on 40M records. */
        const val DEFAULT_CACHE_BYTES = 256L * 1024 * 1024
    }
}

internal sealed interface Node {
    /** Higher costs are evaluated later, on fewer candidates. */
    val cost: Int
}

private class AndNode(val parts: List<Node>) : Node {
    override val cost = parts.maxOfOrNull { it.cost } ?: 0
}

private class OrNode(val parts: List<Node>) : Node {
    override val cost = parts.maxOfOrNull { it.cost } ?: 0
}

private class NotNode(val part: Node) : Node {
    override val cost = part.cost
}

/** A condition resolved against a snapshot's schema, with everything dictionary-based precomputed. */
private class Leaf(
    val condition: Condition,
    override val cost: Int,
    val readsFile: Boolean,
    val test: (RecordCursor, SpanReader?) -> Boolean,
) : Node {

    companion object {
        fun of(condition: Condition, snapshot: RecordSnapshot): Leaf {
            val field = snapshot.schema.fields.indexOfFirst { it.name == condition.field }
            val type = snapshot.schema.fields.getOrNull(field)?.type
            fun constant(value: Boolean) = Leaf(condition, 0, false) { _, _ -> value }
            if (type == null) {
                // The log lacks the field: every record counts as having it absent.
                return constant(
                    (condition is TextCondition && TextMatcher(condition).matchesAbsent) ||
                        (condition is PresenceCondition && !condition.present),
                )
            }
            return when (condition) {
                is PresenceCondition -> Leaf(condition, 0, false) { c, _ -> present(c, field, type) == condition.present }
                is LevelCondition -> {
                    if (type != ColumnType.LEVEL) return constant(false)
                    val matching = snapshot.dictionaryTexts(field).map { snapshot.levels.of(it) in condition.levels }.toBooleanArray()
                    Leaf(condition, 0, false) { c, _ -> matching.getOrElse(c.levelId(field)) { false } }
                }
                is TimeCondition -> {
                    if (type != ColumnType.TIMESTAMP) return constant(false)
                    val from = condition.from ?: Long.MIN_VALUE + 1
                    val to = condition.to ?: Long.MAX_VALUE
                    Leaf(condition, 0, false) { c, _ -> c.timestamp(field) in from..to }
                }
                is TextCondition -> textLeaf(condition, snapshot, field, type)
            }
        }

        private fun textLeaf(condition: TextCondition, snapshot: RecordSnapshot, field: Int, type: ColumnType): Leaf {
            val matcher = TextMatcher(condition)
            val absent = matcher.matchesAbsent
            return when (type) {
                ColumnType.DICT -> {
                    val matching = snapshot.dictionaryTexts(field).map(matcher::matches).toBooleanArray()
                    Leaf(condition, 0, false) { c, _ ->
                        val id = c.dictId(field)
                        if (id < 0) absent else matching[id]
                    }
                }
                ColumnType.LEVEL -> {
                    val matching = snapshot.dictionaryTexts(field).map(matcher::matches).toBooleanArray()
                    Leaf(condition, 0, false) { c, _ -> matching.getOrElse(c.levelId(field)) { absent } }
                }
                ColumnType.TIMESTAMP -> Leaf(condition, 1, false) { c, _ ->
                    val nanos = c.timestamp(field)
                    if (nanos == Columns.ABSENT_TIMESTAMP) absent else matcher.matches(snapshot.formatTimestamp(field, nanos))
                }
                ColumnType.SPAN -> Leaf(condition, 2, true) { c, reader ->
                    val length = c.spanLength(field)
                    if (length == Columns.ABSENT_LENGTH) {
                        absent
                    } else {
                        val from = c.start + c.spanStart(field)
                        val to = if (length == Columns.TO_RECORD_END) c.end else from + length
                        val offset = reader!!.load(from, to)
                        var end = offset + (to - from).toInt()
                        if (length == Columns.TO_RECORD_END) end = Bytes.trimLineEnd(reader.buffer, offset, end)
                        matcher.matches(reader.buffer, offset, minOf(end, reader.loaded))
                    }
                }
            }
        }

        private fun present(c: RecordCursor, field: Int, type: ColumnType) = when (type) {
            ColumnType.TIMESTAMP -> c.timestamp(field) != Columns.ABSENT_TIMESTAMP
            ColumnType.LEVEL -> c.levelId(field) <= Columns.MAX_LEVEL_ID
            ColumnType.DICT -> c.dictId(field) >= 0
            ColumnType.SPAN -> c.spanLength(field) != Columns.ABSENT_LENGTH
        }
    }
}

/**
 * Reads file bytes for consecutive records through one buffer, refilled only when a record is not
 * in it, so scanning many records costs few large reads.
 */
internal class SpanReader(private val channel: FileChannel, private val fileEnd: Long) {
    var buffer = ByteArray(BUFFER_SIZE)
        private set
    private var bufferStart = -1L

    /** Number of valid bytes in [buffer]. */
    var loaded = 0
        private set

    /** Makes `[from, to)` available in [buffer] and returns its offset there. */
    fun load(from: Long, to: Long): Int {
        if (bufferStart >= 0 && from >= bufferStart && to <= bufferStart + loaded) return (from - bufferStart).toInt()
        val length = (to - from).toInt()
        if (length > buffer.size) buffer = ByteArray(length)
        loaded = channel.readFully(ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), fileEnd - from).toInt()), from)
        bufferStart = from
        return 0
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
    }
}
