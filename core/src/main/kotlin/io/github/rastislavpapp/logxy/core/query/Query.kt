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
    }
}

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
    BELOW("<");

    val comparison get() = this == AT_LEAST || this == ABOVE || this == AT_MOST || this == BELOW

    /** With an empty value, keeps records that have a non-empty one; other ops make an empty value a placeholder. */
    val excludesEmpty get() = this == NOT_EQUALS || this == NOT_EQUALS_CASE
}

/** A problem in a query, at `[offset, offset + length)` of its text when known. */
class QueryException(message: String, val offset: Int = -1, val length: Int = 0) : Exception(message)
