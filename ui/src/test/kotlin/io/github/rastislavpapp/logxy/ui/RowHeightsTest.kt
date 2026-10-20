package io.github.rastislavpapp.logxy.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RowHeightsTest {

    @Test
    fun `rows of the same height lie one after another`() {
        val heights = RowHeights(20, IntArray(0), IntArray(0))
        assertEquals(20, heights.height(7))
        assertEquals(140, heights.top(7))
        assertEquals(7, heights.rowAt(159, 100))
        assertEquals(2000, heights.top(100))
    }

    @Test
    fun `taller rows push the rows below them down`() {
        // Row 5 is 100 high, row 2 is 50 high.
        val heights = RowHeights(20, intArrayOf(5, 2), intArrayOf(100, 50))
        assertEquals(50, heights.height(2))
        assertEquals(100, heights.height(5))
        assertEquals(40, heights.top(2))
        assertEquals(90, heights.top(3))
        assertEquals(130, heights.top(5))
        assertEquals(230, heights.top(6))
        assertEquals(20 * 10 + 30 + 80, heights.top(10))
    }

    @Test
    fun `the row at a point counts the taller rows above it`() {
        val heights = RowHeights(20, intArrayOf(5, 2), intArrayOf(100, 50))
        assertEquals(2, heights.rowAt(40, 10))
        assertEquals(2, heights.rowAt(89, 10))
        assertEquals(3, heights.rowAt(90, 10))
        assertEquals(5, heights.rowAt(229, 10))
        assertEquals(6, heights.rowAt(230, 10))
        assertEquals(9, heights.rowAt(heights.top(10) - 1, 10))
    }

    @Test
    fun `many tall rows keep the rows' y an int`() {
        val tall = IntArray(1000) { it }
        val heights = RowHeights(20, tall, IntArray(1000) { 1 shl 22 })
        val end = heights.top(1_000_000)
        assertTrue(end > 0)
        assertEquals(999_999, heights.rowAt(end - 1, 1_000_000))
    }

    @Test
    fun `no row is outside the rows`() {
        val heights = RowHeights(20, intArrayOf(1), intArrayOf(60))
        assertEquals(-1, heights.rowAt(-1, 3))
        assertEquals(-1, heights.rowAt(heights.top(3), 3))
        assertEquals(-1, heights.rowAt(0, 0))
    }
}
