package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.Bytes

/**
 * Text that every match of a regex contains, so a byte search can rule a record out before it is decoded and
 * matched. [bytes] are UTF-8; when [ignoreCase] they are ASCII lower case and only ASCII, so folding ASCII case
 * finds every match.
 */
internal class RequiredText private constructor(val bytes: ByteArray, val ignoreCase: Boolean) {

    /** Whether the UTF-8 text `bytes[from, to)` contains this text; when false, no match of the regex can. */
    fun foundIn(bytes: ByteArray, from: Int, to: Int): Boolean {
        val at = if (ignoreCase) {
            Bytes.indexOfIgnoreAsciiCase(bytes, this.bytes, from, to)
        } else {
            Bytes.indexOf(bytes, this.bytes, from, to)
        }
        return at >= 0
    }

    companion object {
        /** Shorter text is too common to rule out enough records to pay for the extra search. */
        private const val MIN_BYTES = 3

        /**
         * The longest run of literal text every match of the Java [regex] contains, compiled case-insensitively
         * (with Unicode case) when [ignoreCase]; null if it has none of [MIN_BYTES] or more, or uses syntax this
         * scanner does not follow. A null is always safe: the caller then runs the regex on every record.
         */
        fun of(regex: String, ignoreCase: Boolean): RequiredText? {
            val runs = try {
                Scanner(regex).scan(ignoreCase)
            } catch (_: UnsupportedSyntax) {
                return null
            }
            val longest = runs.map { run -> run.toRequiredText() }.maxByOrNull { it.bytes.size }
            if (longest == null || longest.bytes.size < MIN_BYTES) return null
            return longest
        }
    }

    /** Literal text a match contains, in one case mode. */
    private class Run(val text: String, val ignoreCase: Boolean) {
        fun toRequiredText(): RequiredText {
            val text = if (ignoreCase) text.lowercase() else text
            return RequiredText(text.toByteArray(Charsets.UTF_8), ignoreCase)
        }
    }

    private class UnsupportedSyntax : Exception()

    /** How a quantifier affects the atom before it. */
    private enum class Repeat { ONCE, OPTIONAL, AT_LEAST_ONCE }

    /**
     * A conservative walk over Java regex syntax. It collects literal runs a match must contain and throws
     * [UnsupportedSyntax] wherever it is unsure; a run never spans anything but plain literal characters.
     */
    private class Scanner(private val regex: String) {
        private var pos = 0

        fun scan(ignoreCase: Boolean): List<Run> {
            val runs = sequence(ignoreCase)
            if (pos != regex.length) throw UnsupportedSyntax()
            return runs
        }

        /**
         * The runs of the sequence starting at [pos], up to a `)` or the end, which is left unread. A sequence
         * with a `|` yields none: no single text is required of every alternative.
         */
        private fun sequence(startIgnoreCase: Boolean): List<Run> {
            val runs = ArrayList<Run>()
            val run = StringBuilder()
            var ignoreCase = startIgnoreCase
            var alternation = false
            fun endRun() {
                if (run.isNotEmpty()) runs += Run(run.toString(), ignoreCase)
                run.clear()
            }
            fun literal(c: Char, repeat: Repeat) {
                if (repeat == Repeat.OPTIONAL || !isLiteral(c, ignoreCase)) {
                    endRun()
                    return
                }
                run.append(c)
                if (repeat == Repeat.AT_LEAST_ONCE) endRun()
            }
            while (pos < regex.length && regex[pos] != ')') {
                when (val c = regex[pos++]) {
                    '|' -> {
                        alternation = true
                        endRun()
                    }
                    '(' -> {
                        endRun()
                        val caseMode = inlineFlags()
                        if (caseMode != null) {
                            ignoreCase = caseMode
                        } else {
                            runs += group(ignoreCase)
                        }
                    }
                    '[' -> {
                        skipClass()
                        endRun()
                        repeat()
                    }
                    '.', '^', '$' -> {
                        endRun()
                        repeat()
                    }
                    '*', '+', '?', '{' -> throw UnsupportedSyntax()
                    '\\' -> {
                        val text = escape()
                        if (text == null) {
                            endRun()
                            repeat()
                        } else {
                            // A quantifier after quoted text applies to its last character only.
                            for (i in 0..<text.lastIndex) literal(text[i], Repeat.ONCE)
                            if (text.isNotEmpty()) literal(text.last(), repeat())
                        }
                    }
                    else -> literal(c, repeat())
                }
            }
            endRun()
            return if (alternation) emptyList() else runs
        }

