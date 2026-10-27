package io.github.rastislavpapp.logxy.core.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimestampShiftTest {

    private val hour = 3_600_000_000_000L
    private val minute = 60_000_000_000L

    @Test
    fun `a bare number is hours, and minutes follow a colon`() {
        assertEquals(2 * hour, TimestampShift.parse("2"))
        assertEquals(2 * hour, TimestampShift.parse(" +2 "))
        assertEquals(2 * hour, TimestampShift.parse("+2h"))
        assertEquals(-5 * hour, TimestampShift.parse("-5"))
        assertEquals(5 * hour + 30 * minute, TimestampShift.parse("+5:30"))
        assertEquals(-(9 * hour + 45 * minute), TimestampShift.parse("-9:45"))
        assertEquals(26 * hour, TimestampShift.parse("26:00"))
    }

    @Test
    fun `empty or zero is no shift`() {
        assertEquals(0, TimestampShift.parse(""))
        assertEquals(0, TimestampShift.parse("0"))
        assertEquals(0, TimestampShift.parse("-0:00"))
    }

    @Test
    fun `anything else, or beyond 26 hours, is no shift at all`() {
        for (text in listOf("27", "-26:01", "5:60", "5:3", "1.5", "2m", "+-2", "two", "99999999999999999999")) {
            assertNull(TimestampShift.parse(text), text)
        }
    }

    @Test
    fun `a shift shows as whole hours, or hours and minutes`() {
        assertEquals("+2h", TimestampShift.format(2 * hour))
        assertEquals("-5h", TimestampShift.format(-5 * hour))
        assertEquals("+5:30", TimestampShift.format(5 * hour + 30 * minute))
        assertEquals("-9:45", TimestampShift.format(-(9 * hour + 45 * minute)))
        assertEquals("", TimestampShift.format(0))
    }

    @Test
    fun `a step moves by an hour, within the range`() {
        assertEquals(6 * hour + 30 * minute, TimestampShift.step(5 * hour + 30 * minute, up = true))
        assertEquals(-hour, TimestampShift.step(0, up = false))
        assertEquals(26 * hour, TimestampShift.step(25 * hour + 30 * minute, up = true))
        assertEquals(-26 * hour, TimestampShift.step(-26 * hour, up = false))
    }
}
