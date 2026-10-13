package io.github.rastislavpapp.logxy.core

/** Small routines on raw (UTF-8 or ASCII) bytes, shared by parsing, filtering and display. */
internal object Bytes {
    const val LF = '\n'.code.toByte()
    const val CR = '\r'.code.toByte()

    /** Whether `bytes[at, to)` starts with [expected]. */
    fun regionMatches(bytes: ByteArray, at: Int, to: Int, expected: ByteArray): Boolean {
        if (at + expected.size > to) return false
        for (i in expected.indices) if (bytes[at + i] != expected[i]) return false
        return true
    }

    /** Whether `bytes[from, to)` starts with the ASCII text [prefix]. */
    fun startsWith(bytes: ByteArray, from: Int, to: Int, prefix: String): Boolean {
        if (to - from < prefix.length) return false
        for (i in prefix.indices) if (bytes[from + i] != prefix[i].code.toByte()) return false
        return true
    }

    /** First position of [needle] in `bytes[from, to)`, or -1; an empty needle is found at [from]. */
    fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        if (needle.isEmpty()) return from
        val first = needle[0]
        for (i in from..to - needle.size) {
            if (bytes[i] == first && regionMatches(bytes, i, to, needle)) return i
        }
        return -1
    }

    /** Last position of [needle] in `bytes[from, to)`, or -1; an empty needle is found at [to]. */
    fun lastIndexOf(bytes: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        if (needle.isEmpty()) return to
        val first = needle[0]
        for (i in to - needle.size downTo from) {
            if (bytes[i] == first && regionMatches(bytes, i, to, needle)) return i
        }
        return -1
    }

    /** The end of `bytes[from, to)` without a trailing `\n` or `\r\n`. */
    fun trimLineEnd(bytes: ByteArray, from: Int, to: Int): Int {
        var end = to
        if (end > from && bytes[end - 1] == LF) end--
        if (end > from && bytes[end - 1] == CR) end--
        return end
    }

    /** `bytes[from, to)` as UTF-8 text; beyond [maxBytes] cut at a character boundary and ending with `…`. */
    fun utf8Text(bytes: ByteArray, from: Int, to: Int, maxBytes: Int): String {
        if (to - from <= maxBytes) return String(bytes, from, to - from, Charsets.UTF_8)
        var end = from + maxBytes
        while (end > from && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, from, end - from, Charsets.UTF_8) + "…"
    }

    /** The decimal number `bytes[from, to)` holds, e.g. `-12`, `3.5` or `1e-3`, ignoring surrounding blanks; NaN if it holds none. */
    fun parseNumber(bytes: ByteArray, from: Int, to: Int): Double {
        var start = from
        var end = to
        while (start < end && bytes[start] <= SPACE) start++
        while (end > start && bytes[end - 1] <= SPACE) end--
        var i = start
        val negative = i < end && bytes[i] == MINUS
        if (i < end && (negative || bytes[i] == PLUS)) i++
        val digits = i
        var value = 0L
        // Integers of up to 18 digits fit a long; they are the common case and need no string.
        while (i < end && i - digits < 18 && bytes[i] in ZERO..NINE) value = value * 10 + (bytes[i++] - ZERO)
        if (i == end) return if (i == digits) Double.NaN else (if (negative) -value else value).toDouble()
        return if (isDecimal(bytes, digits, end)) String(bytes, start, end - start, Charsets.ISO_8859_1).toDouble() else Double.NaN
    }

    /** The decimal number [text] holds, as [parseNumber] reads bytes; NaN if it holds none. */
    fun parseNumber(text: String): Double = text.toByteArray().let { parseNumber(it, 0, it.size) }

    /** Whether `bytes[from, to)` is digits with an optional fraction and exponent; toDouble would also take e.g. `NaN` or `1f`. */
    private fun isDecimal(bytes: ByteArray, from: Int, to: Int): Boolean {
        var i = from
        var mantissa = 0
        while (i < to && bytes[i] in ZERO..NINE) { i++; mantissa++ }
        if (i < to && bytes[i] == DOT) {
            i++
            while (i < to && bytes[i] in ZERO..NINE) { i++; mantissa++ }
        }
        if (mantissa == 0) return false
        if (i < to && (bytes[i] == 'e'.code.toByte() || bytes[i] == 'E'.code.toByte())) {
            i++
            if (i < to && (bytes[i] == PLUS || bytes[i] == MINUS)) i++
            val exponent = i
            while (i < to && bytes[i] in ZERO..NINE) i++
            if (i == exponent) return false
        }
        return i == to
    }

    private const val SPACE = ' '.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private const val DOT = '.'.code.toByte()
    private const val ZERO = '0'.code.toByte()
    private const val NINE = '9'.code.toByte()
}
