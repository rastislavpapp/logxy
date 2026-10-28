package io.github.rastislavpapp.logxy.core.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurationStatisticsTest {

    private fun DurationStatistics.all() = listOf(count.toLong(), min, median, p90, p95, p99, max)

    @Test
    fun `percentiles are the nearest rank, in any order of the durations`() {
        // 1 to 20 shuffled: the median is the 10th smallest, p90 the 18th, p95 the 19th, p99 the 20th.
        val durations = longArrayOf(7, 20, 3, 15, 1, 12, 18, 9, 4, 16, 2, 11, 19, 6, 14, 8, 17, 5, 13, 10)
        assertEquals(listOf(20L, 1, 10, 18, 19, 20, 20), DurationStatistics.of(durations)!!.all())
    }

    @Test
    fun `one duration is every statistic, and none gives no statistics`() {
        assertEquals(listOf(1L, 0, 0, 0, 0, 0, 0), DurationStatistics.of(longArrayOf(0))!!.all())
        assertEquals(listOf(4L, 1, 2, 4, 4, 4, 4), DurationStatistics.of(longArrayOf(4, 3, 2, 1))!!.all())
        assertNull(DurationStatistics.of(LongArray(0)))
    }
}
