package io.github.rastislavpapp.logxy.core.parse

import java.io.ByteArrayOutputStream

/**
 * Finds and indents XML elements in UTF-8 text by reading its tags, without an XML parser. Nothing is resolved, neither
 * a DTD nor an entity, so `&e;` stays as written.
 *
 * Only the tags are checked: their names, their quoted attributes, and that each end tag closes the element opened
 * last. Text between tags is taken as it is, except that a `<` starting no tag, comment, CDATA section, processing
 * instruction or DOCTYPE makes the elements around it not well-formed.
 */
internal object Xml {

    private const val LT = '<'.code.toByte()
    private const val GT = '>'.code.toByte()
    private const val SLASH = '/'.code.toByte()
    private const val EQUALS = '='.code.toByte()
    private const val QUOTE = '"'.code.toByte()
    private const val APOSTROPHE = '\''.code.toByte()
    private const val OPEN_BRACKET = '['.code.toByte()
    private const val CLOSE_BRACKET = ']'.code.toByte()

    private val COMMENT = "<!--".toByteArray()
    private val COMMENT_END = "-->".toByteArray()
    private val CDATA = "<![CDATA[".toByteArray()
    private val CDATA_END = "]]>".toByteArray()
    private val INSTRUCTION = "<?".toByteArray()
    private val INSTRUCTION_END = "?>".toByteArray()
    private val DOCTYPE = "<!DOCTYPE".toByteArray()

    private val INDENT = "  ".toByteArray()

