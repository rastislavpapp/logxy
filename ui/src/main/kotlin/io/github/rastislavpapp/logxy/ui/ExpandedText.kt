package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.query.Marker

/**
 * The whole [text] an expanded row's cell shows: its first line, cut at 1 MB rather than the collapsed cell's 4 KB,
 * then the record's later lines, tabs expanded. Keeps its last wrap and marks, as painting asks for them over and
 * over and the text can be long.
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

    /** What the filter's [marker] marks in [text], for rules on [field]; see [Marker.ranges]. */
    fun matches(marker: Marker, field: String): IntArray = matches.of(marker, field, text)

    /** What the find bar's [marker] marks in [text]. */
    fun found(marker: Marker, field: String): IntArray = found.of(marker, field, text)

    /** The marks of the last marker asked for. */
    private class Marks {
        private var marker: Marker? = null
        private var field: String? = null
        private var ranges = IntArray(0)

        fun of(marker: Marker, field: String, text: String): IntArray {
            if (marker !== this.marker || field != this.field) {
                ranges = marker.ranges(field, text, Marker.DETAIL_BUDGET_NANOS)
                this.marker = marker
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
