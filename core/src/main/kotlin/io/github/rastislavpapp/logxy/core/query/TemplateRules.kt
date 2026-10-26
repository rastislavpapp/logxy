package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.template.Templates

/** Rules on message templates and their variables, as the app adds them to a filter, joined with AND at its top. */
object TemplateRules {

    /** `Template op "…"`, with [template]'s variables numbered as the user sees them. */
    fun template(template: String, op: QueryOp): Query.Rule =
        Query.Rule(Query.TEMPLATE_FIELD, op, Templates.numbered(template))

    /**
     * The rule to join to [current] to show only the records of [template] whose variable value at template
     * variable [variable], counted from 0, is [value].
     *
     * If [current]'s only `Template ==` rule at its top is the one for [template], that is the variable rule
     * alone. Else it is the variable rule in a group with a `Template ==` rule, as a variable rule needs one to
     * belong to.
     */
    fun showOnly(current: Query.Group, template: String, variable: Int, value: String): Query {
        val rule = variable(variable, value)
        val templates = if (current.all && !current.negated) current.items.filter(::isTemplateEquals) else emptyList()
        val own = (templates.singleOrNull() as Query.Rule?)?.let { Templates.unnumbered(it.value) == template } == true
        return if (own) rule else Query.Group(all = true, items = listOf(template(template, QueryOp.EQUALS), rule))
    }

    /** The rule that shows only the records of [templates]: the one `Template ==` rule, or an OR group of them. */
    fun showOnly(templates: List<String>): Query {
        val rules = templates.map { template(it, QueryOp.EQUALS) }
        return rules.singleOrNull() ?: Query.Group(all = false, items = rules)
    }

    /** The rules that hide the records of [templates], a `Template !=` rule each, to join with AND. */
    fun hide(templates: List<String>): List<Query.Rule> = templates.map { template(it, QueryOp.NOT_EQUALS) }

    /**
     * The rule that hides the records of [template] whose variable value at template variable [variable], counted
     * from 0, is [value], keeping all others.
     */
    fun hide(template: String, variable: Int, value: String): Query {
        val items = listOf(template(template, QueryOp.EQUALS), variable(variable, value))
        return Query.Group(all = true, items = items, negated = true)
    }

    /** `{n} == value`, with wildcards in [value] escaped, as variable values are matched exactly. */
    private fun variable(variable: Int, value: String) =
        Query.Rule("{${variable + 1}}", QueryOp.EQUALS, escapeWildcards(value))

    private fun isTemplateEquals(item: Query) = item is Query.Rule && !item.negated &&
        item.field.equals(Query.TEMPLATE_FIELD, ignoreCase = true) &&
        (item.op == QueryOp.EQUALS || item.op == QueryOp.EQUALS_CASE)
}
