package io.github.rastislavpapp.logxy.core.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurationHistogramTest {

    @Test
    fun `no durations give no histogram`() {
        assertNull(DurationHistogram.of(LongArray(0), 10))
    }

    @Test
    fun `the counts add up to the durations`() {
        val durations = LongArray(1000) { (it + 1) * 7L }
        val histogram = DurationHistogram.of(durations, 20)!!
        assertEquals(durations.size, histogram.counts.sum())
    }

    @Test
    fun `a few extreme durations do not put the rest in one bin`() {
        // 990 durations of 1 to 10 ms, and 10 of 100 s.
        val durations = LongArray(1000) { if (it < 990) (1 + it % 10) * 1_000_000L else 100_000_000_000L }
        val histogram = DurationHistogram.of(durations, 40)!!
        val busiest = histogram.counts.max()
        assertTrue(busiest < 400, "the busiest bin holds $busiest of 1000")
        assertEquals(1000, histogram.counts.sum())
        assertEquals(10, histogram.counts.last())
    }

    @Test
    fun `each bin holds the durations from its first edge up to the next`() {
        val histogram = DurationHistogram.of(longArrayOf(10, 100, 1000), 4)!!
        for (duration in longArrayOf(10, 100, 1000)) {
            val bin = histogram.binOf(duration)
            assertTrue(histogram.from(bin) <= duration && duration < histogram.to(bin))
        }
        assertEquals(3, histogram.counts.sum())
    }

    @Test
    fun `zero durations get a bin of their own`() {
        val histogram = DurationHistogram.of(longArrayOf(0, 0, 5, 50), 10)!!
        assertTrue(histogram.hasZeroBin)
        assertEquals(2, histogram.counts[0])
        assertEquals(4, histogram.counts.sum())
        assertFalse(DurationHistogram.of(longArrayOf(5, 50), 10)!!.hasZeroBin)
    }

    @Test
    fun `equal durations and only zeros are one bin`() {
        assertEquals(listOf(3), DurationHistogram.of(longArrayOf(8, 8, 8), 10)!!.counts.toList())
        assertEquals(listOf(2), DurationHistogram.of(longArrayOf(0, 0), 10)!!.counts.toList())
    }

    @Test
    fun `a narrow range has no more bins than distinct whole nanoseconds`() {
        val histogram = DurationHistogram.of(longArrayOf(5, 6, 7), 40)!!
        assertTrue(histogram.counts.size <= 3)
        assertEquals(3, histogram.counts.sum())
    }
}
