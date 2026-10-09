package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.filter.TimeCondition
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Turns a [Query] into an engine [Filter] for logs with [schema]; throws [QueryException] for what does not fit. */
class QueryCompiler(private val schema: Schema, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** Conditions with an empty value are placeholders and ignored, like groups left with nothing in them. */
    fun compile(query: Query): Filter = compileOrNull(query) ?: Filter.And(emptyList())

    private fun compileOrNull(query: Query): Filter? {
        val filter = when (query) {
            is Query.Group -> {
                val items = query.items.mapNotNull(::compileOrNull)
                if (items.isEmpty()) return null
                if (query.all) Filter.And(items) else Filter.Or(items)
            }
            is Query.Rule -> if (query.value.isEmpty()) return null else rule(query)
        }
        return if (query.negated) Filter.Not(filter) else filter
    }

    private fun rule(rule: Query.Rule): Filter {
        val field = schema.fields.firstOrNull { it.name.equals(rule.field, ignoreCase = true) }
            ?: throw QueryException("Unknown field “${rule.field}”; this log has ${schema.fields.joinToString { it.name }}")
        val name = field.name
        if (rule.op.comparison) {
            return when (field.type) {
                ColumnType.LEVEL -> levelComparison(name, rule)
                ColumnType.TIMESTAMP -> timeComparison(name, rule)
                else -> throw QueryException("${rule.op.symbol} works on levels and timestamps, not on $name")
            }
        }
        if (field.type == ColumnType.LEVEL && rule.bare && (rule.op == QueryOp.EQUALS || rule.op == QueryOp.NOT_EQUALS)) {
            val condition = LevelCondition(name, setOf(level(rule.value)))
            return if (rule.op == QueryOp.EQUALS) condition else Filter.Not(condition)
        }
        val (op, ignoreCase) = when (rule.op) {
            QueryOp.EQUALS -> TextOp.EQUALS to true
            QueryOp.NOT_EQUALS -> TextOp.NOT_EQUALS to true
            QueryOp.EQUALS_CASE -> TextOp.EQUALS to false
            QueryOp.NOT_EQUALS_CASE -> TextOp.NOT_EQUALS to false
            QueryOp.MATCHES -> TextOp.MATCHES to true
            QueryOp.NOT_MATCHES -> TextOp.NOT_MATCHES to true
            else -> error("comparison handled above")
        }
        if (op == TextOp.MATCHES || op == TextOp.NOT_MATCHES) {
            try {
                Pattern.compile(rule.value)
            } catch (e: PatternSyntaxException) {
                throw QueryException("Invalid regex “${rule.value}”: ${e.description}")
            }
        }
        return TextCondition(name, op, rule.value, ignoreCase)
    }

    private fun level(text: String): Level = Level.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
        ?: throw QueryException("Unknown level “$text”; use one of ${Level.entries.joinToString()}")

    private fun levelComparison(field: String, rule: Query.Rule): Filter {
        val bound = level(rule.value)
        val levels = Level.entries.filter { it != Level.UNKNOWN }.filter { level ->
            when (rule.op) {
                QueryOp.AT_LEAST -> level >= bound
                QueryOp.ABOVE -> level > bound
                QueryOp.AT_MOST -> level <= bound
                else -> level < bound
            }
        }
        return LevelCondition(field, levels.toSet())
    }

    private fun timeComparison(field: String, rule: Query.Rule): Filter {
        val nanos = TimeLiteral.parse(rule.value, zone)
        return when (rule.op) {
            QueryOp.AT_LEAST -> TimeCondition(field, from = nanos)
            QueryOp.ABOVE -> TimeCondition(field, from = nanos + 1)
            QueryOp.AT_MOST -> TimeCondition(field, to = nanos)
            else -> TimeCondition(field, to = nanos - 1)
        }
    }
}

/** Points in time as typed in queries: `2026-10-09`, `2026-10-09 08:30`, `…:15.250`, or a time alone. */
object TimeLiteral {

    private val DATE_TIME = Regex("""(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)?""")
    private val TIME = Regex("""(\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?""")

    /** Epoch nanoseconds; a time alone is on 1970-01-01, like timestamps of logs that only print the time. */
    fun parse(text: String, zone: ZoneId): Long {
        val trimmed = text.trim()
        try {
            DATE_TIME.matchEntire(trimmed)?.let { m ->
                val g = m.groupValues
                val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                return toNanos(date, g[4], g[5], g[6], g[7], zone)
            }
            TIME.matchEntire(trimmed)?.let { m ->
                val g = m.groupValues
                return toNanos(LocalDate.EPOCH, g[1], g[2], g[3], g[4], zone)
            }
        } catch (e: DateTimeException) {
            throw QueryException("Invalid time “$text”: ${e.message}")
        }
        throw QueryException("Invalid time “$text”; write e.g. 2026-10-09 08:30:00.000, 2026-10-09 or 08:30")
    }

    private fun toNanos(date: LocalDate, hour: String, minute: String, second: String, fraction: String, zone: ZoneId): Long {
        val time = LocalTime.of(hour.ifEmpty { "0" }.toInt(), minute.ifEmpty { "0" }.toInt(), second.ifEmpty { "0" }.toInt())
        val instant = LocalDateTime.of(date, time).atZone(zone).toInstant()
        return instant.epochSecond * 1_000_000_000 + fraction.padEnd(9, '0').ifEmpty { "0" }.toLong()
    }
}
