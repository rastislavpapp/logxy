package io.github.rastislavpapp.logxy.ui

/**
 * [text]'s lines broken every [width] characters, as a monospaced font shows them in a column of that width; the
 * first line shown breaks at [firstWidth], leaving room for what follows it.
 */
internal class WrappedText(val text: String, val width: Int, val firstWidth: Int) {

    /** Where each line shown starts in [text]: counted first, then filled in. */
    private val starts: IntArray = IntArray(lineStarts { }).also { starts ->
        var i = 0
        lineStarts { starts[i++] = it }
    }

    /** Calls [visit] with the start of each line shown, and returns how many lines there are. */
    private inline fun lineStarts(visit: (Int) -> Unit): Int {
        var count = 0
        var start = 0
        var limit = firstWidth.coerceAtLeast(1)
        while (true) {
            visit(start)
            count++
            var newline = text.indexOf('\n', start)
            if (newline < 0) newline = text.length
            start = when {
                newline - start > limit -> start + limit
                newline < text.length -> newline + 1
                else -> return count
            }
            limit = width.coerceAtLeast(1)
        }
    }

    val lineCount: Int get() = starts.size

    fun start(line: Int): Int = starts[line]

    /** Where [line] ends, before its `\n` if it has one. */
    fun end(line: Int): Int {
        if (line + 1 == starts.size) return text.length
        val next = starts[line + 1]
        return if (text[next - 1] == '\n') next - 1 else next
    }

    /** The line shown that holds [offset] of [text]. */
    fun lineOf(offset: Int): Int {
        val i = starts.binarySearch(offset)
        return if (i >= 0) i else -i - 2
    }
}
