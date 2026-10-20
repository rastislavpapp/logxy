package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.query.Highlighter

/**
 * The whole [text] an expanded row's cell shows: the cell's own first line, then the record's later lines, tabs
 * expanded. Keeps its last wrap and marks, as painting asks for them over and over and the text can be long.
 */
internal class ExpandedText(val text: String) {

    private var wrapped: WrappedText? = null
    private val matches = Marks()
    private val found = Marks()

    /** [text] wrapped as [WrappedText] does. */
    fun wrapped(width: Int, firstWidth: Int): WrappedText {
        val last = wrapped
        if (last != null && last.width == width && last.firstWidth == firstWidth) return last
        return WrappedText(text, width, firstWidth).also { wrapped = it }
    }

    /** What the filter's [highlighter] marks in [text], for rules on [field]; see [Highlighter.ranges]. */
    fun matches(highlighter: Highlighter, field: String): IntArray = matches.of(highlighter, field, text)

    /** What the find bar's [highlighter] marks in [text]. */
    fun found(highlighter: Highlighter, field: String): IntArray = found.of(highlighter, field, text)

    /** The marks of the last highlighter asked for. */
    private class Marks {
        private var highlighter: Highlighter? = null
        private var field: String? = null
        private var ranges = IntArray(0)

        fun of(highlighter: Highlighter, field: String, text: String): IntArray {
            if (highlighter !== this.highlighter || field != this.field) {
                ranges = highlighter.ranges(field, text, Highlighter.DETAIL_BUDGET_NANOS)
                this.highlighter = highlighter
                this.field = field
            }
            return ranges
        }
    }

    companion object {
        /** The cell's [firstLine] followed by [laterLines], each line with its tabs expanded and no `\r`. */
        fun of(firstLine: String, laterLines: String): ExpandedText {
            if (laterLines.isEmpty()) return ExpandedText(firstLine)
            val text = StringBuilder(firstLine.length + laterLines.length + 1).append(firstLine)
            for (line in laterLines.split('\n')) {
                text.append('\n').append(LogTableModel.expandTabs(line.removeSuffix("\r")))
            }
            return ExpandedText(text.toString())
        }
    }
}
