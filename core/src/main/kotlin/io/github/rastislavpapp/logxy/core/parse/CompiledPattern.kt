package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.DateTimeException
import java.time.ZoneId

/**
 * A log4j2/logback `PatternLayout` compiled into a fixed sequence of fields and literal separators,
 * matched byte by byte (no regex). A field's value ends where the next literal starts; the message
 * runs to the end of the record, including continuation lines such as stack traces.
 */
class CompiledPattern(val pattern: String, private val zone: ZoneId = ZoneId.systemDefault()) : CompiledParser() {

    private enum class Kind { TIMESTAMP, LEVEL, DICT, SPAN, MESSAGE }

    private sealed interface Token
    private class Literal(val bytes: ByteArray) : Token
    private class FieldToken(
        val field: Int,
        val kind: Kind,
        val minWidth: Int,
        /** `key=` of `%X{key}`, for taking the value out of a whole MDC map written there (log4j2's braces only). */
        val mdcKey: ByteArray? = null,
    ) : Token

    private val tokens = ArrayList<Token>()
    private val fields = ArrayList<Field>()
    private val timestamps = HashMap<Int, TimestampFormat>()

    /** Fields of `%X` without a key or with several, i.e. an MDC map. */
    private val mdcMaps = ArrayList<Int>()

    override val schema: Schema

    init {
        val literal = StringBuilder()
        compile(pattern, literal)
        if (literal.isNotEmpty()) tokens += Literal(literal.toString().toByteArray())
        require(fields.isNotEmpty()) { "The pattern has no fields" }
        if (mdcMaps.size > 1) {
            for ((n, field) in mdcMaps.withIndex()) fields[field] = Field("$MDC[$n]", ColumnType.SPAN, map = MdcMap)
        }
        // A message at the end runs to the end of the record, stack traces included.
        val last = tokens.last()
        if (last is FieldToken && last.kind == Kind.MESSAGE) {
            fields[last.field] = fields[last.field].let { Field(it.name, it.type, it.role, toRecordEnd = true) }
        }
        schema = Schema(fields)
    }

    override fun formatTimestamp(field: Int, nanos: Long) = timestamps.getValue(field).format(nanos)

    override fun newMatcher(): LineMatcher = Matcher()

