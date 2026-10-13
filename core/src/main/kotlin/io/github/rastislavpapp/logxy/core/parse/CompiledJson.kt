package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * JSON lines: one object per line. The keys in [keys] fill the usual fields; every other top-level
 * key becomes a field read from the line on demand, so it costs no memory per record. Timestamps are
 * ISO-8601 strings or epoch numbers in seconds, milliseconds, microseconds or nanoseconds (told apart
 * by their size). Nested objects and arrays are shown as written. Lines that are no JSON object become
 * records with only a message.
 */
class CompiledJson(
    val keys: RoleKeys = RoleKeys(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : CompiledParser() {

    private val roles = RoleFields(keys)

    private val mapField = roles.fields.size

    override val schema =
        Schema(roles.fields + Field("JSON", ColumnType.SPAN, map = JsonMap(roles.excluded), json = true))

    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)

    override val multiLine get() = false

    override fun formatTimestamp(field: Int, nanos: Long): String =
        displayFormat.format(Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS), Math.floorMod(nanos, NANOS)))

    override fun detailText(bytes: ByteArray, from: Int, to: Int): String? = Json.pretty(bytes, from, to)

    override fun newMatcher(): LineMatcher = Matcher()

    private inner class Matcher : LineMatcher {
        private var count = 0
        private var keyFrom = IntArray(16)
        private var keyTo = IntArray(16)
        private var valueFrom = IntArray(16)
        private var valueTo = IntArray(16)
        private val writer = roles.Writer(zone)
        private var bytes = ByteArray(0)
        private var lineFrom = 0
        private var lineTo = 0

        override fun match(bytes: ByteArray, from: Int, to: Int): Boolean {
            count = 0
            val valid = Json.forEachEntry(bytes, from, to) { kFrom, kTo, vFrom, vTo ->
                if (count == keyFrom.size) grow()
                keyFrom[count] = kFrom
                keyTo[count] = kTo
                valueFrom[count] = vFrom
                valueTo[count] = vTo
                count++
            }
            if (!valid) return false
            this.bytes = bytes
            lineFrom = from
            lineTo = to
            return true
        }

        private fun grow() {
            keyFrom = keyFrom.copyOf(count * 2)
            keyTo = keyTo.copyOf(count * 2)
            valueFrom = valueFrom.copyOf(count * 2)
            valueTo = valueTo.copyOf(count * 2)
        }

        override fun write(out: Columns) {
            writer.begin()
            for (e in 0..<count) {
                writer.key(bytes, keyFrom[e], keyTo[e], e)
                out.addMapKey(mapField, bytes, keyFrom[e], keyTo[e])
            }
            writer.write(out, bytes, lineFrom, valueFrom, valueTo)
            val start = Json.skipSpace(bytes, lineFrom, lineTo)
            var end = lineTo
            while (end > start && Json.isSpace(bytes[end - 1])) end--
            out.setSpan(mapField, start - lineFrom, end - start)
        }
    }

    private companion object {
        const val NANOS = 1_000_000_000L
    }
}

/**
 * Parses JSON timestamps: ISO-8601 text (`2026-10-01T08:00:00.123Z`, a space for the `T`, any number
 * of fraction digits, an offset or none, then [zone] applies), or epoch numbers. Caches the minute,
 * which consecutive records usually share.
 */
internal class JsonTimestampParser(private val zone: ZoneId) {
    var nanos = 0L
        private set

    private val cachedPrefix = ByteArray(PREFIX)
    private var cachedUtcMinute = Long.MIN_VALUE
    private var cachedZonedMinute = Long.MIN_VALUE

    /** Parses all of `bytes[from, to)` (a string's content or a number); returns false if it is no timestamp. */
    fun parse(bytes: ByteArray, from: Int, to: Int): Boolean {
        if (to - from >= PREFIX && bytes[from + 4] == '-'.code.toByte()) return parseIso(bytes, from, to)
        return parseEpoch(bytes, from, to)
    }

    private fun parseIso(bytes: ByteArray, from: Int, to: Int): Boolean {
        val cached = cachedUtcMinute != Long.MIN_VALUE &&
            java.util.Arrays.equals(bytes, from, from + PREFIX, cachedPrefix, 0, PREFIX)
        if (!cached && !cacheMinute(bytes, from)) return false
        var i = from + PREFIX
        var second = 0
        var fraction = 0L
        if (i < to && bytes[i] == ':'.code.toByte()) {
            second = digits(bytes, i + 1, 2, to)
            if (second !in 0..60) return false
            i += 3
            if (i < to && (bytes[i] == '.'.code.toByte() || bytes[i] == ','.code.toByte())) {
                i++
                var scale = 100_000_000L
                val start = i
                while (i < to && bytes[i] in ZERO..NINE) {
                    fraction += (bytes[i] - ZERO) * scale
                    scale /= 10
                    i++
                }
                if (i == start) return false
            }
        }
        val minute = if (i == to) {
            zonedMinute()
        } else {
            val offset = offsetSeconds(bytes, i, to)
            if (offset == NO_OFFSET) return false
            cachedUtcMinute - offset
        }
        nanos = (minute + second) * NANOS + fraction
        return true
    }

