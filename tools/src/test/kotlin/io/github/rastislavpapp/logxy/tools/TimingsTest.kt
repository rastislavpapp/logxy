package io.github.rastislavpapp.logxy.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class TimingsTest {

    @Test
    fun `across JVMs takes the median of their bests and medians, and spreads their bests`() {
        val times = Timings(
            listOf(
                doubleArrayOf(0.10, 0.12, 0.11),
                doubleArrayOf(0.20, 0.21, 0.22),
                doubleArrayOf(0.13, 0.11, 0.12),
            ),
        )

        assertEquals(0.11, times.best, 1e-9)
        assertEquals(0.12, times.median, 1e-9)
        assertEquals(1.0, times.spread, 1e-9)
    }

    @Test
    fun `in one JVM spreads its runs`() {
        val times = Timings(listOf(doubleArrayOf(0.12, 0.10, 0.15, 0.11)))

        assertEquals(0.10, times.best, 1e-9)
        assertEquals(0.115, times.median, 1e-9)
        assertEquals(0.5, times.spread, 1e-9)
    }
}
