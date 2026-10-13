package io.github.rastislavpapp.logxy.core

import java.math.BigDecimal
import java.math.RoundingMode

private const val MICRO = 1_000L
private const val MILLI = 1_000_000L
private const val SECOND = 1_000_000_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * Formats a span of time in nanoseconds for display, in the largest unit that fits,
 * e.g. `500 ns`, `12.5 ms`, `3.25 s`, `1 h 2 min 3.5 s`; negative spans get a minus sign.
 */
fun formatTimeSpan(nanos: Long): String {
    if (nanos < 0) return "-" + formatTimeSpan(if (nanos == Long.MIN_VALUE) Long.MAX_VALUE else -nanos)
    return when {
        nanos < MICRO -> "$nanos ns"
        nanos < MILLI -> "${decimal(nanos, MICRO)} µs"
        nanos < SECOND -> "${decimal(nanos, MILLI)} ms"
        nanos < MINUTE -> "${decimal(nanos, SECOND)} s"
        else -> buildList {
            val days = nanos / DAY
            if (days > 0) add("$days d")
            if (nanos >= HOUR) add("${nanos % DAY / HOUR} h")
            add("${nanos % HOUR / MINUTE} min")
            // Milliseconds are enough next to minutes.
            add("${decimal(nanos % MINUTE / MILLI * MILLI, SECOND)} s")
        }.joinToString(" ")
    }
}

/** [nanos] in [unit]s, with at most three decimals and no trailing zeros. */
private fun decimal(nanos: Long, unit: Long): String {
    val value = BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(unit), 3, RoundingMode.DOWN)
    return value.stripTrailingZeros().toPlainString()
}
