package io.github.rastislavpapp.logxy.ui

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeShortcutTest {

    // A Tuesday afternoon.
    private val now = ZonedDateTime.of(2026, 10, 27, 15, 42, 10, 123_000_000, ZoneId.of("Europe/Bratislava"))

    private fun at(shortcut: TimeShortcut, firstDay: DayOfWeek = DayOfWeek.MONDAY) =
        shortcut.at(now, firstDay).toLocalDateTime().toString()

    @Test
    fun `shortcuts go back from now to the second`() {
        assertEquals("2026-10-27T15:42:10", at(TimeShortcut.NOW))
        assertEquals("2026-10-27T15:27:10", at(TimeShortcut.MINUTES_15))
        assertEquals("2026-10-26T15:42:10", at(TimeShortcut.HOURS_24))
        assertEquals("2026-10-20T15:42:10", at(TimeShortcut.DAYS_7))
    }

    @Test
    fun `day shortcuts go to the start of the day`() {
        assertEquals("2026-10-27T00:00", at(TimeShortcut.TODAY))
        assertEquals("2026-10-26T00:00", at(TimeShortcut.YESTERDAY))
        assertEquals("2026-10-26T00:00", at(TimeShortcut.THIS_WEEK))
        // Where weeks start on Sunday.
        assertEquals("2026-10-25T00:00", at(TimeShortcut.THIS_WEEK, DayOfWeek.SUNDAY))
    }
}
