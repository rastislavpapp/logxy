package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level

/** An operator as the visual editor offers it; several give the same [QueryOp] with a wildcard shape. */
enum class EditorOp {
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    IS,
    IS_NOT,
    MATCHES,
    NOT_MATCHES,
    LEVEL_AT_LEAST,
    LEVEL_ABOVE,
    LEVEL_AT_MOST,
    LEVEL_BELOW,
    LEVEL_IS,
    LEVEL_IS_NOT,
    TIME_FROM,
    TIME_AFTER,
    TIME_TO,
    TIME_BEFORE,
    NUMBER_AT_LEAST,
    NUMBER_ABOVE,
    NUMBER_AT_MOST,
    NUMBER_BELOW,
    ;

    /** Whether the line compares with a picked level rather than typed text. */
    val usesLevel get() = name.startsWith("LEVEL_")

    /** Whether the line's match case option counts. */
    val usesCase get() = this in setOf(CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, IS, IS_NOT)

    companion object {
        val TEXT = listOf(CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, IS, IS_NOT, MATCHES, NOT_MATCHES)
        val LEVEL = listOf(LEVEL_AT_LEAST, LEVEL_ABOVE, LEVEL_AT_MOST, LEVEL_BELOW, LEVEL_IS, LEVEL_IS_NOT)
        val TIME = listOf(TIME_FROM, TIME_AFTER, TIME_TO, TIME_BEFORE)
        val NUMBER = listOf(NUMBER_AT_LEAST, NUMBER_ABOVE, NUMBER_AT_MOST, NUMBER_BELOW)

        /**
         * The operator a line takes when its field turns to one of [type], [selected] before: a level field compares
         * by at least, a time field by at or after, unless one of their own comparisons is [selected] already.
         */
        fun forField(type: ColumnType?, selected: EditorOp): EditorOp = when {
            type == ColumnType.LEVEL && selected !in LEVEL -> LEVEL_AT_LEAST
            type == ColumnType.TIMESTAMP && selected !in TIME -> TIME_FROM
            else -> selected
        }
    }
}

/**
 * A rule as one line of the visual editor shows it: on [field], the plain operator [op] with the typed [value], such
 * as "contains" `timeout` for `message == "*timeout*"`. [level] is the level picked for a level operator, and null if
 * the rule names none; [matchCase] is the line's match case option. Lines have no NOT of their own.
 */
