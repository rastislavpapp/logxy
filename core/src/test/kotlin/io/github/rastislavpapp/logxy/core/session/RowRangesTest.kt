package io.github.rastislavpapp.logxy.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RowRangesTest {

    private fun RowRanges.toList() = buildList { forEachRange { start, end -> add(start..<end) } }

    @Test
    fun `adding merges adjacent and overlapping ranges`() {
        val ranges = RowRanges()
        ranges.add(10, 20)
        ranges.add(30, 40)
        ranges.add(20, 25)
        assertEquals(listOf(10..<25, 30..<40), ranges.toList())
        ranges.add(5, 35)
        assertEquals(listOf(5..<40), ranges.toList())
        assertEquals(35, ranges.count)
    }

    @Test
    fun `removing from the middle splits a range`() {
        val ranges = RowRanges()
        ranges.add(0, 10)
        ranges.add(20, 30)
        ranges.remove(5, 7)
        ranges.remove(8, 25)
        assertEquals(listOf(0..<5, 7..<8, 25..<30), ranges.toList())
        assertEquals(11, ranges.count)
    }

    @Test
    fun `contains is exact at the edges`() {
        val ranges = RowRanges()
        ranges.add(10, 20)
        assertEquals(listOf(false, true, true, false), listOf(9, 10, 19, 20).map(ranges::contains))
        assertEquals(10, ranges.min)
        assertEquals(19, ranges.max)
        ranges.clear()
        assertEquals(-1, ranges.min)
        assertEquals(-1, ranges.max)
        assertFalse(ranges.contains(10))
    }

    @Test
    fun `inserted rows shift the rows after them and are not added`() {
        val ranges = RowRanges()
        ranges.add(0, 10)
        ranges.add(20, 30)
        ranges.insert(5, 3)
        assertEquals(listOf(0..<5, 8..<13, 23..<33), ranges.toList())
        assertEquals(20, ranges.count)
    }

    @Test
    fun `deleted rows go and the rows after them move up`() {
        val ranges = RowRanges()
        ranges.add(0, 10)
        ranges.add(20, 30)
        ranges.delete(5, 25)
        // The rest of both ranges meets at row 5 and becomes one.
        assertEquals(listOf(0..<10), ranges.toList())
        ranges.delete(8, 100)
        assertEquals(listOf(0..<8), ranges.toList())
    }

    @Test
    fun `the first and last row within bounds`() {
        val ranges = RowRanges()
        ranges.add(10, 20)
        ranges.add(40, 50)
        assertEquals(15, ranges.firstIn(15, 45))
        assertEquals(40, ranges.firstIn(20, 45))
        assertEquals(19, ranges.lastIn(15, 40))
        assertEquals(44, ranges.lastIn(15, 45))
        assertEquals(-1, ranges.firstIn(20, 40))
        assertEquals(-1, ranges.lastIn(20, 40))
    }

    @Test
    fun `half of all possible rows is one range`() {
        val ranges = RowRanges()
        ranges.add(0, Int.MAX_VALUE / 2)
        ranges.remove(1, 2)
        assertEquals(Int.MAX_VALUE / 2 - 1, ranges.count)
        assertEquals(2, ranges.rangeCount)
        assertEquals(Int.MAX_VALUE / 2 - 1, ranges.max)
    }
}
