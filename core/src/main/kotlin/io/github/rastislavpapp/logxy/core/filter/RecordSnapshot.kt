package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.index.LongChunkList
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.parse.RecordFormat
import java.nio.channels.FileChannel

/**
 * The first [count] records of a log, taken on the thread that owns it and readable from any thread:
 * records below [count] never change, appended ones are not seen.
 */
internal abstract class RecordSnapshot(
    val schema: Schema,
    val count: Int,
    /** File offset where the last record ends. */
    val end: Long,
    val channel: FileChannel,
) {
    /** Dictionary texts of LEVEL and DICT fields, by id. */
    abstract fun dictionaryTexts(field: Int): List<String>

    open val levels: LevelMapping get() = LevelMapping.DEFAULT

    open fun formatTimestamp(field: Int, nanos: Long): String = nanos.toString()

    /** Walks records, cheapest in increasing order; one per thread. */
    abstract fun cursor(): RecordCursor
}

internal interface RecordCursor {
    fun moveTo(record: Int)
    val start: Long
    val end: Long
    fun timestamp(field: Int): Long
    fun levelId(field: Int): Int
    fun dictId(field: Int): Int
    fun spanStart(field: Int): Int

    /** [Columns.ABSENT_LENGTH], [Columns.TO_RECORD_END], or a length. */
    fun spanLength(field: Int): Int
}

internal class ParsedSnapshot(
    schema: Schema,
    count: Int,
    end: Long,
    channel: FileChannel,
    private val segments: List<Columns>,
    private val first: IntArray,
    private val texts: Array<List<String>?>,
    override val levels: LevelMapping,
    private val format: RecordFormat,
) : RecordSnapshot(schema, count, end, channel) {

    override fun dictionaryTexts(field: Int) = texts[field]!!

    override fun formatTimestamp(field: Int, nanos: Long) = format.formatTimestamp(field, nanos)

    override fun cursor(): RecordCursor = object : RecordCursor {
        private var segment = 0
        private var columns = segments.firstOrNull()
        private var local = 0
        private var record = 0

        override fun moveTo(record: Int) {
            this.record = record
            if (record < first[segment] || record >= first[segment] + columns!!.size) {
                segment = first.indexOfLast { it <= record && it >= 0 }
                while (record >= first[segment] + segments[segment].size) segment++
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
        override fun spanLength(field: Int) = columns!!.lengths[field]!![local]
    }
}

/** A raw log: one record per line, whose only field is the line itself. */
internal class LineSnapshot(
    count: Int,
    end: Long,
    channel: FileChannel,
    private val starts: LongChunkList,
) : RecordSnapshot(Schema.RAW, count, end, channel) {

    override fun dictionaryTexts(field: Int) = throw UnsupportedOperationException("Raw logs have no dictionaries")

    override fun cursor(): RecordCursor = object : RecordCursor {
        private var record = 0

        override fun moveTo(record: Int) {
            this.record = record
        }

        override val start get() = starts[record]
        override val end get() = if (record + 1 < count) starts[record + 1] else this@LineSnapshot.end
        override fun timestamp(field: Int) = Columns.ABSENT_TIMESTAMP
        override fun levelId(field: Int) = Columns.MAX_LEVEL_ID + 1
        override fun dictId(field: Int) = -1
        override fun spanStart(field: Int) = 0
        override fun spanLength(field: Int) = Columns.TO_RECORD_END
    }
}
