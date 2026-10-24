package io.github.rastislavpapp.logxy.core.parse

/**
 * Splits one CSV line into cells, RFC 4180 style except that a quoted cell cannot span lines: `"` is the only
 * quote, `""` inside a quoted cell is a literal quote, and a quote opens a cell only at the start of the cell
 * (`5" pipe` is plain text).
 */
internal object Csv {

    /** Most columns a CSV file may have, as every column costs memory per record. */
    const val MAX_COLUMNS = 100

    private const val QUOTE = '"'.code.toByte()

    /**
     * Where the content of `bytes[from, to)` starts: past a UTF-8 byte order mark, which spreadsheets write at the
     * start of a file.
     */
    fun contentFrom(bytes: ByteArray, from: Int, to: Int): Int =
        if (to - from >= 3 && bytes[from] == 0xEF.toByte() && bytes[from + 1] == 0xBB.toByte() &&
            bytes[from + 2] == 0xBF.toByte()
        ) {
            from + 3
        } else {
            from
        }

    /**
     * Splits `bytes[from, to)` at [separator], writing where each cell (with its quotes, see [decode]) starts and ends
     * into [starts] and [ends]. Returns the number of cells, or -1 if the line is no valid row: a quote that never
     * closes, text after a closing quote, or more cells than the arrays hold.
     */
    fun cells(bytes: ByteArray, from: Int, to: Int, separator: Byte, starts: IntArray, ends: IntArray): Int {
        var count = 0
        var i = from
        while (true) {
            if (count == starts.size) return -1
            if (i < to && bytes[i] == QUOTE) {
                var close = i + 1
                while (true) {
                    if (close >= to) return -1
                    if (bytes[close] == QUOTE) {
                        if (close + 1 >= to || bytes[close + 1] != QUOTE) break
                        close++
                    }
                    close++
                }
                starts[count] = i
                i = close + 1
                ends[count++] = i
                if (i == to) return count
                if (bytes[i] != separator) return -1
            } else {
                var end = i
                while (end < to && bytes[end] != separator) end++
                starts[count] = i
                ends[count++] = end
                if (end == to) return count
                i = end
            }
            i++
        }
    }

    /** Whether the cell `bytes[from, to)` is quoted, its closing quote included. */
    fun isQuoted(bytes: ByteArray, from: Int, to: Int) =
        to - from >= 2 && bytes[from] == QUOTE && bytes[to - 1] == QUOTE

    /** Whether `bytes[from, to)` has no quote, so a quoted cell's content reads as written. */
    fun isPlain(bytes: ByteArray, from: Int, to: Int): Boolean {
        for (i in from..<to) if (bytes[i] == QUOTE) return false
        return true
    }

    /**
     * Decodes the cell `bytes[from, to)` to the UTF-8 text the table shows: a quoted cell without its quotes and
     * with `""` read as `"`, others as written. A quoted cell cut before its closing quote is shown up to the cut.
     */
    fun decode(bytes: ByteArray, from: Int, to: Int): ByteArray {
        if (from >= to || bytes[from] != QUOTE) return bytes.copyOfRange(from, to)
        val out = ByteArray(to - from - 1)
        var o = 0
        var i = from + 1
        while (i < to) {
            if (bytes[i] == QUOTE) {
                if (i + 1 >= to || bytes[i + 1] != QUOTE) break
                i++
            }
            out[o++] = bytes[i++]
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
