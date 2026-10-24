package io.github.rastislavpapp.logxy.ui

import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Tells whether a value fits in a cell [width] pixels wide, drawn in [component]'s font, and if not, how much of it
 * shows before Swing's "...". It runs the layout a label runs when it paints, rather than counting characters, so the
 * answer matches the screen: e.g. CJK characters are wider than '0', and an emoji is two chars in a String but one
 * on screen.
 */
internal class CellCut(private val component: JComponent, private val width: Int) {

    /** How many characters of [value] the cell shows, before the "..." if it cuts it. */
    fun shownLength(value: String): Int {
        val metrics = component.getFontMetrics(component.font)
        val view = Rectangle(0, 0, width, metrics.height)
        val displayed = SwingUtilities.layoutCompoundLabel(
            component, metrics, value, null, SwingConstants.CENTER, SwingConstants.LEADING, SwingConstants.CENTER,
            SwingConstants.TRAILING, view, Rectangle(), Rectangle(), 0,
        )
        return shownLength(value, displayed)
    }

    fun isCut(value: String): Boolean = shownLength(value) < value.length

    companion object {
        /** What Swing ends a label's text with when it does not fit. */
        const val CLIP = "..."

        /**
         * How many characters of [value] are visible in [displayed], the string Swing made of it to fit the cell:
         * [value] unchanged, or its start followed by [CLIP]. E.g. 2 for value "hello" displayed as "he...".
         * Swing returns that string, not a count; [value] tells it apart from a value that itself ends in "...".
         */
        fun shownLength(value: String, displayed: String): Int =
            if (displayed == value) value.length else (displayed.length - CLIP.length).coerceAtLeast(0)
    }
}
