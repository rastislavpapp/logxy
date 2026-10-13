package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.log.Level

/** A condition tree over the fields of a log's records; see [FilterEngine]. */
sealed interface Filter {
    /** Every one of [parts] matches; with no parts, every record does. */
    data class And(val parts: List<Filter>) : Filter

    /** At least one of [parts] matches; with no parts, no record does. */
    data class Or(val parts: List<Filter>) : Filter

    /** [part] does not match. */
    data class Not(val part: Filter) : Filter
}

/** A leaf of a [Filter] on one field, named as in the log's schema. Fields the log lacks match nothing. */
sealed interface Condition : Filter {
    val field: String
}

/** [EQUALS] and [NOT_EQUALS] take wildcard patterns (see [Glob]), [MATCHES] and [NOT_MATCHES] regexes (found anywhere). */
enum class TextOp { EQUALS, NOT_EQUALS, MATCHES, NOT_MATCHES }

/** Compares the field's text, as shown in the table. Absent values match only the negated ops. */
data class TextCondition(
    override val field: String,
    val op: TextOp,
    val value: String,
    val ignoreCase: Boolean = false,
) : Condition

/** Compares a record's whole text as written in the file, e.g. for searching; [field] is not one of the log's. */
data class RecordTextCondition(val op: TextOp, val value: String, val ignoreCase: Boolean = false) : Condition {
    override val field get() = RECORD_TEXT

    /** The same test as a [TextCondition], for the text matcher that both share. */
    internal fun asText() = TextCondition(RECORD_TEXT, op, value, ignoreCase)

    companion object {
        /** The pseudo field name of the whole record; the leading NUL keeps it apart from the fields of real logs. */
        const val RECORD_TEXT = "\u0000record"
    }
}

/** The record has a value of [field] ([present]), or has none (not [present]). A log without the field has none. */
data class PresenceCondition(override val field: String, val present: Boolean = true) : Condition

/** The record's canonical level is one of [levels]. */
data class LevelCondition(override val field: String, val levels: Set<Level>) : Condition {
    companion object {
        /** [level] or more severe; never [Level.UNKNOWN]. */
        fun atLeast(field: String, level: Level) =
            LevelCondition(field, Level.entries.filter { it != Level.UNKNOWN && it >= level }.toSet())

        /** [level] or less severe; never [Level.UNKNOWN]. */
        fun atMost(field: String, level: Level) =
            LevelCondition(field, Level.entries.filter { it != Level.UNKNOWN && it <= level }.toSet())
    }
}

/** The template of the message [field] (see [io.github.rastislavpapp.logxy.core.pattern.Templates]) is one of [templates]. */
data class PatternCondition(override val field: String, val templates: Set<String>) : Condition

/** The timestamp, in epoch nanoseconds, lies in `[from, to]`; a null bound is open. */
data class TimeCondition(override val field: String, val from: Long? = null, val to: Long? = null) : Condition

/**
 * The value, read as a decimal number, lies in `[from, to]`; a null bound is open. Values that are no
 * number match nothing, like absent ones.
 */
data class NumberCondition(override val field: String, val from: Double? = null, val to: Double? = null) : Condition {
    /** Whether [number] lies in the range; false for NaN, which stands for a value that is no number. */
    internal fun matches(number: Double) = number >= (from ?: Double.NEGATIVE_INFINITY) && number <= (to ?: Double.POSITIVE_INFINITY)
}
