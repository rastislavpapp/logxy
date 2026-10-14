package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.ReadChannels
import io.github.rastislavpapp.logxy.core.index.LongChunkList
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.parse.CompiledParser
import io.github.rastislavpapp.logxy.core.readFully
import java.nio.ByteBuffer

/**
 * The first [count] records of a log, taken on the thread that owns it and readable from any thread:
 * records below [count] never change, appended ones are not seen. The exception is the last record,
 * which grows while it is being written (continuation lines, an unfinished line).
 */
internal abstract class RecordSnapshot(
    val schema: Schema,
    val count: Int,
    /** File offset where the last record ends. */
    val end: Long,
    val channels: ReadChannels,
) {
    /** Dictionary texts of LEVEL and DICT fields, by id. */
    abstract fun dictionaryTexts(field: Int): List<String>

    /** How level texts map to levels at the time of the snapshot. */
    open val levels: LevelMapping get() = LevelMapping.DEFAULT

    /**
     * A TIMESTAMP value of [field] as the table shows it, so text conditions on timestamps match what the user
     * sees.
     */
    open fun formatTimestamp(field: Int, nanos: Long): String = nanos.toString()

    /** Walks records, cheapest in increasing order; one per thread. */
    abstract fun cursor(): RecordCursor

    /** [length] bytes of the file from [position]; fewer if the file ends before. */
    protected fun readBytes(position: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val read = channels.main.readFully(ByteBuffer.wrap(bytes), position)
        return if (read == length) bytes else bytes.copyOf(read)
    }

    /** [length] bytes of the file from [position], e.g. records as written; fewer if the file ends before. */
    fun read(position: Long, length: Int): ByteArray = readBytes(position, length)

    /** The whole value of [field] in [record], like [io.github.rastislavpapp.logxy.core.log.Records.fieldText]. */
    abstract fun fieldText(record: Int, field: Int): String
}

/**
 * The stored values of one record at a time, without reading the file. Moving forward is cheap; a filter
 * moves through the records it tests in increasing order. Not thread-safe.
 */
internal interface RecordCursor {
    /**
     * Makes [record], below the snapshot's count, the current one; the next record or a later one in the same
     * segment is cheapest.
     */
    fun moveTo(record: Int)

    /** The record moved to. */
    val record: Int

    /** File offset where the record starts. */
    val start: Long

    /** File offset where the record ends, line terminator included. */
    val end: Long

    /** Epoch nanoseconds of TIMESTAMP [field], or [Columns.ABSENT_TIMESTAMP]. */
    fun timestamp(field: Int): Long

    /** Dictionary id of LEVEL [field]'s text, above [Columns.MAX_LEVEL_ID] if absent. */
    fun levelId(field: Int): Int

    /** Dictionary id of DICT [field]'s value, negative if absent. */
    fun dictId(field: Int): Int

    /** Where SPAN [field]'s value starts, relative to [start]. */
    fun spanStart(field: Int): Int

    /** [Columns.ABSENT_LENGTH], [Columns.TO_RECORD_END], or a length. */
    fun spanLength(field: Int): Int
}

/**
 * A snapshot of a [ParsedLog]: its column segments up to [count] records, the [first] record of each, and the
 * dictionary [texts].
 */
internal class ParsedSnapshot(
    schema: Schema,
    count: Int,
    end: Long,
    channels: ReadChannels,
    private val segments: List<Columns>,
    private val first: IntArray,
    private val texts: Array<List<String>?>,
    override val levels: LevelMapping,
    private val parser: CompiledParser,
) : RecordSnapshot(schema, count, end, channels) {

    override fun dictionaryTexts(field: Int) = texts[field]!!

    override fun formatTimestamp(field: Int, nanos: Long) = parser.formatTimestamp(field, nanos)

    /** The segment holding [record]: the last one starting at or before it that is not empty. */
    private fun segmentOf(record: Int): Int {
        var low = 0
        var high = first.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (first[middle] <= record) low = middle else high = middle - 1
        }
        // Empty segments share their first record number with the next one; the search lands on the last of those.
        while (record >= first[low] + segments[low].size) low++
        return low
    }

    override fun fieldText(record: Int, field: Int): String {
        require(record in 0..<count) { "Record $record, count $count" }
        val segment = segmentOf(record)
        val end = if (record + 1 < count) recordStart(record + 1) else end
        val local = record - first[segment]
        return ParsedLog.fieldText(schema, segments[segment], local, field, parser, { texts[field]!![it] }) { start ->
            readBytes(start, minOf(end - start, Int.MAX_VALUE / 2L).toInt())
        }
    }

    private fun recordStart(record: Int): Long {
        val segment = segmentOf(record)
        return segments[segment].starts[record - first[segment]]
    }

    override fun cursor(): RecordCursor = object : RecordCursor {
        private var segment = 0
        private var columns = segments.firstOrNull()
        private var local = 0
        override var record = 0
            private set

        override fun moveTo(record: Int) {
            this.record = record
            if (record < first[segment] || record >= first[segment] + columns!!.size) {
                // Records are mostly walked in order: the next segment, or else a search.
                if (record < first[segment]) {
                    segment = segmentOf(record)
                } else {
                    var next = segment + 1
                    while (next < segments.size && segments[next].size == 0) next++
                    val inNext = next < segments.size && record < first[next] + segments[next].size
                    segment = if (inNext) next else segmentOf(record)
                }
                columns = segments[segment]
            }
            local = record - first[segment]
        }

        override val start get() = columns!!.starts[local]

        override val end: Long
            get() {
                if (record + 1 >= count) return this@ParsedSnapshot.end
                if (local + 1 < columns!!.size) return columns!!.starts[local + 1]
                var next = segment + 1
                while (segments[next].size == 0) next++
                return segments[next].starts[0]
            }

        override fun timestamp(field: Int) = columns!!.longs[field]!![local]
        override fun levelId(field: Int) = columns!!.bytes[field]!![local].toInt() and 0xFF
        override fun dictId(field: Int) = columns!!.ints[field]!![local]
        override fun spanStart(field: Int) = columns!!.ints[field]!![local]
        override fun spanLength(field: Int) = columns!!.spanLength(field, local)
    }
}

/** A raw log: one record per line, whose only field is the line itself. */
internal class LineSnapshot(
    count: Int,
    end: Long,
    channels: ReadChannels,
    private val starts: LongChunkList,
) : RecordSnapshot(Schema.RAW, count, end, channels) {

    override fun dictionaryTexts(field: Int) = throw UnsupportedOperationException("Raw logs have no dictionaries")

    /** Offset just past [line], its terminator included. */
    private fun lineEnd(line: Int) = if (line + 1 < count) starts[line + 1] else end

    override fun fieldText(record: Int, field: Int): String {
        require(record in 0..<count) { "Line $record, count $count" }
        val bytes = readBytes(starts[record], (lineEnd(record) - starts[record]).toInt())
        return String(bytes, 0, Bytes.trimLineEnd(bytes, 0, bytes.size), Charsets.UTF_8)
    }

    override fun cursor(): RecordCursor = object : RecordCursor {
        override var record = 0
            private set

        override fun moveTo(record: Int) {
            this.record = record
        }

        override val start get() = starts[record]
        override val end get() = lineEnd(record)
        override fun timestamp(field: Int) = Columns.ABSENT_TIMESTAMP
        override fun levelId(field: Int) = Columns.MAX_LEVEL_ID + 1
        override fun dictId(field: Int) = -1
        override fun spanStart(field: Int) = 0
        override fun spanLength(field: Int) = Columns.TO_RECORD_END
    }
}
