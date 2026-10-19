package io.github.rastislavpapp.logxy.core.template

/**
 * Message templates: the first line of a message with its variable parts replaced by `{}`, as in
 * `Failed to process message {}: timeout after {} ms`. A word is variable if it has a digit (numbers,
 * ids, UUIDs, IP addresses, durations) or is a long hex string.
 */
object Templates {
    const val PLACEHOLDER = "{}"

    /** First lines are cut at this many bytes, so huge payloads do not make huge templates. */
    const val MAX_BYTES = 1024

    /** Space [mask] needs for its output: every byte may become a two-byte placeholder. */
    const val OUT_SIZE = MAX_BYTES * 2

    /**
     * Writes the template of the text `bytes[from, to)` (UTF-8) into [out] (at least [OUT_SIZE] long); returns
     * its length.
     */
    fun mask(bytes: ByteArray, from: Int, to: Int, out: ByteArray): Int {
        var limit = to
        if (to - from > MAX_BYTES) {
            limit = from + MAX_BYTES
            while (limit > from && (bytes[limit].toInt() and 0xC0) == 0x80) limit--
        }
        var n = 0
        var i = from
        // One pass, by byte classes: this runs for every record of a file.
        while (i < limit) {
            val kind = CLASSES[bytes[i].toInt() and 0xFF].toInt()
            if (kind and LINE_END != 0) break
            if (kind and DELIMITER != 0) {
                out[n++] = bytes[i++]
                continue
            }
            // The word is copied as it is scanned, and replaced by a placeholder only if it turns out variable.
            val start = i
            val outStart = n
            var any = 0
            var all = HEX
            while (i < limit) {
                val b = bytes[i]
                val k = CLASSES[b.toInt() and 0xFF].toInt()
                if (k and (DELIMITER or LINE_END) != 0) break
                any = any or k
                all = all and k
                out[n++] = b
                i++
            }
            // Punctuation around a word stays: `id 42:` becomes `id {}:`, JSON's `"id":42` becomes `"id":{}`.
            var valueStart = start
            while (valueStart < i && bytes[valueStart] == COLON) valueStart++
            var valueEnd = i
            while (valueEnd > valueStart && (bytes[valueEnd - 1] == DOT || bytes[valueEnd - 1] == COLON)) valueEnd--
            var hex = all and HEX != 0
            if ((valueStart > start || valueEnd < i) && !hex) {
                hex = (valueStart..<valueEnd).all { CLASSES[bytes[it].toInt() and 0xFF].toInt() and HEX != 0 }
            }
            if (valueEnd > valueStart && (any and DIGIT != 0 || (hex && valueEnd - valueStart >= MIN_HEX_LENGTH))) {
                n = outStart + (valueStart - start)
                out[n++] = OPEN
                out[n++] = CLOSE
                for (j in valueEnd..<i) out[n++] = bytes[j]
            }
        }
        return n
    }

    /** The template of [text]. */
    fun mask(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(OUT_SIZE)
        return String(out, 0, mask(bytes, 0, bytes.size, out), Charsets.UTF_8)
    }

    private const val DOT = '.'.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val OPEN = '{'.code.toByte()
    private const val CLOSE = '}'.code.toByte()

    /** Shorter all-hex words are often real words, like `added` or `face`. */
    private const val MIN_HEX_LENGTH = 8

    private const val DELIMITER = 1
    private const val LINE_END = 2
    private const val DIGIT = 4
    private const val HEX = 8

    private val CLASSES = ByteArray(256).also { classes ->
        fun mark(chars: String, kind: Int) = chars.forEach {
            classes[it.code] = (classes[it.code].toInt() or kind).toByte()
        }
        mark(" \t,;()[]{}<>\"'=|", DELIMITER)
        mark("\n\r", LINE_END)
        mark("0123456789", DIGIT or HEX)
        mark("abcdefABCDEF", HEX)
    }
}
