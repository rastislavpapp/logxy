package io.github.rastislavpapp.logxy.core.merge

/**
 * A timestamp shift as the user types and reads it: an amount in nanoseconds added to every timestamp of one file of
 * a merge, for a file logged in another time zone. Whole hours are the usual case: `2`, `+2` or `-5` is hours, and
 * `+5:30` or `-9:45` adds minutes. At most [MAX_HOURS] hours either way.
 */
object TimestampShift {

    const val MAX_HOURS = 26

    private const val NANOS_PER_MINUTE = 60_000_000_000L
    private const val MINUTES_PER_HOUR = 60
    private const val NANOS_PER_HOUR = NANOS_PER_MINUTE * MINUTES_PER_HOUR
    private const val MAX = MAX_HOURS * NANOS_PER_HOUR

    private val TEXT = Regex("""([+-]?)(\d{1,2})(?:h|:(\d{2}))?""")

    /** The shift [text] stands for; 0 if it is empty, null if it is no shift or beyond [MAX_HOURS]. */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        val match = TEXT.matchEntire(trimmed) ?: return null
        val (sign, hours, minutes) = match.destructured
        val minute = minutes.ifEmpty { "0" }.toInt()
        if (minute >= MINUTES_PER_HOUR) return null
        val nanos = hours.toLong() * NANOS_PER_HOUR + minute * NANOS_PER_MINUTE
        if (nanos > MAX) return null
        return if (sign == "-") -nanos else nanos
    }

    /** [nanos] as a cell marks a shifted time, e.g. `+2h` or `-9:45`; empty for no shift. */
    fun format(nanos: Long): String {
        if (nanos == 0L) return ""
        val sign = if (nanos < 0) "-" else "+"
        val minutes = Math.abs(nanos) / NANOS_PER_MINUTE
        val hours = minutes / MINUTES_PER_HOUR
        val minute = minutes % MINUTES_PER_HOUR
        return if (minute == 0L) "$sign${hours}h" else "$sign$hours:%02d".format(minute)
    }

    /** [nanos] an hour later, or earlier unless [up], kept within [MAX_HOURS]. */
    fun step(nanos: Long, up: Boolean): Long =
        (nanos + if (up) NANOS_PER_HOUR else -NANOS_PER_HOUR).coerceIn(-MAX, MAX)
}
