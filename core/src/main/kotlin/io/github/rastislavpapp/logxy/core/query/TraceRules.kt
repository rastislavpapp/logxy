package io.github.rastislavpapp.logxy.core.query

/** The filter the Traces panel sets to show the records of the traces chosen in it. */
object TraceRules {

    /**
     * The query that shows only the records whose [column] holds one of [ids]: `column == "id"` for one, joined with
     * OR for more. Each trace id is matched as it is, with its wildcards escaped.
     */
    fun showOnly(column: String, ids: List<String>): Query.Group =
        Query.Group(all = ids.size <= 1, items = ids.map { Query.Rule(column, QueryOp.EQUALS, escapeWildcards(it)) })
}
