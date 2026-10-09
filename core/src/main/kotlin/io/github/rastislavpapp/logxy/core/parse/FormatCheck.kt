package io.github.rastislavpapp.logxy.core.parse

import java.nio.channels.FileChannel

/** The file's first entries do not fit the chosen format. */
class FormatMismatchException(message: String) : Exception(message)

/**
 * Checks a format against the start of a file before parsing it, so a wrong format fails fast
 * instead of turning the whole file into one record.
 */
internal object FormatCheck {

    /** Lines that should start a record; if this many in a row do not match, the format is wrong. */
    const val REAL_LINES = 10

    private const val BUFFER_SIZE = 1024 * 1024
    private const val SHOWN_LINE_CHARS = 200

    /**
     * Throws [FormatMismatchException] unless one of the first [REAL_LINES] lines that do not look
     * like continuations matches, or the file has no content at all. Continuations before the first
     * match are skipped, as the file may start in the middle of a stack trace; after it nothing more
     * is read, as a single record may be huge.
     */
    fun check(channel: FileChannel, size: Long, parser: RecordParser) {
        val reader = LineReader(channel, 0, size, BUFFER_SIZE)
        var firstContent: String? = null
        var firstMismatch: String? = null
        var mismatches = 0
        while (reader.next()) {
            val bytes = reader.buffer
            val from = reader.lineFrom
            val to = reader.lineTo
            if (parser.match(bytes, from, to)) return
            if (isBlank(bytes, from, to)) continue
            if (firstContent == null) firstContent = shown(bytes, from, to)
            if (looksLikeContinuation(bytes, from, to)) continue
            if (firstMismatch == null) firstMismatch = shown(bytes, from, to)
            if (++mismatches == REAL_LINES) break
        }
        val example = firstMismatch ?: firstContent ?: return
        val which = if (mismatches == REAL_LINES) "None of the first $REAL_LINES entries" else "No entry"
        throw FormatMismatchException("$which in the file matches the format. First one:\n$example")
    }

    private fun shown(bytes: ByteArray, from: Int, to: Int) =
        String(bytes, from, minOf(to - from, SHOWN_LINE_CHARS), Charsets.UTF_8)

    private fun isBlank(bytes: ByteArray, from: Int, to: Int): Boolean {
        for (i in from..<to) if (bytes[i] != ' '.code.toByte() && bytes[i] != '\t'.code.toByte()) return false
        return true
    }

    /** Blank, indented, or the start of a stack trace: lines that usually continue a record. */
    fun looksLikeContinuation(bytes: ByteArray, from: Int, to: Int): Boolean {
        var i = from
        while (i < to && (bytes[i] == ' '.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
        if (i == to || i > from) return true
        if (startsWith(bytes, from, to, "Caused by:") || startsWith(bytes, from, to, "Suppressed:")) return true
        // An exception header such as "java.lang.IllegalStateException: message".
        var end = from
        while (end < to && bytes[end] != ':'.code.toByte() && bytes[end] != ' '.code.toByte()) end++
        val name = String(bytes, from, end - from, Charsets.ISO_8859_1)
        return '.' in name && (name.endsWith("Exception") || name.endsWith("Error") || name.endsWith("Throwable"))
    }

    private fun startsWith(bytes: ByteArray, from: Int, to: Int, prefix: String): Boolean {
        if (to - from < prefix.length) return false
        for (i in prefix.indices) if (bytes[from + i] != prefix[i].code.toByte()) return false
        return true
    }
}
