package io.github.rastislavpapp.logxy.core.parse

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessLogTimestampTest {

    private val parser = AccessLogTimestamp()

    private fun nanos(text: String): Long? {
        val bytes = "[$text]".toByteArray()
        return if (parser.parse(bytes, 1, bytes.size - 1)) parser.nanos else null
    }

    private fun epochNanos(iso: String) = OffsetDateTime.parse(iso).toInstant().let { it.epochSecond * 1_000_000_000 + it.nano }

    @Test
    fun `parses every month`() {
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        for ((i, month) in months.withIndex()) {
            val m = (i + 1).toString().padStart(2, '0')
            assertEquals(epochNanos("2026-$m-28T23:59:58Z"), nanos("28/$month/2026:23:59:58 +0000"), month)
        }
    }

    @Test
    fun `honours the offset, across a day and month boundary`() {
        assertEquals(epochNanos("2026-09-30T22:30:00Z"), nanos("01/Oct/2026:00:30:00 +0200"))
        assertEquals(epochNanos("2026-11-01T09:59:59Z"), nanos("31/Oct/2026:23:59:59 -1000"))
        assertEquals(epochNanos("2026-10-01T05:15:00Z"), nanos("01/Oct/2026:10:45:00 +0530"))
        // The cached minute must not carry the offset of the line before.
        assertEquals(epochNanos("2026-10-01T10:45:00Z"), nanos("01/Oct/2026:10:45:00 +0000"))
        assertEquals(epochNanos("2028-02-29T12:00:01Z"), nanos("29/Feb/2028:12:00:01 +0000"))
    }

    @Test
    fun `rejects what is no access-log timestamp`() {
        for (bad in listOf(
            "13/oct/2026:10:00:00 +0000", "13/Okt/2026:10:00:00 +0000", "32/Oct/2026:10:00:00 +0000", "13/Oct/2026:24:00:00 +0000",
            "13/Oct/2026:10:00:00", "13/Oct/2026:10:00:00 0000", "2026-10-13T10:00:00Z", "30/Feb/2026:10:00:00 +0000",
        )) {
            assertNull(nanos(bad), bad)
        }
    }
}
