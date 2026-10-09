package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.index.ByteChunkList
import io.github.rastislavpapp.logxy.core.index.IntChunkList
import io.github.rastislavpapp.logxy.core.index.LongChunkList

/**
 * Field values of consecutive records, one primitive list per field. While a segment is being
 * parsed, [dictionaries] are local to it; [ParsedLog] later remaps the ids to shared ones.
 */
internal class Columns(val schema: Schema) {

    val starts = LongChunkList()
    val longs = arrayOfNulls<LongChunkList>(schema.fields.size)
    val bytes = arrayOfNulls<ByteChunkList>(schema.fields.size)

    /** Dictionary ids for DICT fields; start offsets relative to the record for SPAN fields. */
    val ints = arrayOfNulls<IntChunkList>(schema.fields.size)
    val lengths = arrayOfNulls<IntChunkList>(schema.fields.size)
    val dictionaries = arrayOfNulls<ByteDictionary>(schema.fields.size)

    /** False once a record's timestamp was earlier than the one before it. */
    var timestampsSorted = true
    private var lastTimestamp = Long.MIN_VALUE

    init {
        for ((i, field) in schema.fields.withIndex()) {
            when (field.type) {
                ColumnType.TIMESTAMP -> longs[i] = LongChunkList()
                ColumnType.LEVEL -> {
                    bytes[i] = ByteChunkList()
                    dictionaries[i] = ByteDictionary()
                }
                ColumnType.DICT -> {
                    ints[i] = IntChunkList()
                    dictionaries[i] = ByteDictionary()
                }
                ColumnType.SPAN -> {
                    ints[i] = IntChunkList()
                    lengths[i] = IntChunkList()
                }
            }
        }
    }

    val size get() = starts.size

    /** Starts a record with every field absent. */
    fun beginRecord(start: Long) {
        starts.add(start)
        for (i in schema.fields.indices) {
            longs[i]?.add(ABSENT_TIMESTAMP)
            bytes[i]?.add(ABSENT_LEVEL)
            ints[i]?.add(-1)
            lengths[i]?.add(ABSENT_LENGTH)
        }
    }

    fun setTimestamp(field: Int, nanos: Long) {
        longs[field]!![size - 1] = nanos
        if (nanos < lastTimestamp) timestampsSorted = false
        lastTimestamp = nanos
    }

    fun setLevel(field: Int, source: ByteArray, from: Int, to: Int) {
        val id = minOf(dictionaries[field]!!.idOf(source, from, to), MAX_LEVEL_ID)
        bytes[field]!![size - 1] = id.toByte()
    }

    fun setDict(field: Int, source: ByteArray, from: Int, to: Int) {
        ints[field]!![size - 1] = dictionaries[field]!!.idOf(source, from, to)
    }

    fun setLevelId(field: Int, id: Int) {
        bytes[field]!![size - 1] = minOf(id, MAX_LEVEL_ID).toByte()
    }

    fun setDictId(field: Int, id: Int) {
        ints[field]!![size - 1] = id
    }

    /** [length] may be [TO_RECORD_END]. */
    fun setSpan(field: Int, relativeStart: Int, length: Int) {
        ints[field]!![size - 1] = relativeStart
        lengths[field]!![size - 1] = length
    }

    fun removeLast() {
        starts.removeLast()
        for (i in schema.fields.indices) {
            longs[i]?.removeLast()
            bytes[i]?.removeLast()
            ints[i]?.removeLast()
            lengths[i]?.removeLast()
        }
    }

    companion object {
        const val ABSENT_TIMESTAMP = Long.MIN_VALUE
        const val ABSENT_LEVEL: Byte = -1
        const val ABSENT_LENGTH = -1
        const val TO_RECORD_END = -2

        /** Level ids are stored as unsigned bytes; 255 means absent. */
        const val MAX_LEVEL_ID = 254
    }
}
