package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.query.Highlighter
import java.awt.Color
import javax.swing.JTextArea
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent

/** Background of text matched by the filter, translucent to suit light and dark looks. */
val HIGHLIGHT_COLOR = Color(255, 200, 0, 110)

/** Background of text the find bar found; unlike the filter's, also shown on selected rows. */
val FOUND_COLOR = Color(40, 200, 90, 130)

/** A [DetailPane] of plain text in the monospaced font, without syntax colours. */
class PlainDetailArea : JTextArea(), DetailPane {

    private val painter = DefaultHighlighter.DefaultHighlightPainter(HIGHLIGHT_COLOR)
    private val foundPainter = DefaultHighlighter.DefaultHighlightPainter(FOUND_COLOR)
    private var foundRanges = IntArray(0)

    override val component: JTextComponent get() = this

    override var matches: Highlighter = Highlighter.NONE
        set(value) {
            field = value
            mark()
        }

    override var found: Highlighter = Highlighter.NONE
        set(value) {
            field = value
            mark()
        }

    override val firstFound: Int get() = foundRanges.firstOrNull() ?: 0

    init {
        isEditable = false
        lineWrap = true
    }

    override fun updateUI() {
        super.updateUI()
        // Also called from the superclass constructor, before the look is read.
        font = look.monospacedFont
    }

    override fun show(text: String) {
        this.text = text
        mark()
    }

    private fun mark() {
        highlighter.removeAllHighlights()
        val ranges = matches.ranges(null, text, Highlighter.DETAIL_BUDGET_NANOS)
        for (i in ranges.indices step 2) highlighter.addHighlight(ranges[i], ranges[i + 1], painter)
        foundRanges = found.ranges(null, text, Highlighter.DETAIL_BUDGET_NANOS)
        for (i in foundRanges.indices step 2) highlighter.addHighlight(foundRanges[i], foundRanges[i + 1], foundPainter)
    }
}
