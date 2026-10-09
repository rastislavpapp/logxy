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
}
