package io.github.rastislavpapp.logxy.core.session

import java.util.TreeMap

/**
 * The records a search over the first [recordCount] records found so far, as the chunks of records it has decided,
 * which arrive in no particular order. Used on one thread.
 */
internal class FoundChunks(private val recordCount: Int) {

    /** The matches among the records `[first, end)`. */
    private class Chunk(val first: Int, val end: Int, val records: IntArray)

    private val chunks = TreeMap<Int, Chunk>()

    /** Number of matches in the chunks decided so far. */
    var size = 0
        private set

    /** The records from 0 up to here are all decided; matches before it have their final position. */
    private var decidedUntil = 0

    /** Adds the matches [records] (sorted) among the records `[first, end)`. */
    fun add(first: Int, end: Int, records: IntArray) {
        chunks[first] = Chunk(first, end, records)
        size += records.size
        while (true) {
            val next = chunks[decidedUntil] ?: break
            decidedUntil = next.end
        }
    }

    /** 1-based position of [record] among all matches, 0 if it is not one or its position is not final yet. */
    fun positionOf(record: Int): Int {
        if (record >= decidedUntil) return 0
        var before = 0
        for (chunk in chunks.values) {
            if (record < chunk.end) {
                val index = chunk.records.binarySearch(record)
                return if (index >= 0) before + index + 1 else 0
            }
            before += chunk.records.size
        }
        return 0
    }

    /**
     * The match after [current], or before it unless [forward], wrapping around; with [inclusive], [current] itself
     * counts. -1 if there is none, [NOT_YET_FOUND] if a chunk that may hold it is not decided yet: a match is only
     * certain when every chunk between [current] and it is decided.
     */
    fun next(current: Int, forward: Boolean, inclusive: Boolean): Int {
        val start = chunkAt(current) ?: return NOT_YET_FOUND
        val index = lowerBound(start.records, if (forward == inclusive) current else current + 1)
        val here = if (forward) index else index - 1
        if (here in start.records.indices) return start.records[here]
        var chunk = start
        while (!isLast(chunk, forward)) {
            chunk = neighbour(chunk, forward) ?: return NOT_YET_FOUND
            if (chunk.records.isNotEmpty()) return edge(chunk, forward)
        }
        // Past the end: wrap to the far end and on to the chunk of [current], whose other side is left.
        chunk = (if (forward) chunks[0] else chunkAt(recordCount - 1)) ?: return NOT_YET_FOUND
        while (true) {
            if (chunk.records.isNotEmpty()) return edge(chunk, forward)
            if (chunk === start) return -1
            chunk = neighbour(chunk, forward) ?: return NOT_YET_FOUND
        }
    }

    private fun isLast(chunk: Chunk, forward: Boolean) = if (forward) chunk.end >= recordCount else chunk.first == 0

    /** The chunk decided that holds [record]; null if it is not decided yet. */
    private fun chunkAt(record: Int): Chunk? {
        val chunk = chunks.floorEntry(record)?.value ?: return null
        return if (record < chunk.end) chunk else null
    }

    private fun neighbour(chunk: Chunk, forward: Boolean): Chunk? =
        if (forward) chunks[chunk.end] else chunkAt(chunk.first - 1)

    private fun edge(chunk: Chunk, forward: Boolean) = if (forward) chunk.records.first() else chunk.records.last()

    /** The index of the first of [records] that is [key] or more. */
    private fun lowerBound(records: IntArray, key: Int): Int {
        val found = records.binarySearch(key)
        return if (found >= 0) found else -found - 1
    }

    /** All matches so far, in order. */
    fun toArray(): IntArray {
        val all = IntArray(size)
        var i = 0
        for (chunk in chunks.values) {
            System.arraycopy(chunk.records, 0, all, i, chunk.records.size)
            i += chunk.records.size
        }
        return all
    }
}
