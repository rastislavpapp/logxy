package io.github.rastislavpapp.logxy.ui

import java.awt.Font
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellCutTest {

    private val label = JLabel().apply { font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val charWidth = label.getFontMetrics(label.font).charWidth('0')

    /** A cut of a cell [chars] characters of the monospaced font wide. */
    private fun cut(chars: Int) = CellCut(label, chars * charWidth)

    @Test
    fun `a value as wide as the cell is not cut`() {
        val value = "a".repeat(20)
        assertFalse(cut(20).isCut(value))
        assertEquals(20, cut(20).shownLength(value))
    }

    @Test
    fun `a value one character too wide is cut before the dots`() {
        val value = "a".repeat(21)
        assertTrue(cut(20).isCut(value))
        assertEquals(20 - CellCut.CLIP.length, cut(20).shownLength(value))
    }

    // How wide CJK characters and emoji are depends on the fonts installed: measured, not assumed.
    @Test
    fun `values are cut by their drawn width, not their character count`() {
        val metrics = label.getFontMetrics(label.font)
        for (value in listOf("日本語のログメッセージです", "ok 😀 done", "été")) {
            val width = metrics.stringWidth(value)
            assertFalse(CellCut(label, width).isCut(value), value)
            assertTrue(CellCut(label, width - 1).isCut(value), value)
        }
    }

    @Test
    fun `an empty value is not cut`() {
        assertFalse(cut(1).isCut(""))
    }

    @Test
    fun `the displayed text tells how much shows`() {
        assertEquals(5, CellCut.shownLength("hello", "hello"))
        assertEquals(2, CellCut.shownLength("hello", "he..."))
        assertEquals(0, CellCut.shownLength("hello", ".."))
    }
}
