package io.github.rastislavpapp.logxy.core.parse

import java.text.ParsePosition
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalQueries
import java.util.Locale

/** Parses timestamps from raw bytes into epoch nanoseconds. Stateful (caches); one per thread. */
internal interface TimestampParser {
    /** Parses a timestamp starting at `bytes[from]`; returns the index after it, or -1. Result in [nanos]. */
    fun parse(bytes: ByteArray, from: Int, limit: Int): Int

    val nanos: Long
}

/**
 * A timestamp layout in log4j/logback (SimpleDateFormat-style) syntax, or one of their named
 * formats such as `ISO8601` or `UNIX_MILLIS`. Timestamps without zone use [zone].
 */
class TimestampFormat(pattern: String, private val zone: ZoneId = ZoneId.systemDefault()) {

    private val unixUnit: Long? = when (pattern) {
        "UNIX" -> 1_000_000_000L
        "UNIX_MILLIS" -> 1_000_000L
        else -> null
    }
    private val pattern = NAMED[pattern] ?: pattern
    private val javaPattern = toJavaPattern(this.pattern)
    private val formatter: DateTimeFormatter? = if (unixUnit != null) {
        null
    } else {
        DateTimeFormatter.ofPattern(javaPattern, Locale.ENGLISH).withZone(zone)
    }
    private val layout: List<Element>? = if (unixUnit != null) null else fixedLayout(this.pattern)