    /** Appends the tokens of [pattern]; returns false once `%n` ends the first line. */
    private fun compile(pattern: String, literal: StringBuilder): Boolean {
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c != '%') {
                literal.append(c)
                i++
                continue
            }
            if (pattern.getOrNull(i + 1) == '%') {
                literal.append('%')
                i += 2
                continue
            }
            i++
            if (pattern.getOrNull(i) == '-') i++
            var minWidth = 0
            while (i < pattern.length && pattern[i].isDigit()) minWidth = minWidth * 10 + (pattern[i++] - '0')
            if (pattern.getOrNull(i) == '.') {
                i++
                if (pattern.getOrNull(i) == '-') i++
                while (i < pattern.length && pattern[i].isDigit()) i++
            }
            val wordStart = i
            while (i < pattern.length && pattern[i].isLetter()) i++
            val word = pattern.substring(wordStart, i)
            var content: String? = null
            if (pattern.getOrNull(i) == '(') {
                val close = closing(pattern, i, '(', ')')
                content = pattern.substring(i + 1, close)
                i = close + 1
            }
            val options = ArrayList<String>()
            while (pattern.getOrNull(i) == '{') {
                val close = closing(pattern, i, '{', '}')
                options += pattern.substring(i + 1, close)
                i = close + 1
            }
            when {
                word == "n" -> return false
                word in WRAPPERS -> if (!compile(content ?: options.firstOrNull() ?: "", literal)) return false
                word in IGNORED -> Unit
                else -> {
                    if (literal.isNotEmpty()) {
                        tokens += Literal(literal.toString().toByteArray())
                        literal.clear()
                    }
                    addField(word, options, minWidth)
                }
            }
        }
        return true
    }

    private fun addField(word: String, options: List<String>, minWidth: Int) {
        val previous = tokens.lastOrNull()
        require(previous !is FieldToken || previous.kind == Kind.TIMESTAMP) {
            "%$word directly follows another field; the pattern needs text between them"
        }
        val (name, kind, role) = when (word) {
            "d", "date" -> Triple("Timestamp", Kind.TIMESTAMP, Role.TIMESTAMP)
            "p", "le", "level" -> Triple("Level", Kind.LEVEL, Role.LEVEL)
            "t", "thread", "tn", "threadName" -> Triple("Thread", Kind.DICT, Role.THREAD)
            "c", "lo", "logger" -> Triple("Logger", Kind.DICT, Role.LOGGER)
            "m", "msg", "message" -> Triple("Message", Kind.MESSAGE, Role.MESSAGE)
            "C", "class" -> Triple("Class", Kind.DICT, null)
            "M", "method" -> Triple("Method", Kind.DICT, null)
            "F", "file" -> Triple("File", Kind.DICT, null)
            "L", "line" -> Triple("Line", Kind.SPAN, null)
            in MDC_WORDS -> Triple(options.firstOrNull()?.takeIf { it.isNotEmpty() && ',' !in it } ?: MDC, Kind.SPAN, null)
            "K", "map", "MAP" -> Triple(options.firstOrNull()?.ifEmpty { null } ?: "Map", Kind.SPAN, null)
            "x", "NDC" -> Triple("NDC", Kind.SPAN, null)
            "pid", "processId" -> Triple("PID", Kind.DICT, null)
            "T", "tid", "threadId" -> Triple("Thread ID", Kind.DICT, null)
            "tp", "threadPriority" -> Triple("Thread priority", Kind.DICT, null)
            "marker", "markerSimpleName" -> Triple("Marker", Kind.DICT, null)
            "cn", "contextName" -> Triple("Context", Kind.DICT, null)
            "r", "relative" -> Triple("Relative", Kind.SPAN, null)
            "sn", "sequenceNumber" -> Triple("Sequence", Kind.SPAN, null)
            "u", "uuid" -> Triple("UUID", Kind.SPAN, null)
            else -> throw IllegalArgumentException("Unsupported conversion %$word")
        }
        val index = fields.size
        if (word in MDC_WORDS && options.firstOrNull().let { it.isNullOrEmpty() || ',' in it }) mdcMaps += index
        if (kind == Kind.TIMESTAMP) timestamps[index] = timestampFormat(options)
        val type = when (kind) {
            Kind.TIMESTAMP -> ColumnType.TIMESTAMP
            Kind.LEVEL -> ColumnType.LEVEL
            Kind.DICT -> ColumnType.DICT
            Kind.SPAN, Kind.MESSAGE -> ColumnType.SPAN
        }
        val unique = (sequenceOf(name) + generateSequence(2) { it + 1 }.map { "$name $it" })
            .first { candidate -> fields.none { it.name == candidate } }
        val mdcKeys = if (word in MDC_WORDS) options.firstOrNull().orEmpty() else null
        // `%X{a,b}` writes a map of those keys, like `%X` of all.
        val isMap = mdcKeys != null && (mdcKeys.isEmpty() || ',' in mdcKeys)
        fields += Field(unique, type, role, map = if (isMap) MdcMap else null)
        tokens += FieldToken(index, kind, minWidth, mdcKeys?.takeIf { !isMap }?.let { "$it=".toByteArray() })
    }

    private fun timestampFormat(options: List<String>): TimestampFormat {
        val layout = options.firstOrNull()?.ifEmpty { null } ?: "DEFAULT"
        val zone = options.getOrNull(1)?.let {
            try {
                ZoneId.of(it)
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("Unknown time zone '$it'", e)
            }
        } ?: zone
        return try {
            TimestampFormat(layout, zone)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid date pattern '$layout': ${e.message}", e)
        }
    }

    private inner class Matcher : LineMatcher {
        private val timestampParsers = arrayOfNulls<TimestampParser>(fields.size).also { parsers ->
            timestamps.forEach { (field, format) -> parsers[field] = format.newParser() }
        }

        // Arrays and index loops: this runs for every line of the file.
        private val tokenArray = tokens.toTypedArray()

        /** The literal after each token, which ends a field's value; null for the last token. */
        private val nextLiteral = Array(tokenArray.size) { (tokenArray.getOrNull(it + 1) as? Literal)?.bytes }
        private val endsWithLiteral = tokenArray.last() is Literal
        private val starts = IntArray(tokenArray.size)
        private val ends = IntArray(tokenArray.size)
        private var lineFrom = 0
        private var bytes = ByteArray(0)

        override fun match(bytes: ByteArray, from: Int, to: Int): Boolean {
            var position = from
            for (k in tokenArray.indices) {
                when (val token = tokenArray[k]) {
                    is Literal -> {
                        if (!Bytes.regionMatches(bytes, position, to, token.bytes)) return false
                        position += token.bytes.size
                    }
                    is FieldToken -> {
                        if (token.kind == Kind.TIMESTAMP) {
                            val end = timestampParsers[token.field]!!.parse(bytes, position, to)
                            if (end < 0) return false
                            starts[k] = position
                            ends[k] = end
                            position = end
                            continue
                        }
                        val next = nextLiteral[k]
                        val end = when {
                            next == null -> to
                            token.kind == Kind.MESSAGE -> Bytes.lastIndexOf(bytes, next, position, to)
                            else -> Bytes.indexOf(bytes, next, minOf(position + token.minWidth, to), to)
                        }
                        if (end < 0) return false
                        var start = position
                        var valueEnd = end
                        if (token.kind != Kind.MESSAGE) {
                            while (start < valueEnd && bytes[start] == SPACE) start++
                            while (valueEnd > start && bytes[valueEnd - 1] == SPACE) valueEnd--
                        }
                        if (token.kind == Kind.LEVEL && !looksLikeLevel(bytes, start, valueEnd)) return false
                        starts[k] = start
                        ends[k] = valueEnd
                        position = end
                    }
                }
            }
            if (endsWithLiteral) {
                while (position < to && bytes[position] == SPACE) position++
                if (position != to) return false
            }
            this.bytes = bytes
            lineFrom = from
            return true
        }

        override fun write(out: Columns) {
            for (k in tokenArray.indices) {
                val token = tokenArray[k]
                if (token !is FieldToken) continue
                val field = token.field
                when (token.kind) {
                    Kind.TIMESTAMP -> out.setTimestamp(field, timestampParsers[field]!!.nanos)
                    Kind.LEVEL -> out.setLevel(field, bytes, starts[k], ends[k])
                    Kind.DICT -> out.setText(field, bytes, lineFrom, starts[k], ends[k])
                    Kind.SPAN -> {
                        val key = token.mdcKey
                        val entries = if (key != null && ends[k] > starts[k] && bytes[starts[k]] == OPEN_BRACE) {
                            MdcMap.entries(bytes, starts[k], ends[k])
                        } else {
                            -1L
                        }
                        if (entries >= 0) {
                            val value = MdcMap.valueOf(bytes, MdcMap.from(entries), MdcMap.to(entries), key!!)
                            if (value >= 0) out.setSpan(field, MdcMap.from(value) - lineFrom, MdcMap.to(value) - MdcMap.from(value))
                        } else {
                            out.setSpan(field, starts[k] - lineFrom, ends[k] - starts[k])
                            if (out.schema.fields[field].isMap) out.addMapKeys(field, bytes, starts[k], ends[k])
                        }
                    }
                    Kind.MESSAGE -> {
                        val toEnd = k == tokenArray.lastIndex
                        out.setSpan(field, starts[k] - lineFrom, if (toEnd) Columns.TO_RECORD_END else ends[k] - starts[k])
                    }
                }
            }
        }
    }

    private companion object {
        const val SPACE = ' '.code.toByte()
        const val MDC = "MDC"
        val MDC_WORDS = setOf("X", "mdc", "MDC")
        const val OPEN_BRACE = '{'.code.toByte()

        /** Converters that only decorate their content, e.g. with ANSI colors. */
        val WRAPPERS = setOf(
            "highlight", "style", "clr", "encode", "black", "red", "green", "yellow", "blue", "magenta", "cyan",
            "white", "gray", "boldRed", "boldGreen", "boldYellow", "boldBlue", "boldMagenta", "boldCyan", "boldWhite",
        )

        /** Converters that print nothing on the first line (exceptions go on following lines). */
        val IGNORED = setOf(
            "ex", "exception", "throwable", "xEx", "xException", "xThrowable", "rEx", "rException", "rThrowable",
            "wEx", "wThrowable", "nopex", "nopexception", "xwEx",
        )

        fun closing(pattern: String, open: Int, openChar: Char, closeChar: Char): Int {
            var depth = 0
            for (i in open..<pattern.length) {
                if (pattern[i] == openChar) depth++
                if (pattern[i] == closeChar && --depth == 0) return i
            }
            throw IllegalArgumentException("Unclosed '$openChar' at position ${open + 1}")
        }

        /** Stack trace lines often fit a layout loosely; real levels are short words. */
        fun looksLikeLevel(bytes: ByteArray, from: Int, to: Int): Boolean {
            if (to - from !in 1..12) return false
            for (i in from..<to) {
                val c = bytes[i].toInt().toChar()
                if (!c.isLetter()) return false
            }
            return true
        }
    }
}
