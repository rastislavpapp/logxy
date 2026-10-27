package io.github.rastislavpapp.logxy.core.query

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoToTimeTest {

    private val zone = ZoneId.of("Europe/Bratislava")

    private fun nanos(text: String, zone: ZoneId = this.zone) =
        LocalDateTime.parse(text).atZone(zone).toInstant().let { it.epochSecond * 1_000_000_000 + it.nano }

    private val from = nanos("2026-10-09T14:00:00.250")

    @Test
    fun `a date and time goes there`() {
        assertEquals(nanos("2026-10-11T08:30:00"), GoToTime.parse("2026-10-11 08:30", from, zone))
        assertEquals(nanos("2026-10-11T00:00:00"), GoToTime.parse("2026-10-11", from, zone))
    }

    @Test
    fun `a time alone takes the day of the time gone from`() {
        assertEquals(nanos("2026-10-09T14:32:05.100"), GoToTime.parse("14:32:05.100", from, zone))
        assertEquals(nanos("2026-10-09T08:00:00"), GoToTime.parse(" 08:00 ", from, zone))
        // A log without dates has its times on 1970-01-01, and so does a time alone gone to from there.
        val undated = GoToTime.parse("09:15", 3_600_000_000_000, ZoneOffset.UTC)
        assertEquals(nanos("1970-01-01T09:15:00", ZoneOffset.UTC), undated)
    }

    @Test
    fun `a relative time goes forward without a sign and back with a minus`() {
        assertEquals(nanos("2026-10-09T15:30:00.250"), GoToTime.parse("1h30m", from, zone))
        assertEquals(nanos("2026-10-09T14:00:10.250"), GoToTime.parse("+10s", from, zone))
        assertEquals(nanos("2026-10-07T13:59:00.250"), GoToTime.parse("-2d1m", from, zone))
    }

    @Test
    fun `anything else is no time`() {
        val texts = listOf("", "1.5h", "10ms", "1h-", "--1h", "14:61", "yesterday", "2026-13-01")
        val tooFar = listOf("99999999999d", "99999999999999999999s")
        for (text in texts + tooFar) {
            assertFailsWith<QueryException>(text) { GoToTime.parse(text, from, zone) }
        }
    }
}
