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
}
