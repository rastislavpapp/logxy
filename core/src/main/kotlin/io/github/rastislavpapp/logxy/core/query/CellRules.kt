package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import java.time.ZoneId

/** The rules a cell's menu offers, to show only the records with the cell's value or all but them. */
object CellRules {

    /** The most characters of a value's first line a rule matches; a longer line is matched by its start. */
    const val MAX_CHARS = 1000

    private val RANGE_OPS = listOf(QueryOp.AT_LEAST, QueryOp.ABOVE, QueryOp.AT_MOST, QueryOp.BELOW)

    /**
     * `field == value` and `field != value` for the cell of [field] holding [value], the field's whole value rather
     * than the cell's text. The value is matched as it is, with its wildcards escaped; a value with more lines, such
     * as a message with its stack trace, or a first line over [MAX_CHARS], is matched by its start. In the level field,
     * the record's [level] is matched as a bare level, unless it is [Level.UNKNOWN]. An empty value gives only the `!=`
     * rule, which keeps the records with a value, as `== ""` is a placeholder that filters nothing.
     */
    fun values(field: Field, value: String, level: Level?): List<Query.Rule> {
        val known = knownLevel(field, level)
        val rules = listOf(QueryOp.EQUALS, QueryOp.NOT_EQUALS).map { op ->
            if (known != null) {
                Query.Rule(field.name, op, known.name, bare = true)
            } else {
                Query.Rule(field.name, op, pattern(value))
            }
        }
        return if (value.isEmpty()) rules.drop(1) else rules
    }

    /** The comparisons with the record's [level], in the level field, unless it is [Level.UNKNOWN]. */
    fun levelRanges(field: Field, level: Level?): List<Query.Rule> {
        val known = knownLevel(field, level) ?: return emptyList()
        return RANGE_OPS.map { Query.Rule(field.name, it, known.name, bare = true) }
    }

    /**
     * The comparisons with the record's [time] in epoch nanoseconds, in a timestamp field. The time is written as a
     * literal in [zone], as the log's own layout may not parse back (e.g. offsets).
     */
    fun timeRanges(field: Field, time: Long?, zone: ZoneId): List<Query.Rule> {
        if (time == null || field.type != ColumnType.TIMESTAMP) return emptyList()
        val literal = TimeLiteral.format(time, zone)
        return RANGE_OPS.map { Query.Rule(field.name, it, literal) }
    }

    private fun knownLevel(field: Field, level: Level?) =
        level?.takeIf { field.role == Role.LEVEL && it != Level.UNKNOWN }

    private fun pattern(value: String): String {
        val firstLine = value.lineSequence().first()
        val escaped = escapeWildcards(firstLine.take(MAX_CHARS))
        return if (firstLine.length > MAX_CHARS || firstLine.length < value.length) "$escaped*" else escaped
    }
}