        /**
         * After a `(`: if it opens a flags-only group such as `(?-i)`, reads it and returns the case mode it sets
         * for the rest of the enclosing sequence; otherwise reads nothing and returns null.
         */
        private fun inlineFlags(): Boolean? {
            val end = flagsEnd(pos) ?: return null
            if (regex[end] != ')') return null
            val result = caseMode(pos + 1, end)
            pos = end + 1
            return result
        }

        /** The runs a group starting after its `(` contributes, reading through its `)` and quantifier. */
        private fun group(ignoreCase: Boolean): List<Run> {
            var mode = ignoreCase
            var required = true
            if (regex.startsWith("?", pos)) {
                val flagsEnd = flagsEnd(pos)
                when {
                    flagsEnd != null -> {
                        // `(?i:…)`; a flags-only `(?i)` was taken by inlineFlags.
                        mode = caseMode(pos + 1, flagsEnd)
                        pos = flagsEnd + 1
                    }
                    regex.startsWith("?:", pos) -> pos += 2
                    regex.startsWith("?=", pos) || regex.startsWith("?!", pos) || regex.startsWith("?>", pos) -> {
                        // Lookarounds match nothing themselves, and an atomic group may give up text a plain
                        // one would keep: neither is scanned for required text.
                        required = false
                        pos += 2
                    }
                    regex.startsWith("?<=", pos) || regex.startsWith("?<!", pos) -> {
                        required = false
                        pos += 3
                    }
                    regex.startsWith("?<", pos) -> {
                        val close = regex.indexOf('>', pos)
                        if (close < 0 || !regex.substring(pos + 2, close).all { it.isLetterOrDigit() }) {
                            throw UnsupportedSyntax()
                        }
                        pos = close + 1
                    }
                    else -> throw UnsupportedSyntax()
                }
            }
            val runs = sequence(mode)
            if (pos >= regex.length) throw UnsupportedSyntax()
            pos++
            if (repeat() == Repeat.OPTIONAL) required = false
            return if (required) runs else emptyList()
        }

        /** The index of the `)` or `:` ending flags such as `?i-i` that start at [from] with `?`, or null. */
        private fun flagsEnd(from: Int): Int? {
            if (!regex.startsWith("?", from)) return null
            var i = from + 1
            while (i < regex.length && (regex[i].isLetter() || regex[i] == '-')) i++
            if (i == from + 1 || i >= regex.length || (regex[i] != ')' && regex[i] != ':')) return null
            return i
        }

        /** The case mode after the flags `regex[from, to)`; only `i` and `-i` are followed. */
        private fun caseMode(from: Int, to: Int): Boolean = when (regex.substring(from, to)) {
            "i" -> true
            "-i" -> false
            else -> throw UnsupportedSyntax()
        }

        /**
         * After a `\`: the literal text the escape stands for (several characters for `\Q…\E`), or null for one
         * that matches something else, such as `\d`, `\b` or a backreference.
         */
        private fun escape(): String? {
            if (pos >= regex.length) throw UnsupportedSyntax()
            val c = regex[pos++]
            return when (c) {
                'Q' -> {
                    // Without an `\E`, the quote runs to the end.
                    var end = regex.indexOf("\\E", pos)
                    if (end < 0) end = regex.length
                    val text = regex.substring(pos, end)
                    pos = minOf(end + 2, regex.length)
                    text
                }
                't' -> "\t"
                'n' -> "\n"
                'r' -> "\r"
                'f' -> "\u000C"
                'a' -> "\u0007"
                'e' -> "\u001B"
                'x' -> {
                    if (regex.startsWith("{", pos)) throw UnsupportedSyntax()
                    hex(2)
                }
                'u' -> hex(4)
                'd', 'D', 'w', 'W', 's', 'S', 'h', 'H', 'v', 'V', 'R', 'X', 'B', 'A', 'z', 'Z', 'G' -> null
                'b' -> if (regex.startsWith("{", pos)) throw UnsupportedSyntax() else null
                'p', 'P' -> {
                    if (regex.startsWith("{", pos)) skipPast('}') else pos++
                    null
                }
                'k' -> {
                    if (!regex.startsWith("<", pos)) throw UnsupportedSyntax()
                    skipPast('>')
                    null
                }
                in '1'..'9' -> {
                    while (pos < regex.length && regex[pos].isDigit()) pos++
                    null
                }
                else -> if (c.isLetterOrDigit()) throw UnsupportedSyntax() else c.toString()
            }
        }

