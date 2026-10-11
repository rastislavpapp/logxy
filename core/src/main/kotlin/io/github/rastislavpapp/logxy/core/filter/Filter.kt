package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.log.Level

/** A condition tree over the fields of a log's records; see [FilterEngine]. */
sealed interface Filter {
    data class And(val parts: List<Filter>) : Filter
    data class Or(val parts: List<Filter>) : Filter
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