    /**
     * The well-formed elements in `bytes`, as start and end index pairs in order, none inside another. An element
     * inside one that is not well-formed, such as `<b/>` in `<a><b/>`, counts on its own.
     */
    fun elements(bytes: ByteArray): IntArray {
        val markup = Markup(bytes, bytes.size)
        // The open elements: where each starts, and its name's range.
        var open = IntArray(16)
        var depth = 0
        var found = IntArray(16)
        var count = 0
        fun complete(from: Int, to: Int) {
            if (count + 2 > found.size) found = found.copyOf(found.size * 2)
            found[count++] = from
            found[count++] = to
        }
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] != LT) {
                i++
                continue
            }
            when (markup.read(i)) {
                Markup.START -> {
                    if (depth * 3 + 3 > open.size) open = open.copyOf(open.size * 2)
                    open[depth * 3] = i
                    open[depth * 3 + 1] = markup.nameFrom
                    open[depth * 3 + 2] = markup.nameTo
                    depth++
                }
                Markup.EMPTY -> complete(i, markup.end)
                Markup.END -> {
                    val top = (depth - 1) * 3
                    if (depth > 0 && sameName(bytes, open[top + 1], open[top + 2], markup.nameFrom, markup.nameTo)) {
                        depth--
                        complete(open[top], markup.end)
                    } else {
                        depth = 0
                    }
                }
                Markup.OTHER -> {}
                // A `<` that starts no markup may stand in text outside elements, but not inside one.
                else -> depth = 0
            }
            i = if (markup.kind == Markup.NONE) i + 1 else markup.end
        }
        return outermost(found, count)
    }

    /**
     * The ranges among the first [count] values of [found] that no other range holds. Ranges come in the order their
     * elements end, so an element follows those inside it.
     */
    private fun outermost(found: IntArray, count: Int): IntArray {
        val result = IntArray(count)
        var size = 0
        var r = count - 2
        // Walking back, an element comes before those inside it, which then end before the last range kept starts.
        while (r >= 0) {
            if (size == 0 || found[r + 1] <= result[size - 2]) {
                result[size++] = found[r]
                result[size++] = found[r + 1]
            }
            r -= 2
        }
        val ordered = IntArray(size)
        for (k in 0..<size step 2) {
            ordered[k] = result[size - 2 - k]
            ordered[k + 1] = result[size - 1 - k]
        }
        return ordered
    }

    /**
     * The element `bytes[from, to)`, one that [elements] found, indented: each child element, comment, CDATA section
     * or processing instruction on a line of its own, and text trimmed. An element with nothing but text inside stays
     * on one line, its text as written.
     */
    fun pretty(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream(to - from + 64)
        val markup = Markup(bytes, to)
        var depth = 0
        fun line(lineFrom: Int, lineTo: Int) {
            if (out.size() > 0) out.write('\n'.code)
            repeat(depth) { out.write(INDENT) }
            out.write(bytes, lineFrom, lineTo - lineFrom)
        }
        var i = from
        while (i < to) {
            if (bytes[i] != LT) {
                val textEnd = textEnd(bytes, i, to)
                var start = i
                var end = textEnd
                while (start < end && Json.isSpace(bytes[start])) start++
                while (end > start && Json.isSpace(bytes[end - 1])) end--
                if (start < end) line(start, end)
                i = textEnd
                continue
            }
            val kind = markup.read(i)
            val end = markup.end
            when (kind) {
                Markup.START -> {
                    val textEnd = textEnd(bytes, end, to)
                    if (textEnd < to && markup.read(textEnd) == Markup.END) {
                        line(i, markup.end)
                        i = markup.end
                        continue
                    }
                    line(i, end)
                    depth++
                }
                Markup.END -> {
                    depth--
                    line(i, end)
                }
                else -> line(i, end)
            }
            i = end
        }
        return out.toByteArray()
    }

    /** Where the text from [from] on ends: the index of the next `<`, or [to]. */
    private fun textEnd(bytes: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to && bytes[i] != LT) i++
        return i
    }

    private fun sameName(bytes: ByteArray, from: Int, to: Int, otherFrom: Int, otherTo: Int) =
        java.util.Arrays.equals(bytes, from, to, bytes, otherFrom, otherTo)

    /** Whether [b] may start a name: an ASCII letter, `_`, `:`, or any byte of a non-ASCII character. */
    private fun startsName(b: Byte): Boolean {
        val letter = b.toInt().toChar().lowercaseChar() in 'a'..'z'
        return letter || b < 0 || b == '_'.code.toByte() || b == ':'.code.toByte()
    }

    private fun inName(b: Byte) =
        startsName(b) || b in '0'.code.toByte()..'9'.code.toByte() || b == '-'.code.toByte() || b == '.'.code.toByte()

    private fun startsWith(bytes: ByteArray, at: Int, to: Int, prefix: ByteArray) =
        at + prefix.size <= to && java.util.Arrays.equals(bytes, at, at + prefix.size, prefix, 0, prefix.size)

    /** The index after the first [terminator] from [from] on, or -1 if none ends before [to]. */
    private fun after(bytes: ByteArray, from: Int, to: Int, terminator: ByteArray): Int {
        var i = from
        while (i + terminator.size <= to) {
            if (java.util.Arrays.equals(bytes, i, i + terminator.size, terminator, 0, terminator.size)) {
                return i + terminator.size
            }
            i++
        }
        return -1
    }

    /** Reads the markup that starts at a `<` in `bytes`, up to [to]: its [kind], [end] and, for tags, its name. */
    private class Markup(private val bytes: ByteArray, private val to: Int) {
        var kind = NONE
            private set
        var end = -1
            private set
        var nameFrom = 0
            private set
        var nameTo = 0
            private set

        /**
         * Where an unclosed comment, CDATA section, processing instruction or DOCTYPE was found, one index for each of
         * the four kinds, or `Int.MAX_VALUE` while none was. A later one of the same kind is then taken as unclosed
         * without reading to the end of the text again. Otherwise a text of many unclosed `<!--` would take time
         * growing with the square of its length.
         */
        private val unclosedFrom = IntArray(DOCTYPE_SEARCH + 1) { Int.MAX_VALUE }

        /** Reads the markup at [from] and returns its [kind]; [NONE] if the `<` there starts none. */
        fun read(from: Int): Int {
            val (kind, end) = when {
                startsWith(bytes, from, to, COMMENT) -> OTHER to otherEnd(COMMENT_SEARCH, from)
                startsWith(bytes, from, to, CDATA) -> OTHER to otherEnd(CDATA_SEARCH, from)
                startsWith(bytes, from, to, INSTRUCTION) -> OTHER to otherEnd(INSTRUCTION_SEARCH, from)
                startsWith(bytes, from, to, DOCTYPE) -> OTHER to otherEnd(DOCTYPE_SEARCH, from)
                from + 1 < to && bytes[from + 1] == SLASH -> END to endTagEnd(from + 2)
                else -> tag(from + 1)
            }
            this.kind = if (end < 0) NONE else kind
            this.end = end
            return this.kind
        }

        /** The index after the comment, CDATA section, processing instruction or DOCTYPE at [from], or -1. */
        private fun otherEnd(search: Int, from: Int): Int {
            if (from >= unclosedFrom[search]) return -1
            val end = when (search) {
                COMMENT_SEARCH -> after(bytes, from + COMMENT.size, to, COMMENT_END)
                CDATA_SEARCH -> after(bytes, from + CDATA.size, to, CDATA_END)
                INSTRUCTION_SEARCH -> after(bytes, from + INSTRUCTION.size, to, INSTRUCTION_END)
                else -> doctypeEnd(from + DOCTYPE.size)
            }
            if (end < 0) unclosedFrom[search] = from
            return end
        }

        /**
         * Reads a start tag or empty-element tag whose name starts at [from]. Returns its kind, [START] or [EMPTY],
         * paired with the index after it; `NONE to -1` if no such tag is there.
         */
        private fun tag(from: Int): Pair<Int, Int> {
            var i = name(from)
            if (i < 0) return NONE to -1
            while (true) {
                val spaceFrom = i
                i = Json.skipSpace(bytes, i, to)
                if (i >= to) return NONE to -1
                if (bytes[i] == GT) return START to i + 1
                if (bytes[i] == SLASH) {
                    val closed = i + 1 < to && bytes[i + 1] == GT
                    return if (closed) EMPTY to i + 2 else NONE to -1
                }
                // An attribute must follow white space.
                if (i == spaceFrom) return NONE to -1
                i = attribute(i)
                if (i < 0) return NONE to -1
            }
        }

        /** The index after the attribute `name="value"` at [from], or -1 if none is there. */
        private fun attribute(from: Int): Int {
            var i = skipName(from)
            if (i < 0) return -1
            i = Json.skipSpace(bytes, i, to)
            if (i >= to || bytes[i] != EQUALS) return -1
            i = Json.skipSpace(bytes, i + 1, to)
            if (i >= to || (bytes[i] != QUOTE && bytes[i] != APOSTROPHE)) return -1
            val quote = bytes[i]
            i++
            while (i < to && bytes[i] != quote) {
                if (bytes[i] == LT) return -1
                i++
            }
            return if (i < to) i + 1 else -1
        }

        /** The index after the end tag whose name starts at [from], or -1 if none is there. */
        private fun endTagEnd(from: Int): Int {
            var i = name(from)
            if (i < 0) return -1
            i = Json.skipSpace(bytes, i, to)
            return if (i < to && bytes[i] == GT) i + 1 else -1
        }

        /** Reads the tag name at [from] into [nameFrom] and [nameTo]; returns its end, or -1 if none is there. */
        private fun name(from: Int): Int {
            val end = skipName(from)
            if (end < 0) return -1
            nameFrom = from
            nameTo = end
            return end
        }

        private fun skipName(from: Int): Int {
            if (from >= to || !startsName(bytes[from])) return -1
            var i = from + 1
            while (i < to && inName(bytes[i])) i++
            return i
        }

        /** The index after a DOCTYPE whose name starts after [from]: past its quoted ids and `[…]` subset. */
        private fun doctypeEnd(from: Int): Int {
            var i = from
            var inSubset = false
            while (i < to) {
                when (bytes[i]) {
                    QUOTE, APOSTROPHE -> {
                        val quote = bytes[i]
                        i++
                        while (i < to && bytes[i] != quote) i++
                    }
                    OPEN_BRACKET -> inSubset = true
                    CLOSE_BRACKET -> inSubset = false
                    GT -> if (!inSubset) return i + 1
                }
                i++
            }
            return -1
        }

        companion object {
            const val NONE = 0
            const val START = 1
            const val EMPTY = 2
            const val END = 3
            const val OTHER = 4

            private const val COMMENT_SEARCH = 0
            private const val CDATA_SEARCH = 1
            private const val INSTRUCTION_SEARCH = 2
            private const val DOCTYPE_SEARCH = 3
        }
    }
}
