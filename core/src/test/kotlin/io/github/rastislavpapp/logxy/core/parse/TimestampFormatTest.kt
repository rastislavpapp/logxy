package io.github.rastislavpapp.logxy.core.parse

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TimestampFormatTest {

    private fun parse(pattern: String, text: String): Long? {
        val parser = TimestampFormat(pattern, ZoneOffset.UTC).newParser()
        val bytes = text.toByteArray()
        val end = parser.parse(bytes, 0, bytes.size)
        return if (end < 0) null else parser.nanos
    }

    private fun nanos(text: String): Long {
        val instant = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        return instant.epochSecond * 1_000_000_000 + instant.nano
    }

    @Test
    fun `fixed layout with millis`() {
        assertEquals(nanos("2026-10-01T08:00:00.001"), parse("yyyy-MM-dd HH:mm:ss.SSS", "2026-10-01 08:00:00.001"))
    }

    @Test
    fun `T and space, comma and dot are interchangeable`() {
        val expected = nanos("2026-10-01T08:00:00.123")
        assertEquals(expected, parse("yyyy-MM-dd HH:mm:ss.SSS", "2026-10-01T08:00:00,123"))
        assertEquals(expected, parse("ISO8601", "2026-10-01 08:00:00,123"))
    }

    @Test
    fun `cached minute prefix gives correct results across minutes and days`() {
        val parser = TimestampFormat("yyyy-MM-dd HH:mm:ss.SSS", ZoneOffset.UTC).newParser()
        for (text in listOf(
            "2026-10-01 23:58:59.999", "2026-10-01 23:58:01.000", "2026-10-01 23:59:00.500",
            "2026-10-02 00:00:00.000", "2026-10-02 00:00:30.250",
        )) {
            val bytes = text.toByteArray()
            assertEquals(bytes.size, parser.parse(bytes, 0, bytes.size))
            assertEquals(nanos(text.replace(' ', 'T')), parser.nanos, text)
        }
    }

    @Test
    fun `micro and nano fractions keep full precision`() {
        assertEquals(nanos("2026-10-01T08:00:00.123456"), parse("yyyy-MM-dd HH:mm:ss.SSSSSS", "2026-10-01 08:00:00.123456"))
        assertEquals(nanos("2026-10-01T08:00:00.123456789"), parse("DEFAULT_NANOS", "2026-10-01 08:00:00,123456789"))
    }

    @Test
    fun `time only layout uses the epoch date`() {
        assertEquals(nanos("1970-01-01T13:45:10.500"), parse("HH:mm:ss.SSS", "13:45:10.500"))
    }

    @Test
    fun `invalid timestamps are rejected`() {
        assertEquals(null, parse("yyyy-MM-dd HH:mm:ss.SSS", "2026-13-01 08:00:00.001"))
        assertEquals(null, parse("yyyy-MM-dd HH:mm:ss.SSS", "\tat com.example.Foo"))
        assertEquals(null, parse("yyyy-MM-dd HH:mm:ss.SSS", "2026-10-01 08:00"))
    }

    @Test
    fun `month names and offsets use java time`() {
        assertEquals(nanos("2026-10-08T12:00:00.123"), parse("DATE", "08 Oct 2026 12:00:00,123 rest"))
        val withOffset = ZonedDateTime.parse("2026-10-08T12:00:00.123+02:00").toInstant()
        assertEquals(
            withOffset.epochSecond * 1_000_000_000 + withOffset.nano,
            parse("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "2026-10-08T12:00:00.123+02:00"),
        )
    }

    @Test
    fun `unix millis`() {
        assertEquals(1_791_000_000_123_000_000L, parse("UNIX_MILLIS", "1791000000123 rest"))
    }

    @Test
    fun `formats in the original layout`() {
        val format = TimestampFormat("yyyy-MM-dd HH:mm:ss.SSS", ZoneOffset.UTC)
        assertEquals("2026-10-01 08:00:00.001", format.format(nanos("2026-10-01T08:00:00.001")))
    }
}
