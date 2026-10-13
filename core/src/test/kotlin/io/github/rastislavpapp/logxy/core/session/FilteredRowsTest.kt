package io.github.rastislavpapp.logxy.core.session

import kotlin.test.Test
import kotlin.test.assertEquals

class FilteredRowsTest {

    private fun FilteredRows.toList() = List(size) { this[it] }

    @Test
    fun `indexOf finds a record or the next one`() {
        val rows = FilteredRows(intArrayOf(2, 5, 9))
        assertEquals(0, rows.indexOf(2))
        assertEquals(1, rows.indexOf(3))
        assertEquals(2, rows.indexOf(9))
        assertEquals(-1, rows.indexOf(10))
    }

    @Test
    fun `replaceFrom keeps earlier records and grows beyond the first array`() {
        val rows = FilteredRows(intArrayOf(2, 5, 9))
        assertEquals(2, rows.replaceFrom(9, intArrayOf(10, 11, 12, 13)))
        assertEquals(listOf(2, 5, 10, 11, 12, 13), rows.toList())
        // The last record may stop matching: it is replaced by nothing.
        assertEquals(5, rows.replaceFrom(13, intArrayOf()))
        assertEquals(listOf(2, 5, 10, 11, 12), rows.toList())
        assertEquals(5, rows.replaceFrom(20, intArrayOf(20)))
        assertEquals(listOf(2, 5, 10, 11, 12, 20), rows.toList())
        assertEquals(3, rows.indexOf(11))
    }

    @Test
    fun `next and previous wrap around`() {
        val rows = FilteredRows(intArrayOf(2, 5, 9))
        assertEquals(5, rows.next(2, forward = true, inclusive = false))
        assertEquals(2, rows.next(9, forward = true, inclusive = false))
        assertEquals(9, rows.next(2, forward = false, inclusive = false))
        assertEquals(5, rows.next(9, forward = false, inclusive = false))
    }

    @Test
    fun `next from a record that is not one of the rows`() {
        val rows = FilteredRows(intArrayOf(2, 5, 9))
        assertEquals(5, rows.next(3, forward = true, inclusive = false))
        assertEquals(2, rows.next(3, forward = false, inclusive = true))
        // Before the first and after the last.
        assertEquals(2, rows.next(0, forward = true, inclusive = false))
        assertEquals(9, rows.next(0, forward = false, inclusive = false))
        assertEquals(2, rows.next(12, forward = true, inclusive = false))
        assertEquals(9, rows.next(12, forward = false, inclusive = false))
    }

    @Test
    fun `inclusive lets the current record count`() {
        val rows = FilteredRows(intArrayOf(2, 5, 9))
        assertEquals(5, rows.next(5, forward = true, inclusive = true))
        assertEquals(5, rows.next(5, forward = false, inclusive = true))
        assertEquals(9, rows.next(6, forward = true, inclusive = true))
        val one = FilteredRows(intArrayOf(4))
        assertEquals(4, one.next(4, forward = true, inclusive = false))
    }

    @Test
    fun `next among no rows is none`() {
        val rows = FilteredRows(IntArray(0))
        assertEquals(-1, rows.next(0, forward = true, inclusive = true))
        // Rows past the size do not count.
        val shrunk = FilteredRows(intArrayOf(2, 5, 9)).apply { replaceFrom(5, IntArray(0)) }
        assertEquals(2, shrunk.next(2, forward = true, inclusive = false))
        assertEquals(0, shrunk.positionOf(5))
    }

    @Test
    fun `positionOf counts from one`() {
        val rows = FilteredRows(intArrayOf(2, 5, 9))
        assertEquals(1, rows.positionOf(2))
        assertEquals(3, rows.positionOf(9))
        assertEquals(0, rows.positionOf(6))
    }
}
