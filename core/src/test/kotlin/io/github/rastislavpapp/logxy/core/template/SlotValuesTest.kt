package io.github.rastislavpapp.logxy.core.template

import kotlin.test.Test
import kotlin.test.assertEquals

class SlotValuesTest {

    private fun values(vararg entries: Pair<Long, Double>): SlotValues {
        val times = LongArray(entries.size) { entries[it].first }
        val rows = IntArray(entries.size) { it }
        return SlotValues(rows, times, DoubleArray(entries.size) { entries[it].second }, entries.size)
    }

    private fun SlotSlices.row(slice: Int): List<Double> {
        val counts = listOf(records[slice].toDouble(), withoutNumber[slice].toDouble())
        return counts + listOf(min[slice], p50[slice], p99[slice], max[slice], sum[slice], average[slice])
    }

    @Test
    fun `sums up each slice of time`() {
        val values = values(
            -1L to 9.0,
            1L to 5.0,
            2L to Double.NaN,
            5L to 1.0,
            9L to 3.0,
            12L to 100.0,
            15L to Double.NaN,
            25L to 7.0,
            35L to Double.NaN,
            40L to 1.0,
        )
        val slices = values.slices(0, 10, 4)
        val nan = Double.NaN
        // Records, without a number, min, p50, p99, max, sum, average.
        assertEquals(listOf(4.0, 1.0, 1.0, 3.0, 5.0, 5.0, 9.0, 3.0), slices.row(0))
        assertEquals(listOf(2.0, 1.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0), slices.row(1))
        assertEquals(listOf(1.0, 0.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0), slices.row(2))
        assertEquals(listOf(1.0, 1.0, nan, nan, nan, nan, nan, nan), slices.row(3))
    }

    @Test
    fun `percentiles are values that occur, by nearest rank`() {
        // 1 to 200, shuffled: the median is the 100th value, p99 the 198th.
        val shuffled = (1..200).shuffled(kotlin.random.Random(7))
        val values = values(*shuffled.map { 3L to it.toDouble() }.toTypedArray())
        val slices = values.slices(0, 10, 1)
        assertEquals(listOf(200.0, 0.0, 1.0, 100.0, 198.0, 200.0, 20100.0, 100.5), slices.row(0))
    }

    @Test
    fun `a slice without records has no values`() {
        val slices = values(5L to 2.0).slices(0, 1, 3)
        assertEquals(List(2) { 0.0 } + List(6) { Double.NaN }, slices.row(0))
    }

    @Test
    fun `sums up all numbers`() {
        val values = values(1L to 4.0, 2L to Double.NaN, 3L to -2.0, 4L to 7.0)
        assertEquals(3, values.numbers)
        assertEquals(listOf(-2.0, 7.0, 3.0), listOf(values.min, values.max, values.average))
        val none = values(1L to Double.NaN)
        assertEquals(listOf(0.0, Double.NaN, Double.NaN), listOf(none.numbers.toDouble(), none.min, none.average))
    }

    @Test
    fun `finds the number closest to a value in a time`() {
        val values = values(1L to 4.0, 2L to Double.NaN, 3L to 9.0, 4L to 8.0, 12L to 8.0)
        assertEquals(3, values.closest(0, 10, 8.2))
        assertEquals(0, values.closest(0, 10, -5.0))
        // Equally close: the first.
        assertEquals(3, values.closest(0, 20, 8.0))
        assertEquals(-1, values.closest(5, 10, 8.0))
    }
}
