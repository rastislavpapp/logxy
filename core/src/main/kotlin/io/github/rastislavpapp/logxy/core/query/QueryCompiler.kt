package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.NumberCondition
import io.github.rastislavpapp.logxy.core.filter.PatternCondition
import io.github.rastislavpapp.logxy.core.filter.PresenceCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.filter.TimeCondition
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Turns a [Query] into an engine [Filter] for logs with [schema]; throws [QueryException] for what does not fit. */
class QueryCompiler(private val schema: Schema, private val zone: ZoneId = ZoneId.systemDefault()) {

    /**
     * Conditions with an empty value are placeholders and ignored, like groups left with nothing in them;
     * except `!= ""`, which keeps records with a non-empty value.
     */
    fun compile(query: Query): Filter = compileOrNull(query) ?: Filter.And(emptyList())

    private fun compileOrNull(query: Query): Filter? {
        val filter = when (query) {
            is Query.Group -> {
                val items = query.items.mapNotNull(::compileOrNull)
                if (items.isEmpty()) return null
                if (query.all) Filter.And(items) else Filter.Or(items)
            }
            is Query.Rule -> if (query.value.isEmpty() && !query.op.excludesEmpty) return null else rule(query)
        }
        return if (query.negated) Filter.Not(filter) else filter
    }

    private fun rule(rule: Query.Rule): Filter {
        val field = schema.fields.firstOrNull { it.name.equals(rule.field, ignoreCase = true) }
        // A field of the log named so wins over the template.
        if (field == null && rule.field.equals(Query.PATTERN_FIELD, ignoreCase = true)) return pattern(rule)
        field ?: throw QueryException("Unknown field “${rule.field}”; this log has ${schema.fields.joinToString { it.name }}")
        val name = field.name
        if (rule.op.comparison) {
            return when (field.type) {
                ColumnType.LEVEL -> levelComparison(name, rule)
                ColumnType.TIMESTAMP -> timeComparison(name, rule)
                else -> numberComparison(name, rule)
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
        // Absent values match negated conditions, so they need ruling out too.
        if (rule.value.isEmpty()) return Filter.And(listOf(PresenceCondition(name), TextCondition(name, op, "", ignoreCase)))
        if (op == TextOp.MATCHES || op == TextOp.NOT_MATCHES) {
            try {
                Pattern.compile(rule.value)
            } catch (e: PatternSyntaxException) {
                throw QueryException("Invalid regex “${rule.value}”: ${e.description}")
            }
        }
        return TextCondition(name, op, rule.value, ignoreCase)
    }

    /** Templates match exactly; `==` and `===` alike, as templates have no wildcards. */
    private fun pattern(rule: Query.Rule): Filter {
        val message = schema.fields.firstOrNull { it.role == Role.MESSAGE }
            ?: throw QueryException("${Query.PATTERN_FIELD} needs a message, which this log does not have")
        val condition = PatternCondition(message.name, setOf(rule.value))
        return when (rule.op) {
            QueryOp.EQUALS, QueryOp.EQUALS_CASE -> condition
            QueryOp.NOT_EQUALS, QueryOp.NOT_EQUALS_CASE -> Filter.Not(condition)
            else -> throw QueryException("${Query.PATTERN_FIELD} works with == and !=, not ${rule.op.symbol}")
        }
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

    private fun numberComparison(field: String, rule: Query.Rule): Filter {
        val number = Bytes.parseNumber(rule.value)
        if (number.isNaN()) throw QueryException("${rule.op.symbol} on $field compares numbers; “${rule.value}” is no number")
        return when (rule.op) {
            QueryOp.AT_LEAST -> NumberCondition(field, from = number)
            QueryOp.ABOVE -> NumberCondition(field, from = Math.nextUp(number))
            QueryOp.AT_MOST -> NumberCondition(field, to = number)
            else -> NumberCondition(field, to = Math.nextDown(number))
        }
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

    /** [nanos] as [parse] reads it back: date, time and as many fraction digits as needed, at least milliseconds. */
    fun format(nanos: Long, zone: ZoneId): String {
        val time = Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L)).atZone(zone)
        val fraction = "%09d".format(time.nano).trimEnd('0').padEnd(3, '0')
        return "%04d-%02d-%02d %02d:%02d:%02d.%s".format(
            time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second, fraction,
        )
    }

    private fun toNanos(date: LocalDate, hour: String, minute: String, second: String, fraction: String, zone: ZoneId): Long {
        val time = LocalTime.of(hour.ifEmpty { "0" }.toInt(), minute.ifEmpty { "0" }.toInt(), second.ifEmpty { "0" }.toInt())
        val instant = LocalDateTime.of(date, time).atZone(zone).toInstant()
        return instant.epochSecond * 1_000_000_000 + fraction.padEnd(9, '0').ifEmpty { "0" }.toLong()
    }
}
