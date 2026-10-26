package io.github.rastislavpapp.logxy.core.query

/**
 * This query with [rules] joined to it with AND: appended to its items if it is a group matching all of them, else
 * in a new such group after it. An empty query gives a group of [rules] alone.
 */
fun Query.Group.and(rules: List<Query>): Query.Group = joined(rules) { it }

/** This query with [rule] joined to it with AND, as [and] joins several. */
fun Query.Group.and(rule: Query): Query.Group = and(listOf(rule))

/**
 * This query showing only the records whose [field] is at or after [from] and before [to], both time literals (see
 * [TimeLiteral]): the time range's comparisons replace those on [field] at the top of the query, joined with AND.
 */
fun Query.Group.withTimeRange(field: String, from: String, to: String): Query.Group {
    val range = listOf(Query.Rule(field, QueryOp.AT_LEAST, from), Query.Rule(field, QueryOp.BELOW, to))
    return joined(range) { items -> items.filterNot { isTimeRule(it, field) } }
}

/** This query without the time range on [field] that [withTimeRange] sets. */
fun Query.Group.withoutTimeRange(field: String): Query.Group =
    joined(emptyList()) { items -> items.filterNot { isTimeRule(it, field) } }

/** Whether this query has a time range on [field] that [withTimeRange] would replace. */
fun Query.Group.hasTimeRange(field: String): Boolean = all && !negated && items.any { isTimeRule(it, field) }

/** A comparison on [field] joined at the top: one that [withTimeRange] replaces. */
private fun isTimeRule(item: Query, field: String) =
    item is Query.Rule && !item.negated && item.op.comparison && item.field.equals(field, ignoreCase = true)

/**
 * This query with [rules] joined with AND. If it is a group matching all of its items, [rules] are appended to the
 * items [keep] picks from them; otherwise the whole query stays, as the first item of a new group.
 */
private fun Query.Group.joined(rules: List<Query>, keep: (List<Query>) -> List<Query>): Query.Group = when {
    items.isEmpty() -> Query.Group(all = true, items = rules)
    all && !negated -> copy(items = keep(items) + rules)
    else -> Query.Group(all = true, items = listOf(this) + rules)
}
