package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.log.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SECOND = 1_000_000_000L
private const val T0 = 1_760_000_000L * SECOND

class TimeHistogramTest {

    private fun TimeHistogram.total(category: Int) = (firstBin..lastBin).sumOf { count(category, it) }

    private fun TimeHistogram.binOf(time: Long) = ((time - start) / width).toInt()

    @Test
    fun `picks a round width that covers the expected times`() {
        val histogram = TimeHistogram(T0, T0 + 3600 * SECOND)
        assertEquals(1 * SECOND, histogram.width)
        assertEquals(0, histogram.start % histogram.width)
        assertTrue(histogram.isEmpty)
    }

    @Test
    fun `counts records by level category`() {
        val histogram = TimeHistogram(T0, T0 + 100 * SECOND)
        histogram.add(T0, Level.INFO)
        histogram.add(T0, Level.ERROR)
        histogram.add(T0 + 50 * SECOND, Level.FATAL)
        histogram.add(T0 + 50 * SECOND, Level.WARN)
        histogram.add(T0 + 50 * SECOND, null)
        assertEquals(2, histogram.total(TimeHistogram.OTHER))
        assertEquals(1, histogram.total(TimeHistogram.WARN))
        assertEquals(2, histogram.total(TimeHistogram.ERROR))
        val bin = histogram.binOf(T0 + 50 * SECOND)
        assertEquals(1, histogram.count(TimeHistogram.ERROR, bin))
        assertEquals(bin, histogram.lastBin)
    }

    @Test
    fun `doubles the width for times far beyond, keeping the counts`() {
        val histogram = TimeHistogram(T0, T0 + 10 * SECOND)
        val width = histogram.width
        for (i in 0..<100) histogram.add(T0 + i * SECOND / 10, Level.INFO)
        histogram.add(T0 + 1000 * SECOND, Level.ERROR)
        assertTrue(histogram.width > width)
        assertEquals(0, histogram.start % histogram.width)
        assertEquals(100, histogram.total(TimeHistogram.OTHER))
        assertEquals(1, histogram.count(TimeHistogram.ERROR, histogram.binOf(T0 + 1000 * SECOND)))
        assertTrue(histogram.lastBin - histogram.firstBin < TimeHistogram.BINS)
    }

    @Test
    fun `moves the bins for earlier times when they fit`() {
        val histogram = TimeHistogram(T0, T0 + 4096 * SECOND)
        histogram.add(T0 + 10 * SECOND, Level.INFO)
        histogram.add(T0 - 10 * SECOND, Level.WARN)
        assertEquals(1 * SECOND, histogram.width)
        assertEquals(T0 - 10 * SECOND, histogram.binStart(histogram.firstBin))
        assertEquals(1, histogram.count(TimeHistogram.OTHER, histogram.binOf(T0 + 10 * SECOND)))
    }

    @Test
    fun `remove takes back a record`() {
        val histogram = TimeHistogram(T0, T0 + 10 * SECOND)
        histogram.add(T0 + SECOND, Level.ERROR)
        histogram.remove(T0 + SECOND, Level.ERROR)
        histogram.add(T0 + SECOND, Level.INFO)
        assertEquals(0, histogram.total(TimeHistogram.ERROR))
        assertEquals(1, histogram.total(TimeHistogram.OTHER))
    }
}