    /** Caches the epoch second of `yyyy-MM-ddTHH:mm` at [from], read as UTC. */
    private fun cacheMinute(bytes: ByteArray, from: Int): Boolean {
        val separator = bytes[from + 10]
        if (bytes[from + 7] != '-'.code.toByte() || bytes[from + 13] != ':'.code.toByte()) return false
        if (separator != 'T'.code.toByte() && separator != ' '.code.toByte()) return false
        val year = digits(bytes, from, 4, from + 4)
        val month = digits(bytes, from + 5, 2, from + 7)
        val day = digits(bytes, from + 8, 2, from + 10)
        val hour = digits(bytes, from + 11, 2, from + 13)
        val minute = digits(bytes, from + 14, 2, from + 16)
        if (year < 0 || month < 0 || day < 0 || hour !in 0..23 || minute !in 0..59) return false
        cachedUtcMinute = try {
            LocalDate.of(year, month, day).toEpochDay() * 86_400 + hour * 3600 + minute * 60
        } catch (_: DateTimeException) {
            return false
        }
        cachedZonedMinute = Long.MIN_VALUE
        System.arraycopy(bytes, from, cachedPrefix, 0, PREFIX)
        return true
    }

    private fun zonedMinute(): Long {
        if (cachedZonedMinute == Long.MIN_VALUE) {
            val utc = LocalDateTime.ofEpochSecond(cachedUtcMinute, 0, ZoneOffset.UTC)
            cachedZonedMinute = utc.atZone(zone).toEpochSecond()
        }
        return cachedZonedMinute
    }

    /** `Z`, `+HH`, `+HHMM` or `+HH:MM` making up all of `bytes[from, to)`; [NO_OFFSET] if it is none. */
    private fun offsetSeconds(bytes: ByteArray, from: Int, to: Int): Long {
        val sign = bytes[from].toInt().toChar()
        if ((sign == 'Z' || sign == 'z') && to == from + 1) return 0
        if (sign != '+' && sign != '-') return NO_OFFSET
        val hours = digits(bytes, from + 1, 2, to)
        var i = from + 3
        if (i < to && bytes[i] == ':'.code.toByte()) i++
        val minutes = if (i == to) 0 else digits(bytes, i, 2, to).also { i += 2 }
        if (hours !in 0..18 || minutes !in 0..59 || i != to) return NO_OFFSET
        val seconds = hours * 3600L + minutes * 60L
        return if (sign == '-') -seconds else seconds
    }

    /**
     * A whole number of seconds, milliseconds, microseconds or nanoseconds, told apart by its digits; seconds may
     * have a fraction.
     */
    private fun parseEpoch(bytes: ByteArray, from: Int, to: Int): Boolean {
        var i = from
        var value = 0L
        while (i < to && bytes[i] in ZERO..NINE && i - from < 19) {
            value = value * 10 + (bytes[i] - ZERO)
            i++
        }
        val integerDigits = i - from
        if (integerDigits == 0) return false
        val unit = when {
            integerDigits <= 11 -> NANOS
            integerDigits <= 14 -> 1_000_000L
            integerDigits <= 17 -> 1_000L
            else -> 1L
        }
        var fraction = 0L
        if (i < to && bytes[i] == '.'.code.toByte()) {
            i++
            var scale = unit / 10
            while (i < to && bytes[i] in ZERO..NINE) {
                fraction += (bytes[i] - ZERO) * scale
                scale /= 10
                i++
            }
        }
        if (i != to) return false
        nanos = value * unit + fraction
        return true
    }

    private fun digits(bytes: ByteArray, from: Int, count: Int, to: Int): Int {
        if (from + count > to) return -1
        var value = 0
        for (i in from..<from + count) {
            val digit = bytes[i] - ZERO
            if (digit !in 0..9) return -1
            value = value * 10 + digit
        }
        return value
    }

    private companion object {
        const val NANOS = 1_000_000_000L
        const val PREFIX = 16
        const val NO_OFFSET = Long.MIN_VALUE
        const val ZERO = '0'.code.toByte()
        const val NINE = '9'.code.toByte()
    }
}
