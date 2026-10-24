package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.index.ByteChunkList
import io.github.rastislavpapp.logxy.core.index.IntChunkList
import io.github.rastislavpapp.logxy.core.index.LongChunkList
import io.github.rastislavpapp.logxy.core.parse.CompiledParser
import io.github.rastislavpapp.logxy.core.parse.MdcMap

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

    /** Lengths of SPAN fields; null for a field whose values always run to the record end, see [spanLength]. */
    private val lengths = arrayOfNulls<IntChunkList>(schema.fields.size)
    val dictionaries = arrayOfNulls<ByteDictionary>(schema.fields.size)

    /** Keys seen in each map field; null once one of its values was no map or there were too many keys. */
    val mapKeys = Array(schema.fields.size) { if (schema.fields[it].isMap) ByteDictionary() else null }

    /** Whether any record has a value of the field. */
    val present = BooleanArray(schema.fields.size)

    /** A DICT field whose values turned out mostly distinct, so a dictionary costs more than it saves; -1 if none. */
    var overflowed = -1
        private set

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
                    if (!field.toRecordEnd) lengths[i] = IntChunkList()
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

    /** Sets TIMESTAMP [field] of the current record to [nanos], noting whether timestamps are still in order. */
    fun setTimestamp(field: Int, nanos: Long) {
        longs[field]!![size - 1] = nanos
        present[field] = true
        if (nanos < lastTimestamp) timestampsSorted = false
        lastTimestamp = nanos
    }

    /**
     * Sets LEVEL [field] of the current record to the text `source[from, to)`. Ids are stored in a byte, so every
     * text from id [MAX_LEVEL_ID] on gets id [MAX_LEVEL_ID].
     */
    fun setLevel(field: Int, source: ByteArray, from: Int, to: Int) {
        val id = minOf(dictionaries[field]!!.idOf(source, from, to), MAX_LEVEL_ID)
        bytes[field]!![size - 1] = id.toByte()
        present[field] = true
    }

    /**
     * Sets a field the parser declares DICT to `source[from, to)`. If the log has turned the field into a SPAN,
     * see [ParsedLog], stores where the value is instead, relative to [lineFrom], where the record starts in
     * [source].
     */
    fun setText(field: Int, source: ByteArray, lineFrom: Int, from: Int, to: Int) {
        if (schema.fields[field].type == ColumnType.SPAN) {
            setSpan(field, from - lineFrom, to - from)
        } else {
            setDict(field, source, from, to)
        }
    }

    /**
     * Sets DICT [field] of the current record to `source[from, to)`; sets [overflowed] once the field's values
     * are mostly distinct.
     */
    fun setDict(field: Int, source: ByteArray, from: Int, to: Int) {
        val id = dictionaries[field]!!.idOf(source, from, to)
        ints[field]!![size - 1] = id
        present[field] = true
        if (id >= MIN_OVERFLOW_VALUES && id.toLong() * MAX_DISTINCT_SHARE >= size) overflowed = field
    }

    /**
     * Sets LEVEL [field] of the current record to an id already in [dictionaries], e.g. when copying a segment
     * with its ids remapped to shared ones.
     */
    fun setLevelId(field: Int, id: Int) {
        bytes[field]!![size - 1] = minOf(id, MAX_LEVEL_ID).toByte()
        present[field] = true
    }

    /**
     * Sets DICT [field] of the current record to an id already in [dictionaries], e.g. when copying a segment
     * with its ids remapped to shared ones.
     */
    fun setDictId(field: Int, id: Int) {
        ints[field]!![size - 1] = id
        present[field] = true
    }

    /** Notes the keys of the map in `source[from, to)`, if it is one, see [MdcMap]. */
    fun addMapKeys(field: Int, source: ByteArray, from: Int, to: Int) {
        val keys = mapKeys[field] ?: return
        val entries = MdcMap.entries(source, from, to)
        if (entries < 0) {
            mapKeys[field] = null
            return
        }
        MdcMap.forEachKey(source, MdcMap.from(entries), MdcMap.to(entries)) { keyFrom, keyTo ->
            keys.idOf(source, keyFrom, keyTo)
        }
        if (keys.size > schema.fields[field].map!!.maxKeys) mapKeys[field] = null
    }

    /** Notes the key `source[from, to)` of the map in [field]. */
    fun addMapKey(field: Int, source: ByteArray, from: Int, to: Int) {
        val keys = mapKeys[field] ?: return
        keys.idOf(source, from, to)
        if (keys.size > schema.fields[field].map!!.maxKeys) mapKeys[field] = null
    }

    /**
     * Sets SPAN [field] of the current record to the value [relativeStart] bytes after the record's start, which
     * is [length] bytes long, or [TO_RECORD_END] if it runs to the end of the record.
     */
    fun setSpan(field: Int, relativeStart: Int, length: Int) {
        ints[field]!![size - 1] = relativeStart
        present[field] = true
        val lengths = lengths[field]
        if (lengths != null) {
            lengths[size - 1] = length
        } else {
            require(length == TO_RECORD_END) { "${schema.fields[field].name} must run to the end of the record" }
        }
    }

    /**
     * Display text of a TIMESTAMP, LEVEL or DICT value of [record], "" if absent; ids are looked up in
     * [dictionary]. Null for SPAN fields, whose text is in the file.
     */
    fun valueText(field: Int, record: Int, parser: CompiledParser, dictionary: ByteDictionary?): String? =
        valueText(field, record, parser) { dictionary!!.text(it) }

    /** Like the other [valueText], with dictionary texts by id from [dictionaryText]. */
    inline fun valueText(field: Int, record: Int, parser: CompiledParser, dictionaryText: (Int) -> String): String? =
        when (schema.fields[field].type) {
            ColumnType.TIMESTAMP -> {
                val nanos = longs[field]!![record]
                if (nanos == ABSENT_TIMESTAMP) "" else parser.formatTimestamp(field, nanos)
            }
            ColumnType.LEVEL -> {
                val id = bytes[field]!![record].toInt() and 0xFF
                if (id > MAX_LEVEL_ID) "" else dictionaryText(id)
            }
            ColumnType.DICT -> {
                val id = ints[field]!![record]
                if (id < 0) "" else dictionaryText(id)
            }
            ColumnType.SPAN -> null
        }

    /**
     * The length in bytes of SPAN [field]'s value in [record]; [ABSENT_LENGTH] if the record has none, or
     * [TO_RECORD_END] if the value runs to the end of the record.
     */
    fun spanLength(field: Int, record: Int): Int = lengths[field]?.get(record) ?: TO_RECORD_END

    /** Drops the last record, e.g. one whose first line was unfinished, to parse it again once more bytes arrive. */
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

        // A DICT field overflows once its dictionary holds at least MIN_OVERFLOW_VALUES values, and one distinct
        // value per MAX_DISTINCT_SHARE records or more. About there, a dictionary entry (key bytes, list and
        // table slots) outweighs the 4 bytes per record a span costs extra.
        private const val MIN_OVERFLOW_VALUES = 10_000
        private const val MAX_DISTINCT_SHARE = 16

        /** Level ids are stored as unsigned bytes; 255 means absent. */
        const val MAX_LEVEL_ID = 254
    }
}