    /** Epoch [nanos] written in this layout, in [zone]; a UNIX layout writes the number in its unit. */
    fun format(nanos: Long): String {
        if (unixUnit != null) return (nanos / unixUnit).toString()
        return formatter!!.format(Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS), Math.floorMod(nanos, NANOS)))
    }

    /**
     * A parser for one thread: byte-level for epoch numbers and fixed-width numeric layouts, java.time otherwise
     * (slower).
     */
    internal fun newParser(): TimestampParser = when {
        unixUnit != null -> UnixParser(unixUnit)
        layout != null -> FixedParser(layout, zone)
        else -> JavaTimeParser(formatter!!, zone)
    }

    /** One element of a fixed-width, all-numeric layout. */
    private class Element(val kind: Char, val width: Int, val literal: Byte = 0)

    private class FixedParser(private val elements: List<Element>, private val zone: ZoneId) : TimestampParser {
        override var nanos = 0L
        private val length = elements.sumOf { it.width }

        // Everything before seconds; consecutive lines usually share it, so its epoch value is cached.
        private val prefixLength = elements.takeWhile { it.kind != 's' && it.kind != 'S' }.sumOf { it.width }
        private val cachedPrefix = ByteArray(prefixLength)
        private var cachedSeconds = Long.MIN_VALUE

        override fun parse(bytes: ByteArray, from: Int, limit: Int): Int {
            if (from + length > limit) return -1
            val prefixCached = cachedSeconds != Long.MIN_VALUE &&
                java.util.Arrays.equals(bytes, from, from + prefixLength, cachedPrefix, 0, prefixLength)
            var year = 1970
            var month = 1
            var day = 1
            var hour = 0
            var minute = 0
            var second = 0
            var fraction = 0L
            var i = from
            for (element in elements) {
                if (element.kind == LITERAL) {
                    if (!literalMatches(bytes[i], element.literal)) return -1
                } else if (!prefixCached || i >= from + prefixLength) {
                    val value = digits(bytes, i, element.width)
                    if (value < 0) return -1
                    when (element.kind) {
                        'y' -> year = if (element.width == 2) 2000 + value.toInt() else value.toInt()
                        'M' -> month = value.toInt()
                        'd' -> day = value.toInt()
                        'H' -> hour = value.toInt()
                        'm' -> minute = value.toInt()
                        's' -> second = value.toInt()
                        'S' -> fraction = value * POWERS[9 - element.width]
                    }
                }
                i += element.width
            }
            if (!prefixCached) {
                cachedSeconds = try {
                    LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toEpochSecond()
                } catch (_: DateTimeException) {
                    return -1
                }
                System.arraycopy(bytes, from, cachedPrefix, 0, prefixLength)
            }
            if (second > 60) return -1
            nanos = (cachedSeconds + second) * NANOS + fraction
            return i
        }

        private fun digits(bytes: ByteArray, from: Int, count: Int): Long {
            var value = 0L
            for (i in from..<from + count) {
                val digit = bytes[i] - '0'.code.toByte()
                if (digit !in 0..9) return -1
                value = value * 10 + digit
            }
            return value
        }

        // Logback's ISO8601 uses a space where log4j2's has 'T', and ',' and '.' both separate fractions.
        private fun literalMatches(actual: Byte, expected: Byte): Boolean =
            actual == expected ||
                interchangeable(actual, expected, 'T', ' ') ||
                interchangeable(actual, expected, ',', '.')

        private fun interchangeable(actual: Byte, expected: Byte, a: Char, b: Char) =
            isEither(actual, a, b) && isEither(expected, a, b)

        private fun isEither(value: Byte, a: Char, b: Char) = value == a.code.toByte() || value == b.code.toByte()
    }

    private class UnixParser(private val unit: Long) : TimestampParser {
        override var nanos = 0L

        override fun parse(bytes: ByteArray, from: Int, limit: Int): Int {
            var i = from
            var value = 0L
            while (i < limit && i - from < 19 && bytes[i] in '0'.code.toByte()..'9'.code.toByte()) {
                value = value * 10 + (bytes[i] - '0'.code.toByte())
                i++
            }
            if (i == from) return -1
            nanos = value * unit
            return i
        }
    }

    /** Any layout java.time understands, e.g. with month names or zone offsets. Slower. */
    private class JavaTimeParser(private val formatter: DateTimeFormatter, private val zone: ZoneId) : TimestampParser {
        override var nanos = 0L

        override fun parse(bytes: ByteArray, from: Int, limit: Int): Int {
            val text = Latin1Chars(bytes, from, limit)
            val position = ParsePosition(0)
            formatter.parseUnresolved(text, position) ?: return -1
            if (position.errorIndex >= 0) return -1
            return try {
                nanos = toEpochNanos(formatter.parse(text.subSequence(0, position.index)))
                from + position.index
            } catch (_: DateTimeException) {
                -1
            }
        }

        private fun toEpochNanos(parsed: TemporalAccessor): Long {
            val time = parsed.query(TemporalQueries.localTime()) ?: LocalTime.MIDNIGHT
            val date = parsed.query(TemporalQueries.localDate()) ?: LocalDate.EPOCH
            // The formatter's zone is only a default; an offset in the text wins.
            val offset = parsed.query(TemporalQueries.offset()) ?: parsed.query(TemporalQueries.zone()) ?: zone
            val instant = ZonedDateTime.of(date, time, offset).toInstant()
            return instant.epochSecond * NANOS + instant.nano
        }
    }

    companion object {
        private const val NANOS = 1_000_000_000L
        private const val LITERAL = '\''
        private val POWERS = LongArray(10).also { p ->
            p[0] = 1
            for (i in 1..9) p[i] = p[i - 1] * 10
        }

        /** log4j2's named formats; logback's ISO8601 is matched by the lenient 'T'. */
        private val NAMED = mapOf(
            "ABSOLUTE" to "HH:mm:ss,SSS",
            "ABSOLUTE_MICROS" to "HH:mm:ss,nnnnnn",
            "ABSOLUTE_NANOS" to "HH:mm:ss,nnnnnnnnn",
            "COMPACT" to "yyyyMMddHHmmssSSS",
            "DATE" to "dd MMM yyyy HH:mm:ss,SSS",
            "DEFAULT" to "yyyy-MM-dd HH:mm:ss,SSS",
            "DEFAULT_MICROS" to "yyyy-MM-dd HH:mm:ss,nnnnnn",
            "DEFAULT_NANOS" to "yyyy-MM-dd HH:mm:ss,nnnnnnnnn",
            "ISO8601" to "yyyy-MM-dd'T'HH:mm:ss,SSS",
            "ISO8601_BASIC" to "yyyyMMdd'T'HHmmss,SSS",
            "ISO8601_OFFSET_DATE_TIME_HHMM" to "yyyy-MM-dd'T'HH:mm:ss,SSSXX",
            "ISO8601_OFFSET_DATE_TIME_HHCMM" to "yyyy-MM-dd'T'HH:mm:ss,SSSXXX",
        )

        /** log4j2 writes fractions with `n`; java.time's `n` means nano-of-second, so use `S`. */
        private fun toJavaPattern(pattern: String) = pattern.replace('n', 'S')

        /** The layout as fixed-width numeric elements, or null if it needs the java.time parser. */
        private fun fixedLayout(pattern: String): List<Element>? {
            val elements = ArrayList<Element>()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                if (c == '\'') {
                    val close = pattern.indexOf('\'', i + 1)
                    if (close < 0) return null
                    if (close == i + 1) elements += Element(LITERAL, 1, '\''.code.toByte())
                    for (j in i + 1..<close) {
                        if (pattern[j].code > 127) return null
                        elements += Element(LITERAL, 1, pattern[j].code.toByte())
                    }
                    i = close + 1
                    continue
                }
                if (!c.isLetter()) {
                    if (c.code > 127) return null
                    elements += Element(LITERAL, 1, c.code.toByte())
                    i++
                    continue
                }
                var count = 1
                while (i + count < pattern.length && pattern[i + count] == c) count++
                val kind = when (c) {
                    'y' -> if (count == 2 || count == 4) 'y' else null
                    'M', 'd', 'H', 'm', 's' -> if (count == 2) c else null
                    'S', 'n' -> if (count <= 9) 'S' else null
                    else -> null
                } ?: return null
                elements += Element(kind, count)
                i += count
            }
            return elements
        }
    }
}

/** Bytes viewed as ISO-8859-1 characters, so character offsets equal byte offsets. */
internal class Latin1Chars(private val bytes: ByteArray, private val from: Int, private val to: Int) : CharSequence {
    override val length get() = to - from
    override fun get(index: Int) = (bytes[from + index].toInt() and 0xFF).toChar()
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        Latin1Chars(bytes, from + startIndex, from + endIndex)

    override fun toString() = String(bytes, from, to - from, Charsets.ISO_8859_1)
}
