package io.github.rastislavpapp.logxy.core.parse

/**
 * Parses access-log timestamps, `13/Oct/2026:10:00:00 +0200`, into epoch nanoseconds. Caches the minute,
 * which consecutive records usually share. Stateful; one per thread.
 */
internal class AccessLogTimestamp {
    var nanos = 0L
        private set

    private val cachedMinute = ByteArray(MINUTE_LENGTH)
    private val cachedOffset = ByteArray(OFFSET_LENGTH)
    private var cachedSeconds = Long.MIN_VALUE

    /** Parses all of `bytes[from, to)`; returns false if it is no such timestamp. */
    fun parse(bytes: ByteArray, from: Int, to: Int): Boolean {
        if (to - from != LENGTH || bytes[from + 17] != COLON || bytes[from + 20] != SPACE) return false
        val second = digits(bytes, from + 18)
        if (second !in 0..60) return false
        val cached = cachedSeconds != Long.MIN_VALUE &&
            java.util.Arrays.equals(bytes, from, from + MINUTE_LENGTH, cachedMinute, 0, MINUTE_LENGTH) &&
            java.util.Arrays.equals(bytes, from + OFFSET_AT, to, cachedOffset, 0, OFFSET_LENGTH)
        if (!cached) {
            val minute = minuteSeconds(bytes, from)
            if (minute == Long.MIN_VALUE) return false
            System.arraycopy(bytes, from, cachedMinute, 0, MINUTE_LENGTH)
            System.arraycopy(bytes, from + OFFSET_AT, cachedOffset, 0, OFFSET_LENGTH)
            cachedSeconds = minute
        }
        nanos = (cachedSeconds + second) * NANOS
        return true
    }

    /**
     * The epoch second of the minute `dd/MMM/yyyy:HH:mm` at [from], read in the UTC offset that ends the
     * timestamp, e.g. `+0200`; Long.MIN_VALUE if either is invalid.
     */
    private fun minuteSeconds(bytes: ByteArray, from: Int): Long {
        if (bytes[from + 2] != SLASH || bytes[from + 6] != SLASH) return Long.MIN_VALUE
        if (bytes[from + 11] != COLON || bytes[from + 14] != COLON) return Long.MIN_VALUE
        val day = digits(bytes, from)
        val month = month(bytes, from + 3)
        val century = digits(bytes, from + 7)
        val yearOfCentury = digits(bytes, from + 9)
        val year = if (century < 0 || yearOfCentury < 0) -1 else century * 100 + yearOfCentury
        val hour = digits(bytes, from + 12)
        val minute = digits(bytes, from + 15)
        val sign = when (bytes[from + OFFSET_AT]) {
            PLUS -> 1
            MINUS -> -1
            else -> return Long.MIN_VALUE
        }
        val offsetHours = digits(bytes, from + OFFSET_AT + 1)
        val offsetMinutes = digits(bytes, from + OFFSET_AT + 3)
        if (month < 1 || year < 0 || day < 1 || day > daysIn(month, year)) return Long.MIN_VALUE
        if (hour !in 0..23 || minute !in 0..59) return Long.MIN_VALUE
        if (offsetHours !in 0..18 || offsetMinutes !in 0..59) return Long.MIN_VALUE
        val offset = sign * (offsetHours * 3600 + offsetMinutes * 60)
        return epochDay(year, month, day) * 86_400 + hour * 3600 + minute * 60 - offset
    }

    /** Two digits at [at], or -1. */
    private fun digits(bytes: ByteArray, at: Int): Int {
        val high = bytes[at] - ZERO
        val low = bytes[at + 1] - ZERO
        return if (high in 0..9 && low in 0..9) high * 10 + low else -1
    }

    /** 1 to 12 for the English month abbreviation at [at], or -1. */
    private fun month(bytes: ByteArray, at: Int): Int {
        for (m in MONTHS.indices) {
            val name = MONTHS[m]
            if (bytes[at] == name[0] && bytes[at + 1] == name[1] && bytes[at + 2] == name[2]) return m + 1
        }
        return -1
    }

    private fun daysIn(month: Int, year: Int) = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private companion object {
        const val LENGTH = 26
        const val MINUTE_LENGTH = 17
        const val OFFSET_AT = 21
        const val OFFSET_LENGTH = 5
        const val NANOS = 1_000_000_000L
        const val ZERO = '0'.code
        const val SLASH = '/'.code.toByte()
        const val COLON = ':'.code.toByte()
        const val SPACE = ' '.code.toByte()
        const val PLUS = '+'.code.toByte()
        const val MINUS = '-'.code.toByte()
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            .map { it.toByteArray() }

        /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
        fun epochDay(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = Math.floorDiv(y, 400)
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097 + dayOfEra - 719_468
        }
    }
}
