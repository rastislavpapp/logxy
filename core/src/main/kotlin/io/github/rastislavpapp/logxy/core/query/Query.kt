package io.github.rastislavpapp.logxy.core.query

/**
 * A filter as the user edits it, in the visual editor or as text (see [QueryParser],
 * [QueryPrinter]); [QueryCompiler] turns it into an engine filter for one log.
 */
sealed interface Query {
    val negated: Boolean

    /** [all] joins the items with AND, otherwise with OR. An empty group matches everything. */
    data class Group(val all: Boolean, val items: List<Query>, override val negated: Boolean = false) : Query

    /**
     * `field op value`. [bare] values were written without quotes; for level fields a bare level
     * name means the canonical level, a quoted value the level's text.
     */
    data class Rule(
        val field: String,
        val op: QueryOp,
        val value: String,
        val bare: Boolean = false,
        override val negated: Boolean = false,
    ) : Query

    companion object {
        val EMPTY = Group(all = true, items = emptyList())

        /**
         * Not a field of the log: the template of the message, see
         * [io.github.rastislavpapp.logxy.core.template.Templates].
         */
        const val TEMPLATE_FIELD = "Template"
    }
}

/** This query with each rule on the field [from], whatever the case of its name, moved to the field [to]. */
fun Query.Group.withField(from: String, to: String): Query.Group = copy(
    items = items.map { item ->
        when (item) {
            is Query.Group -> item.withField(from, to)
            is Query.Rule -> if (item.field.equals(from, ignoreCase = true)) item.copy(field = to) else item
        }
    },
)

/** A comparison in a query rule, written as [symbol]; each `NOT_` op is the negation of the one before it. */
enum class QueryOp(val symbol: String) {
    /** Wildcard match ignoring case. */
    EQUALS("=="),
    NOT_EQUALS("!="),

    /** Wildcard match with exact case. */
    EQUALS_CASE("==="),
    NOT_EQUALS_CASE("!=="),

    /** Regex found anywhere, ignoring case. */
    MATCHES("=~"),
    NOT_MATCHES("!~"),
    AT_LEAST(">="),
    ABOVE(">"),
    AT_MOST("<="),
    BELOW("<"),
    ;

    val comparison get() = this == AT_LEAST || this == ABOVE || this == AT_MOST || this == BELOW

    /**
     * Whether a rule with this op and an empty value keeps the records that have a non-empty value. With other
     * ops, an empty value makes the rule a placeholder, which is ignored.
     */
    val excludesEmpty get() = this == NOT_EQUALS || this == NOT_EQUALS_CASE
}

/** [text] as a wildcard pattern of [QueryOp.EQUALS] that matches it as it is: `\`, `*` and `?` escaped. */
fun escapeWildcards(text: String): String = text.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")

/** A problem in a query, at `[offset, offset + length)` of its text when known. */
class QueryException(message: String, val offset: Int = -1, val length: Int = 0) : Exception(message)
