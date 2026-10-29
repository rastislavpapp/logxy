package io.github.rastislavpapp.logxy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeSpanTest {

    @Test
    fun `short spans use the largest unit that fits`() {
        assertEquals("0ns", formatTimeSpan(0))
        assertEquals("999ns", formatTimeSpan(999))
        assertEquals("12.5µs", formatTimeSpan(12_500))
        assertEquals("1ms", formatTimeSpan(1_000_000))
        assertEquals("123.456ms", formatTimeSpan(123_456_789))
        assertEquals("3.25s", formatTimeSpan(3_250_000_000))
        assertEquals("59.999s", formatTimeSpan(59_999_999_999))
    }

    @Test
    fun `longer spans are split into units down to milliseconds`() {
        assertEquals("1min 0s", formatTimeSpan(60_000_000_000))
        assertEquals("2min 3.5s", formatTimeSpan(123_500_999_999))
        assertEquals("1h 0min 5s", formatTimeSpan(3_605_000_000_000))
        assertEquals("2d 0h 1min 0.001s", formatTimeSpan(2 * 86_400_000_000_000 + 60_001_000_000))
    }

    @Test
    fun `negative spans get a minus sign`() {
        assertEquals("-1.5s", formatTimeSpan(-1_500_000_000))
    }
}
