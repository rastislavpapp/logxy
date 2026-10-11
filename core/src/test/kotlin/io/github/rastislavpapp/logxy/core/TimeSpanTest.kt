package io.github.rastislavpapp.logxy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeSpanTest {

    @Test
    fun `short spans use the largest unit that fits`() {
        assertEquals("0 ns", formatTimeSpan(0))
        assertEquals("999 ns", formatTimeSpan(999))
        assertEquals("12.5 µs", formatTimeSpan(12_500))
        assertEquals("1 ms", formatTimeSpan(1_000_000))
        assertEquals("123.456 ms", formatTimeSpan(123_456_789))
        assertEquals("3.25 s", formatTimeSpan(3_250_000_000))
        assertEquals("59.999 s", formatTimeSpan(59_999_999_999))
    }

    @Test
    fun `longer spans are split into units down to milliseconds`() {
        assertEquals("1 min 0 s", formatTimeSpan(60_000_000_000))
        assertEquals("2 min 3.5 s", formatTimeSpan(123_500_999_999))
        assertEquals("1 h 0 min 5 s", formatTimeSpan(3_605_000_000_000))
        assertEquals("2 d 0 h 1 min 0.001 s", formatTimeSpan(2 * 86_400_000_000_000 + 60_001_000_000))
    }

    @Test
    fun `negative spans get a minus sign`() {
        assertEquals("-1.5 s", formatTimeSpan(-1_500_000_000))
    }
}
