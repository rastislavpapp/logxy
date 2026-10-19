package io.github.rastislavpapp.logxy.core.template

import io.github.rastislavpapp.logxy.core.filter.RecordCursor
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

    /**
     * The numbers of each dictionary text at [numbersVariables], side by side, once read; many records share a
     * text.
     */
    private var dictionaryNumbers = DoubleArray(0)
    private val numbersRead = if (type == ColumnType.DICT) BooleanArray(dictionary!!.size) else null
    private var numbersVariables = IntArray(0)

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
     * Writes the values at [variables] of [record]'s message (see [Templates.variableNumbers]) into [into] from
     * [at]; NaN where it has none. [out] and [places] are scratch space as that needs.
     */
    fun variableNumbers(
        record: Int,
        variables: IntArray,
        at: Int,
        into: DoubleArray,
        out: ByteArray,
        places: IntArray,
    ) {
        cursor.moveTo(record)
        when (type) {
            ColumnType.SPAN -> {
                val read = span(false) { bytes, from, to ->
                    Templates.variableNumbers(bytes, from, to, variables, into, at, out, places)
                    true
                }
                if (!read) into.fill(Double.NaN, at, at + variables.size)
            }
            ColumnType.DICT -> {
                val id = cursor.dictId(field)
                if (id < 0) return into.fill(Double.NaN, at, at + variables.size)
                if (!variables.contentEquals(numbersVariables)) {
                    numbersRead!!.fill(false)
                    numbersVariables = variables.copyOf()
                    dictionaryNumbers = DoubleArray(numbersRead.size * variables.size)
                }
                if (!numbersRead!![id]) {
                    val bytes = snapshot.dictionaryTexts(field)[id].toByteArray(Charsets.UTF_8)
                    val at = id * variables.size
                    Templates.variableNumbers(bytes, 0, bytes.size, variables, dictionaryNumbers, at, out, places)
                    numbersRead[id] = true
                }
                dictionaryNumbers.copyInto(into, at, id * variables.size, (id + 1) * variables.size)
            }
            else -> into.fill(Double.NaN, at, at + variables.size)
        }
    }

    /** The word of each dictionary text at [wordsVariable], once read; [NO_WORD] where it has none. */
    private val dictionaryWords = if (type == ColumnType.DICT) arrayOfNulls<ByteArray>(dictionary!!.size) else null
    private var wordsVariable = -1

    /** Hears of a word in `bytes[from, to)`; see [variableWord]. */
    fun interface WordVisitor {
        fun visit(bytes: ByteArray, from: Int, to: Int)
    }

    /**
     * Calls [visit] with the word at [variable] of [record]'s message, unless it has none there. [out] and [places]
     * are scratch space as [Templates.variableRange] needs.
     */
    fun variableWord(record: Int, variable: Int, out: ByteArray, places: IntArray, visit: WordVisitor) {
        cursor.moveTo(record)
        when (type) {
            ColumnType.SPAN -> span(Unit) { bytes, from, to ->
                val range = Templates.variableRange(bytes, from, to, variable, out, places)
                if (range >= 0) visit.visit(bytes, (range ushr 32).toInt(), range.toInt())
            }
            ColumnType.DICT -> {
                val id = cursor.dictId(field)
                if (id < 0) return
                val words = dictionaryWords!!
                if (variable != wordsVariable) {
                    words.fill(null)
                    wordsVariable = variable
                }
                val word = words[id] ?: run {
                    val bytes = snapshot.dictionaryTexts(field)[id].toByteArray(Charsets.UTF_8)
                    val range = Templates.variableRange(bytes, 0, bytes.size, variable, out, places)
                    val found = if (range < 0) NO_WORD else bytes.copyOfRange((range ushr 32).toInt(), range.toInt())
                    found.also { words[id] = it }
                }
                if (word !== NO_WORD) visit.visit(word, 0, word.size)
            }
            else -> {}
        }
    }

    /** Calls [read] with the start of the message of the record at the cursor, or returns [absent] if it has none. */
    private inline fun <T> span(absent: T, read: (bytes: ByteArray, from: Int, to: Int) -> T): T =
        readStart(cursor, field, json, reader!!, unescaped, absent, read)

    companion object {
        /** Enough for [Templates.MAX_BYTES] of text even when JSON escapes take six bytes per character. */
        const val READ_LIMIT = Templates.MAX_BYTES * 6 + 2
        const val QUOTE = '"'.code.toByte()

        /** Stands for a dictionary text without a word at the variable; compared by identity. */
        private val NO_WORD = ByteArray(0)

        /**
         * Calls [read] with the start of the SPAN message [field] of the record at [cursor], read through [reader];
         * returns [absent] if it has none. A [json] string is unescaped into [unescaped], at least [READ_LIMIT] long.
         */
        inline fun <T> readStart(
            cursor: RecordCursor,
            field: Int,
            json: Boolean,
            reader: SpanReader,
            unescaped: ByteArray?,
            absent: T,
            read: (bytes: ByteArray, from: Int, to: Int) -> T,
        ): T {
            val length = cursor.spanLength(field)
            if (length == Columns.ABSENT_LENGTH) return absent
            val from = cursor.start + cursor.spanStart(field)
            val to = if (length == Columns.TO_RECORD_END) cursor.end else from + length
            // Only the start of the first line counts, so huge messages are not read whole.
            val limit = minOf(to, from + READ_LIMIT)
            val offset = reader.load(from, limit)
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
    }
}
