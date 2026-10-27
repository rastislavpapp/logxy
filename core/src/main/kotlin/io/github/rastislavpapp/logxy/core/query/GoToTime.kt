package io.github.rastislavpapp.logxy.core.query

import java.time.Instant
import java.time.ZoneId

/**
 * The time Go to goes to, as typed: a [TimeLiteral], or a time relative to another, such as `1h30m` forward or
 * `-10s` back. A literal always has a colon or is a date, and a relative time has a unit letter, so the two never
 * clash.
 */
object GoToTime {

    private val RELATIVE = Regex("""([+-]?)((?:\d+[smhd])+)""")
    private val AMOUNT = Regex("""(\d+)([smhd])""")

    /**
     * Epoch nanoseconds of [text]. [from] is the time a relative one counts from, and the day of a time alone, both
     * in [zone]. Throws [QueryException] for text that is neither.
     */
    fun parse(text: String, from: Long, zone: ZoneId): Long {
        val trimmed = text.trim()
        val relative = RELATIVE.matchEntire(trimmed)
        if (relative != null) {
            return try {
                val nanos = AMOUNT.findAll(relative.groupValues[2]).fold(0L) { sum, amount ->
                    Math.addExact(sum, amountNanos(amount))
                }
                if (relative.groupValues[1] == "-") Math.subtractExact(from, nanos) else Math.addExact(from, nanos)
            } catch (_: ArithmeticException) {
                throw QueryException("“$text” is too far from any log's time")
            }
        }
        val day = Instant.ofEpochSecond(Math.floorDiv(from, NANOS_PER_SECOND)).atZone(zone).toLocalDate()
        return try {
            TimeLiteral.parse(trimmed, zone, day)
        } catch (_: QueryException) {
            throw QueryException(
                "Invalid time “$text”; write e.g. 2026-10-09 08:30:00.000, 08:30, 1h30m forward or -10s back",
            )
        }
    }

    private fun amountNanos(amount: MatchResult): Long {
        val seconds = when (amount.groupValues[2]) {
            "s" -> 1L
            "m" -> SECONDS_PER_MINUTE
            "h" -> SECONDS_PER_HOUR
            else -> SECONDS_PER_DAY
        }
        // Too many digits for a Long is too far, like an amount that overflows.
        val count = amount.groupValues[1].toLongOrNull() ?: throw ArithmeticException("too many digits")
        return Math.multiplyExact(count, seconds * NANOS_PER_SECOND)
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_DAY = 86_400L
}
