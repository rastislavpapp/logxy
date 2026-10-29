package io.github.rastislavpapp.logxy.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TraceChartsTest {

    @Test
    fun `a wide range is labelled at durations ten times apart`() {
        val ticks = durationTicks(3 * MILLI, 40 * SECOND)
        assertEquals(listOf(10 * MILLI, 100 * MILLI, SECOND, 10 * SECOND), ticks.toList())
    }

    @Test
    fun `a narrow range is labelled at round steps inside it`() {
        val ticks = durationTicks(DAY + 5 * HOUR + 33 * MINUTE, DAY + 11 * HOUR + 37 * MINUTE)
        assertEquals(listOf(DAY + 6 * HOUR, DAY + 8 * HOUR, DAY + 10 * HOUR), ticks.toList())
        assertEquals(listOf("1d 6h", "1d 8h", "1d 10h"), ticks.map(::axisLabel))
    }

    @Test
    fun `a range with one decade in it is labelled at steps`() {
        val ticks = durationTicks(2 * SECOND, 30 * SECOND)
        assertEquals(listOf(10 * SECOND, 20 * SECOND, 30 * SECOND), ticks.toList())
    }

    @Test
    fun `axis labels leave out the parts that are 0`() {
        assertEquals("1d", axisLabel(DAY))
        assertEquals("1min 30s", axisLabel(90 * SECOND))
        assertEquals("2h 0min 0.5s", axisLabel(2 * HOUR + 500 * MILLI))
        assertEquals("250ms", axisLabel(250 * MILLI))
    }

    @Test
    fun `counts are shortened to thousands and millions`() {
        assertEquals("800", shortCount(800))
        assertEquals("2k", shortCount(2_000))
        assertEquals("2.5k", shortCount(2_500))
        assertEquals("1.2M", shortCount(1_250_000))
    }
}