data class EditorRule(
    val field: String,
    val op: EditorOp,
    val value: String = "",
    val level: Level? = null,
    val matchCase: Boolean = false,
) {

    /**
     * The query rule this line stands for; [template] tells whether [field] is the message template, which is always
     * matched ignoring case. A line without a value is left out, giving null, unless [includeEmpty]; it then gives a
     * placeholder rule, `field == ""`, which filters nothing. The exception is "is not" with an empty value: it gives
     * `field != ""`, which keeps the records that have a value.
     */
    fun rule(template: Boolean, includeEmpty: Boolean): Query.Rule? {
        val notEmpty = op == EditorOp.IS_NOT && value.isEmpty()
        if (!op.usesLevel && value.isEmpty() && !includeEmpty && !notEmpty) return null
        val exact = matchCase && !template
        val equals = if (exact) QueryOp.EQUALS_CASE else QueryOp.EQUALS
        val notEquals = if (exact) QueryOp.NOT_EQUALS_CASE else QueryOp.NOT_EQUALS
        if (notEmpty) return Query.Rule(field, notEquals, "")
        if (value.isEmpty() && !op.usesLevel) {
            val regex = op == EditorOp.MATCHES || op == EditorOp.NOT_MATCHES
            val placeholderOp = if (regex) QueryOp.MATCHES else equals
            return Query.Rule(field, placeholderOp, "")
        }
        val levelName = level?.name.orEmpty()
        val (queryOp, queryValue, bare) = when (op) {
            EditorOp.CONTAINS -> Triple(equals, "*$value*", false)
            EditorOp.NOT_CONTAINS -> Triple(notEquals, "*$value*", false)
            EditorOp.STARTS_WITH -> Triple(equals, "$value*", false)
            EditorOp.ENDS_WITH -> Triple(equals, "*$value", false)
            EditorOp.IS -> Triple(equals, value, false)
            EditorOp.IS_NOT -> Triple(notEquals, value, false)
            EditorOp.MATCHES -> Triple(QueryOp.MATCHES, value, false)
            EditorOp.NOT_MATCHES -> Triple(QueryOp.NOT_MATCHES, value, false)
            EditorOp.LEVEL_AT_LEAST -> Triple(QueryOp.AT_LEAST, levelName, true)
            EditorOp.LEVEL_ABOVE -> Triple(QueryOp.ABOVE, levelName, true)
            EditorOp.LEVEL_AT_MOST -> Triple(QueryOp.AT_MOST, levelName, true)
            EditorOp.LEVEL_BELOW -> Triple(QueryOp.BELOW, levelName, true)
            EditorOp.LEVEL_IS -> Triple(QueryOp.EQUALS, levelName, true)
            EditorOp.LEVEL_IS_NOT -> Triple(QueryOp.NOT_EQUALS, levelName, true)
            EditorOp.TIME_FROM -> Triple(QueryOp.AT_LEAST, value, false)
            EditorOp.TIME_AFTER -> Triple(QueryOp.ABOVE, value, false)
            EditorOp.TIME_TO -> Triple(QueryOp.AT_MOST, value, false)
            EditorOp.TIME_BEFORE -> Triple(QueryOp.BELOW, value, false)
            EditorOp.NUMBER_AT_LEAST -> Triple(QueryOp.AT_LEAST, value, true)
            EditorOp.NUMBER_ABOVE -> Triple(QueryOp.ABOVE, value, true)
            EditorOp.NUMBER_AT_MOST -> Triple(QueryOp.AT_MOST, value, true)
            EditorOp.NUMBER_BELOW -> Triple(QueryOp.BELOW, value, true)
        }
        return Query.Rule(field, queryOp, queryValue, bare)
    }

    companion object {

        /**
         * The line showing [original]. [type] is the type of the rule's field, or null if the log has no such field;
         * [template] tells whether the field is the message template. A negated rule takes the opposite operator,
         * `NOT n >= 5` the line `n < 5`, though for the records without a value that is not the same filter.
         */
        fun of(original: Query.Rule, type: ColumnType?, template: Boolean): EditorRule {
            val rule = if (original.negated) original.copy(op = original.op.opposite, negated = false) else original
            val level = Level.entries.firstOrNull { it.name.equals(rule.value, ignoreCase = true) }
            val bareLevel = type == ColumnType.LEVEL && rule.bare && level != null
            val negated = rule.op == QueryOp.NOT_EQUALS || rule.op == QueryOp.NOT_EQUALS_CASE
            val (op, value) = when {
                rule.op.comparison && type == ColumnType.LEVEL -> when (rule.op) {
                    QueryOp.AT_LEAST -> EditorOp.LEVEL_AT_LEAST
                    QueryOp.ABOVE -> EditorOp.LEVEL_ABOVE
                    QueryOp.AT_MOST -> EditorOp.LEVEL_AT_MOST
                    else -> EditorOp.LEVEL_BELOW
                } to rule.value
                rule.op.comparison && type != ColumnType.TIMESTAMP -> when (rule.op) {
                    QueryOp.AT_LEAST -> EditorOp.NUMBER_AT_LEAST
                    QueryOp.ABOVE -> EditorOp.NUMBER_ABOVE
                    QueryOp.AT_MOST -> EditorOp.NUMBER_AT_MOST
                    else -> EditorOp.NUMBER_BELOW
                } to rule.value
                rule.op.comparison -> when (rule.op) {
                    QueryOp.AT_LEAST -> EditorOp.TIME_FROM
                    QueryOp.ABOVE -> EditorOp.TIME_AFTER
                    QueryOp.AT_MOST -> EditorOp.TIME_TO
                    else -> EditorOp.TIME_BEFORE
                } to rule.value
                bareLevel && rule.op == QueryOp.EQUALS -> EditorOp.LEVEL_IS to rule.value
                bareLevel && rule.op == QueryOp.NOT_EQUALS -> EditorOp.LEVEL_IS_NOT to rule.value
                template -> (if (negated) EditorOp.IS_NOT else EditorOp.IS) to rule.value
                rule.op == QueryOp.MATCHES -> EditorOp.MATCHES to rule.value
                rule.op == QueryOp.NOT_MATCHES -> EditorOp.NOT_MATCHES to rule.value
                // An empty value is a placeholder; show it as the default "contains".
                rule.value.isEmpty() && rule.op == QueryOp.EQUALS -> EditorOp.CONTAINS to ""
                else -> globShape(rule.value, negated)
            }
            val matchCase = rule.op == QueryOp.EQUALS_CASE || rule.op == QueryOp.NOT_EQUALS_CASE
            return EditorRule(rule.field, op, if (op.usesLevel) "" else value, level, matchCase)
        }

        /** The plainest operator showing [pattern]: "*x*" is "contains x", "x*" is "starts with x". */
        private fun globShape(pattern: String, negated: Boolean): Pair<EditorOp, String> {
            fun starAt(i: Int) = pattern[i] == '*' && (i == 0 || pattern[i - 1] != '\\')
            fun noStars(from: Int, to: Int) = (from..<to).none { starAt(it) }
            val n = pattern.length
            return when {
                n >= 2 && starAt(0) && starAt(n - 1) && noStars(1, n - 1) ->
                    (if (negated) EditorOp.NOT_CONTAINS else EditorOp.CONTAINS) to pattern.substring(1, n - 1)
                !negated && n >= 1 && starAt(n - 1) && noStars(0, n - 1) ->
                    EditorOp.STARTS_WITH to pattern.substring(0, n - 1)
                !negated && n >= 1 && starAt(0) && noStars(1, n) -> EditorOp.ENDS_WITH to pattern.substring(1)
                else -> (if (negated) EditorOp.IS_NOT else EditorOp.IS) to pattern
            }
        }
    }
}
