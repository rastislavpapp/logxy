package io.github.rastislavpapp.logxy.core.template

import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.filter.SpanReader
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Encoding
import io.github.rastislavpapp.logxy.core.parse.Json
import java.nio.channels.FileChannel

/** Reads the templates of records' [field] (a message) from [snapshot]; one per thread. */
internal class MessageReader(private val snapshot: RecordSnapshot, private val field: Int, channel: FileChannel) {
    private val cursor = snapshot.cursor()
    private val type = snapshot.schema.fields.getOrNull(field)?.type
    private val json = snapshot.schema.fields.getOrNull(field)?.encoding == Encoding.JSON
    private val reader = if (type == ColumnType.SPAN) SpanReader(channel, snapshot.end) else null
    private val dictionary =
        if (type == ColumnType.DICT) arrayOfNulls<ByteArray>(snapshot.dictionaryTexts(field).size) else null
    private val unescaped = if (json) ByteArray(READ_LIMIT) else null

    /** The numbers of each dictionary text at [numbersSlots], side by side, once read; many records share a text. */
    private var dictionaryNumbers = DoubleArray(0)
    private val numbersRead = if (type == ColumnType.DICT) BooleanArray(dictionary!!.size) else null
    private var numbersSlots = IntArray(0)

    /** Writes the template of [record]'s message into [out] (see [Templates.mask]); empty if it has none. */
    fun template(record: Int, out: ByteArray): Int {
        cursor.moveTo(record)
        return when (type) {
            ColumnType.SPAN -> span(0) { bytes, from, to -> Templates.mask(bytes, from, to, out) }
            ColumnType.DICT -> {
                val id = cursor.dictId(field)
                if (id < 0) return 0
                val template = dictionary!![id]
                    ?: Templates.mask(snapshot.dictionaryTexts(field)[id]).toByteArray().also { dictionary[id] = it }
                template.copyInto(out)
                template.size
            }
            else -> 0
        }
    }

    /**
     * Writes the values at [slots] of [record]'s message (see [Templates.slotNumbers]) into [into] from [at]; NaN
     * where it has none. [out] and [places] are scratch space as that needs.
     */
    fun slotNumbers(record: Int, slots: IntArray, at: Int, into: DoubleArray, out: ByteArray, places: IntArray) {
        cursor.moveTo(record)
        when (type) {
            ColumnType.SPAN -> {
                val read = span(false) { bytes, from, to ->
                    Templates.slotNumbers(bytes, from, to, slots, into, at, out, places)
                    true
                }
                if (!read) into.fill(Double.NaN, at, at + slots.size)
            }
            ColumnType.DICT -> {
                val id = cursor.dictId(field)
                if (id < 0) return into.fill(Double.NaN, at, at + slots.size)
                if (!slots.contentEquals(numbersSlots)) {
                    numbersRead!!.fill(false)
                    numbersSlots = slots.copyOf()
                    dictionaryNumbers = DoubleArray(numbersRead.size * slots.size)
                }
                if (!numbersRead!![id]) {
                    val bytes = snapshot.dictionaryTexts(field)[id].toByteArray(Charsets.UTF_8)
                    Templates.slotNumbers(bytes, 0, bytes.size, slots, dictionaryNumbers, id * slots.size, out, places)
                    numbersRead[id] = true
                }
                dictionaryNumbers.copyInto(into, at, id * slots.size, (id + 1) * slots.size)
            }
            else -> into.fill(Double.NaN, at, at + slots.size)
        }
    }

    /** Calls [read] with the start of the message of the record at the cursor, or returns [absent] if it has none. */
    private inline fun <T> span(absent: T, read: (bytes: ByteArray, from: Int, to: Int) -> T): T {
        val length = cursor.spanLength(field)
        if (length == Columns.ABSENT_LENGTH) return absent
        val from = cursor.start + cursor.spanStart(field)
        val to = if (length == Columns.TO_RECORD_END) cursor.end else from + length
        // Only the start of the first line counts, so huge messages are not read whole.
        val limit = minOf(to, from + READ_LIMIT)
        val offset = reader!!.load(from, limit)
        val end = minOf(offset + (limit - from).toInt(), reader.loaded)
        val bytes = reader.buffer
        // Lines that are no JSON run to the record end and read as written.
        if (json && length != Columns.TO_RECORD_END && end > offset && bytes[offset] == QUOTE) {
            val closed = limit == to && Json.isString(bytes, offset, end)
            val n = Json.unescape(bytes, offset + 1, if (closed) end - 1 else end, unescaped!!, 0)
            return read(unescaped, 0, n)
        }
        return read(bytes, offset, end)
    }

    private companion object {
        /** Enough for [Templates.MAX_BYTES] of text even when JSON escapes take six bytes per character. */
        const val READ_LIMIT = Templates.MAX_BYTES * 6 + 2
        const val QUOTE = '"'.code.toByte()
    }
}
