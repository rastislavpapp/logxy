package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.MapKey
import java.io.ByteArrayOutputStream

/**
 * Reads JSON text byte by byte, finding the ranges of values without building objects. Lenient:
 * it checks the structure only as far as it needs to find where values end.
 */
internal object Json {
    const val QUOTE = '"'.code.toByte()
    const val BACKSLASH = '\\'.code.toByte()
    const val OPEN_OBJECT = '{'.code.toByte()
    const val CLOSE_OBJECT = '}'.code.toByte()
    const val OPEN_ARRAY = '['.code.toByte()
    const val CLOSE_ARRAY = ']'.code.toByte()
    const val COLON = ':'.code.toByte()
    const val COMMA = ','.code.toByte()

    /** Nesting deeper than this is shown as written instead of pretty-printed. */
    private const val MAX_PRETTY_DEPTH = 64

    /** Whether [b] is JSON whitespace: space, tab, line feed or carriage return. */
    fun isSpace(b: Byte) =
        b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte()

    /** Whether [b] ends a number or literal: whitespace or the punctuation that may follow a value. */
    private fun endsValue(b: Byte) = isSpace(b) || b == COMMA || b == CLOSE_OBJECT || b == CLOSE_ARRAY

    /** The first index from [from] on that is not [isSpace], or [to]. */
    fun skipSpace(bytes: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to && isSpace(bytes[i])) i++
        return i
    }

    /** The index after the string whose opening quote is at [from], or -1 if it does not end before [to]. */
    fun stringEnd(bytes: ByteArray, from: Int, to: Int): Int {
        var i = from + 1
        while (i < to) {
            when (bytes[i]) {
                BACKSLASH -> i += 2
                QUOTE -> return i + 1
                else -> i++
            }
        }
        return -1
    }

    /** The index after the value starting at [from], or -1 if none starts there. */
    fun valueEnd(bytes: ByteArray, from: Int, to: Int): Int {
        if (from >= to) return -1
        return when (bytes[from]) {
            QUOTE -> stringEnd(bytes, from, to)
            OPEN_OBJECT, OPEN_ARRAY -> containerEnd(bytes, from, to)
            else -> {
                val first = bytes[from].toInt().toChar()
                if (first != '-' && first !in '0'..'9' && first != 't' && first != 'f' && first != 'n') return -1
                var i = from + 1
                while (i < to && !endsValue(bytes[i])) i++
                i
            }
        }
    }

    private fun containerEnd(bytes: ByteArray, from: Int, to: Int): Int {
        var depth = 0
        var i = from
        while (i < to) {
            when (bytes[i]) {
                QUOTE -> {
                    i = stringEnd(bytes, i, to)
                    if (i < 0) return -1
                    continue
                }
                OPEN_OBJECT, OPEN_ARRAY -> depth++
                CLOSE_OBJECT, CLOSE_ARRAY -> if (--depth == 0) return i + 1
            }
            i++
        }
        return -1
    }

    /**
     * Calls [entry] with the key range (without quotes) and the value range of each entry of the object
     * that `bytes[from, to)` holds, surrounding white space aside. Returns false if it holds no object;
     * [entry] may have been called for the entries before the error then.
     */
    inline fun forEachEntry(bytes: ByteArray, from: Int, to: Int, entry: (Int, Int, Int, Int) -> Unit): Boolean {
        var i = skipSpace(bytes, from, to)
        if (i >= to || bytes[i] != OPEN_OBJECT) return false
        i = skipSpace(bytes, i + 1, to)
        if (i < to && bytes[i] == CLOSE_OBJECT) return skipSpace(bytes, i + 1, to) == to
        while (true) {
            if (i >= to || bytes[i] != QUOTE) return false
            val keyEnd = stringEnd(bytes, i, to)
            if (keyEnd < 0) return false
            var j = skipSpace(bytes, keyEnd, to)
            if (j >= to || bytes[j] != COLON) return false
            val valueFrom = skipSpace(bytes, j + 1, to)
            val valueTo = valueEnd(bytes, valueFrom, to)
            if (valueTo < 0) return false
            entry(i + 1, keyEnd - 1, valueFrom, valueTo)
            j = skipSpace(bytes, valueTo, to)
            if (j >= to) return false
            if (bytes[j] == CLOSE_OBJECT) return skipSpace(bytes, j + 1, to) == to
            if (bytes[j] != COMMA) return false
            i = skipSpace(bytes, j + 1, to)
        }
    }

    /** Like [forEachEntry], for the elements of an array. */
    inline fun forEachElement(bytes: ByteArray, from: Int, to: Int, element: (Int, Int) -> Unit): Boolean {
        var i = skipSpace(bytes, from, to)
        if (i >= to || bytes[i] != OPEN_ARRAY) return false
        i = skipSpace(bytes, i + 1, to)
        if (i < to && bytes[i] == CLOSE_ARRAY) return skipSpace(bytes, i + 1, to) == to
        while (true) {
            val end = valueEnd(bytes, i, to)
            if (end < 0) return false
            element(i, end)
            val j = skipSpace(bytes, end, to)
            if (j >= to) return false
            if (bytes[j] == CLOSE_ARRAY) return skipSpace(bytes, j + 1, to) == to
            if (bytes[j] != COMMA) return false
            i = skipSpace(bytes, j + 1, to)
        }
    }

    /** Whether the value `bytes[from, to)` is a string, i.e. quoted at both ends. */
    fun isString(bytes: ByteArray, from: Int, to: Int) =
        to - from >= 2 && bytes[from] == QUOTE && bytes[to - 1] == QUOTE

    /** Whether the string content `bytes[from, to)` reads as it is written, without escapes. */
    fun isPlain(bytes: ByteArray, from: Int, to: Int): Boolean {
        for (i in from..<to) if (bytes[i] == BACKSLASH) return false
        return true
    }

    /**
     * Decodes the value `bytes[from, to)` to the UTF-8 text the table shows: a string's content unescaped, other
     * values as written.
     * A string cut before its closing quote is shown up to the cut.
     */
    fun decode(bytes: ByteArray, from: Int, to: Int): ByteArray {
        if (from >= to || bytes[from] != QUOTE) return bytes.copyOfRange(from, to)
        val end = if (isString(bytes, from, to)) to - 1 else to
        val out = ByteArray(end - from - 1)
        val length = unescape(bytes, from + 1, end, out, 0)
        return if (length == out.size) out else out.copyOf(length)
    }

    fun text(bytes: ByteArray, from: Int, to: Int): String = String(decode(bytes, from, to), Charsets.UTF_8)

    /**
     * Writes the string content `bytes[from, to)` unescaped into [out] at [at]; returns the end. Never
     * longer than the escaped text. Invalid escapes are kept as written.
     */
    fun unescape(bytes: ByteArray, from: Int, to: Int, out: ByteArray, at: Int): Int {
        var o = at
        var i = from
        while (i < to) {
            val b = bytes[i]
            if (b != BACKSLASH || i + 1 >= to) {
                out[o++] = b
                i++
                continue
            }
            val simple = when (bytes[i + 1].toInt().toChar()) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000C'
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                else -> null
            }
            if (simple != null) {
                out[o++] = simple.code.toByte()
                i += 2
                continue
            }
            val unit = if (bytes[i + 1] == 'u'.code.toByte()) hex4(bytes, i + 2, to) else -1
            if (unit < 0) {
                out[o++] = b
                i++
                continue
            }
            i += 6
            var codePoint = unit
            if (Character.isHighSurrogate(unit.toChar())) {
                val escaped = i + 1 < to && bytes[i] == BACKSLASH && bytes[i + 1] == 'u'.code.toByte()
                val low = if (escaped) hex4(bytes, i + 2, to) else -1
                if (low >= 0 && Character.isLowSurrogate(low.toChar())) {
                    codePoint = Character.toCodePoint(unit.toChar(), low.toChar())
                    i += 6
                } else {
                    codePoint = 0xFFFD
                }
            } else if (Character.isLowSurrogate(unit.toChar())) {
                codePoint = 0xFFFD
            }
            o = utf8(codePoint, out, o)
        }
        return o
    }

    private fun hex4(bytes: ByteArray, from: Int, to: Int): Int {
        if (from + 4 > to) return -1
        var value = 0
        for (i in from..<from + 4) {
            val digit = Character.digit(bytes[i].toInt(), 16)
            if (digit < 0) return -1
            value = value * 16 + digit
        }
        return value
    }

    private fun utf8(codePoint: Int, out: ByteArray, at: Int): Int {
        var o = at
        when {
            codePoint < 0x80 -> out[o++] = codePoint.toByte()
            codePoint < 0x800 -> {
                out[o++] = (0xC0 or (codePoint shr 6)).toByte()
                out[o++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            codePoint < 0x10000 -> {
                out[o++] = (0xE0 or (codePoint shr 12)).toByte()
                out[o++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            else -> {
                out[o++] = (0xF0 or (codePoint shr 18)).toByte()
                out[o++] = (0x80 or ((codePoint shr 12) and 0x3F)).toByte()
                out[o++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
        }
        return o
    }

    /**
     * The object or array that is exactly `bytes[from, to)`, indented for reading, as UTF-8 text; null if it is no
     * well-formed object or array. Strings are unescaped, so a stack trace shows as lines of its own.
     */
    fun pretty(bytes: ByteArray, from: Int, to: Int): ByteArray? {
        if (from >= to || (bytes[from] != OPEN_OBJECT && bytes[from] != OPEN_ARRAY)) return null
        val out = ByteArrayOutputStream(to - from + 64)
        return if (writePretty(bytes, from, to, out, 0)) out.toByteArray() else null
    }

    /**
     * Writes the value `bytes[from, to)` into [out], indented as [depth] levels deep; returns false if the value
     * is malformed inside.
     */
    private fun writePretty(bytes: ByteArray, from: Int, to: Int, out: ByteArrayOutputStream, depth: Int): Boolean {
        val first = bytes[from]
        if (isString(bytes, from, to)) {
            writeString(bytes, from, to, out)
            return true
        }
        if (first != OPEN_OBJECT && first != OPEN_ARRAY) {
            if (!isScalar(bytes, from, to)) return false
            out.write(bytes, from, to - from)
            return true
        }
        if (depth >= MAX_PRETTY_DEPTH) {
            out.write(bytes, from, to - from)
            return true
        }
        val isObject = first == OPEN_OBJECT
        var count = 0
        var valid = true
        fun next() {
            val separator = when {
                count++ > 0 -> COMMA
                isObject -> OPEN_OBJECT
                else -> OPEN_ARRAY
            }
            out.write(separator.toInt())
            out.write('\n'.code)
            repeat(depth + 1) { out.write(INDENT) }
        }
        val structured = if (isObject) {
            forEachEntry(bytes, from, to) { keyFrom, keyTo, valueFrom, valueTo ->
                next()
                writeString(bytes, keyFrom - 1, keyTo + 1, out)
                out.write(COLON.toInt())
                out.write(' '.code)
                if (!writePretty(bytes, valueFrom, valueTo, out, depth + 1)) valid = false
            }
        } else {
            forEachElement(bytes, from, to) { valueFrom, valueTo ->
                next()
                if (!writePretty(bytes, valueFrom, valueTo, out, depth + 1)) valid = false
            }
        }
        if (!structured || !valid) return false
        if (count == 0) {
            out.write(bytes, from, to - from)
            return true
        }
        out.write('\n'.code)
        repeat(depth) { out.write(INDENT) }
        out.write((if (isObject) CLOSE_OBJECT else CLOSE_ARRAY).toInt())
        return true
    }

    private fun writeString(bytes: ByteArray, from: Int, to: Int, out: ByteArrayOutputStream) {
        val content = ByteArray(to - from - 2)
        val length = unescape(bytes, from + 1, to - 1, content, 0)
        out.write(QUOTE.toInt())
        out.write(content, 0, length)
        out.write(QUOTE.toInt())
    }

    /**
     * Whether `bytes[from, to)` is `true`, `false`, `null` or a number as JSON writes it, such as `-1.5e3`;
     * [valueEnd] only checks the first byte of these.
     */
    private fun isScalar(bytes: ByteArray, from: Int, to: Int): Boolean {
        val length = to - from
        if (length == 4 && (matches(bytes, from, TRUE) || matches(bytes, from, NULL))) return true
        if (length == 5 && matches(bytes, from, FALSE)) return true
        var i = from
        if (i < to && bytes[i] == '-'.code.toByte()) i++
        val integer = i
        i = skipDigits(bytes, i, to)
        if (i == integer) return false
        val leadingZero = bytes[integer] == '0'.code.toByte() && i - integer > 1
        if (leadingZero) return false
        if (i < to && bytes[i] == '.'.code.toByte()) {
            val fraction = i + 1
            i = skipDigits(bytes, fraction, to)
            if (i == fraction) return false
        }
        if (i < to && (bytes[i] == 'e'.code.toByte() || bytes[i] == 'E'.code.toByte())) {
            i++
            if (i < to && (bytes[i] == '+'.code.toByte() || bytes[i] == '-'.code.toByte())) i++
            val exponent = i
            i = skipDigits(bytes, exponent, to)
            if (i == exponent) return false
        }
        return i == to
    }

    private fun matches(bytes: ByteArray, from: Int, word: ByteArray) =
        java.util.Arrays.equals(bytes, from, from + word.size, word, 0, word.size)

    private fun skipDigits(bytes: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to && bytes[i] in '0'.code.toByte()..'9'.code.toByte()) i++
        return i
    }

    private val TRUE = "true".toByteArray()
    private val FALSE = "false".toByteArray()
    private val NULL = "null".toByteArray()

    private val INDENT = "  ".toByteArray()
}

/**
 * A JSON object read as a map: its top-level keys are fields. Keys in [excluded] are not, as they
 * are shown through fields of their own (e.g. the message).
 */
internal class JsonMap(private val excluded: Set<String>) : MapSyntax {

    override val maxKeys = 64
    override val json = true

    override fun entries(bytes: ByteArray, from: Int, to: Int): Long =
        if (from < to && bytes[from] == Json.OPEN_OBJECT) MdcMap.pack(from, to) else -1

    override fun value(bytes: ByteArray, from: Int, to: Int, key: MapKey): Long {
        val expected = key.bytes
        Json.forEachEntry(bytes, from, to) { keyFrom, keyTo, valueFrom, valueTo ->
            if (java.util.Arrays.equals(bytes, keyFrom, keyTo, expected, 0, expected.size)) {
                return MdcMap.pack(valueFrom, valueTo)
            }
        }
        return -1
    }

    override fun splits(key: String) = key !in excluded

    override fun fieldName(map: String, key: String) = key
}