        /** The character written as [digits] hex digits at [pos]. */
        private fun hex(digits: Int): String {
            if (pos + digits > regex.length) throw UnsupportedSyntax()
            val code = regex.substring(pos, pos + digits).toIntOrNull(16) ?: throw UnsupportedSyntax()
            pos += digits
            return code.toChar().toString()
        }

        private fun skipPast(close: Char) {
            val at = regex.indexOf(close, pos)
            if (at < 0) throw UnsupportedSyntax()
            pos = at + 1
        }

        /** Skips a character class whose `[` was read, including nested classes. */
        private fun skipClass() {
            var depth = 1
            openClass()
            while (depth > 0) {
                if (pos >= regex.length) throw UnsupportedSyntax()
                when (regex[pos++]) {
                    '\\' -> {
                        if (regex.startsWith("Q", pos)) {
                            val end = regex.indexOf("\\E", pos)
                            if (end < 0) throw UnsupportedSyntax()
                            pos = end + 2
                        } else {
                            pos++
                        }
                    }
                    '[' -> {
                        depth++
                        openClass()
                    }
                    ']' -> depth--
                }
            }
        }

        /** Reads a class's leading `^`; a `]` right after the `[` is read differently by regex flavours. */
        private fun openClass() {
            if (regex.startsWith("^", pos)) pos++
            if (regex.startsWith("]", pos)) throw UnsupportedSyntax()
        }

        /** Reads the quantifier at [pos], if any; lazy and possessive ones count as optional. */
        private fun repeat(): Repeat {
            if (pos >= regex.length) return Repeat.ONCE
            val repeat = when (regex[pos]) {
                '?', '*' -> Repeat.OPTIONAL.also { pos++ }
                '+' -> Repeat.AT_LEAST_ONCE.also { pos++ }
                '{' -> bounds()
                else -> return Repeat.ONCE
            }
            if (pos < regex.length && (regex[pos] == '?' || regex[pos] == '+')) {
                pos++
                return Repeat.OPTIONAL
            }
            return repeat
        }

        /** Reads `{n}`, `{n,}` or `{n,m}`. */
        private fun bounds(): Repeat {
            val close = regex.indexOf('}', pos)
            if (close < 0) throw UnsupportedSyntax()
            val parts = regex.substring(pos + 1, close).split(',')
            val min = parts[0].toIntOrNull()
            val max = parts.getOrNull(1)
            val maxValid = max.isNullOrEmpty() || max.toIntOrNull() != null
            if (min == null || parts.size > 2 || !maxValid) throw UnsupportedSyntax()
            pos = close + 1
            return if (min == 0) Repeat.OPTIONAL else Repeat.AT_LEAST_ONCE
        }

        /**
         * Whether [c] is matched only by itself (and, when [ignoreCase], its other ASCII case) and so found by
         * a byte search. Case-insensitive Unicode matching also takes `İ` and `ı` for `i`, the Kelvin sign for
         * `k` and `ſ` for `s`; malformed UTF-8 decodes to U+FFFD; surrogates may pair up across escapes.
         */
        private fun isLiteral(c: Char, ignoreCase: Boolean): Boolean = when {
            c == '�' || c.isSurrogate() -> false
            !ignoreCase -> true
            else -> c.code < 128 && c.lowercaseChar() !in "iks"
        }
    }
}
