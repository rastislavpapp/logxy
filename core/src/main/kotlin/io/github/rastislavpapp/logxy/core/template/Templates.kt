package io.github.rastislavpapp.logxy.core.template

import io.github.rastislavpapp.logxy.core.Bytes

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

    /** Space [variableNumber] needs for the placeholders it finds: a count, then three numbers each. */
    const val PLACES_SIZE = 1 + 3 * MAX_BYTES

    /**
     * Writes the template of the text `bytes[from, to)` (UTF-8) into [out] (at least [OUT_SIZE] long); returns
     * its length.
     */
    fun mask(bytes: ByteArray, from: Int, to: Int, out: ByteArray): Int = mask(bytes, from, to, out, null)

    /**
     * As [mask], also noting in [places] (if not null, at least [PLACES_SIZE] long) the placeholders written: their
     * count first, then per placeholder its position in [out] and where its value starts and ends in [bytes].
     */
    private fun mask(bytes: ByteArray, from: Int, to: Int, out: ByteArray, places: IntArray?): Int {
        places?.set(0, 0)
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
                if (places != null) {
                    val at = 1 + 3 * places[0]++
                    places[at] = n
                    places[at + 1] = valueStart
                    places[at + 2] = valueEnd
                }
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

    /**
     * Variables of [template]: its placeholders, counted from the left as [String.split] finds them. A literal `{}` in
     * the message counts too, as the template cannot tell it apart.
     */
    fun variableCount(template: String): Int = template.split(PLACEHOLDER).size - 1

    /**
     * The value at [variable] (counted as [variableCount] does) of the template of the text `bytes[from, to)`: the
     * number its word starts with, as in `12` of `12ms`; NaN if the variable is missing, a literal `{}`, or its word
     * does not start with a number or has more digits after it (`10.0.3.17`, `0x1F`, `3fa9c1d2e5`). [out] and
     * [places] are scratch space, at least [OUT_SIZE] and [PLACES_SIZE] long.
     */
    fun variableNumber(bytes: ByteArray, from: Int, to: Int, variable: Int, out: ByteArray, places: IntArray): Double {
        var number = Double.NaN
        forEachVariable(bytes, from, to, out, places) { found, start, end ->
            if (found == variable && start >= 0) number = leadingNumber(bytes, start, end)
            found == variable
        }
        return number
    }

    /** [variableNumber] of each of [variables], masking once, written into [into] from [at]. */
    fun variableNumbers(
        bytes: ByteArray,
        from: Int,
        to: Int,
        variables: IntArray,
        into: DoubleArray,
        at: Int,
        out: ByteArray,
        places: IntArray,
    ) {
        into.fill(Double.NaN, at, at + variables.size)
        val last = variables.max()
        forEachVariable(bytes, from, to, out, places) { found, start, end ->
            if (start >= 0) {
                for (s in variables.indices) if (variables[s] == found) into[at + s] = leadingNumber(bytes, start, end)
            }
            found >= last
        }
    }

    /** [variableNumber] of [text]. */
    fun variableNumber(text: String, variable: Int): Double {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return variableNumber(bytes, 0, bytes.size, variable, ByteArray(OUT_SIZE), IntArray(PLACES_SIZE))
    }

    /**
     * The variable (counted as [variableCount] does) whose word in [text] holds the character at [offset]; -1 if
     * none.
     */
    fun variableAt(text: String, offset: Int): Int {
        if (offset !in text.indices) return -1
        val bytes = text.toByteArray(Charsets.UTF_8)
        val at = text.substring(0, offset).toByteArray(Charsets.UTF_8).size
        var variable = -1
        forEachVariable(bytes, 0, bytes.size, ByteArray(OUT_SIZE), IntArray(PLACES_SIZE)) { found, start, end ->
            if (at in start..<end) variable = found
            variable >= 0 || start > at
        }
        return variable
    }

    /**
     * The word of [text] at [variable], as the template leaves it out; null if there is no such variable or it is
     * literal.
     */
    fun variableWord(text: String, variable: Int): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var word: String? = null
        forEachVariable(bytes, 0, bytes.size, ByteArray(OUT_SIZE), IntArray(PLACES_SIZE)) { found, start, end ->
            if (found == variable && start >= 0) word = String(bytes, start, end - start, Charsets.UTF_8)
            found == variable
        }
        return word
    }

    /**
     * Masks the text `bytes[from, to)`, then calls [visit] with each variable of its template in turn and where the
     * variable's word starts and ends in [bytes] (both -1 for a literal `{}`), until it returns true.
     */
    private inline fun forEachVariable(
        bytes: ByteArray,
        from: Int,
        to: Int,
        out: ByteArray,
        places: IntArray,
        visit: (variable: Int, start: Int, end: Int) -> Boolean,
    ) {
        val n = mask(bytes, from, to, out, places)
        var variable = 0
        var placed = 0
        var i = 0
        while (i < n - 1) {
            if (out[i] != OPEN || out[i + 1] != CLOSE) {
                i++
                continue
            }
            // A placeholder written at i, or a literal `{}` copied there.
            while (placed < places[0] && places[1 + 3 * placed] < i) placed++
            val at = 1 + 3 * placed
            val written = placed < places[0] && places[at] == i
            val stop = if (written) visit(variable, places[at + 1], places[at + 2]) else visit(variable, -1, -1)
            if (stop) return
            variable++
            i += 2
        }
    }

    /** The number `bytes[from, to)` starts with, as described at [variableNumber]. */
    private fun leadingNumber(bytes: ByteArray, from: Int, to: Int): Double {
        fun digit(i: Int) = bytes[i] in ZERO..NINE
        var i = from
        if (i < to && (bytes[i] == MINUS || bytes[i] == PLUS)) i++
        val digits = i
        while (i < to && digit(i)) i++
        if (i == digits) return Double.NaN
        if (i + 1 < to && bytes[i] == DOT && digit(i + 1)) {
            i += 2
            while (i < to && digit(i)) i++
        }
        // A unit may follow, but no more digits: those make an address, a version or an id.
        for (j in i..<to) if (digit(j)) return Double.NaN
        return Bytes.parseNumber(bytes, from, i)
    }

    private const val DOT = '.'.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val OPEN = '{'.code.toByte()
    private const val CLOSE = '}'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val ZERO = '0'.code.toByte()
    private const val NINE = '9'.code.toByte()

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
