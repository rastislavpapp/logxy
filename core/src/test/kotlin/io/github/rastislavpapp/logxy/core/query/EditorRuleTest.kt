package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorRuleTest {

    private fun load(rule: Query.Rule, type: ColumnType? = ColumnType.SPAN, template: Boolean = false) =
        EditorRule.of(rule, type, template)

    private fun printed(line: EditorRule, includeEmpty: Boolean = false, template: Boolean = false) =
        line.rule(template, includeEmpty)?.let(QueryPrinter::print)

    @Test
    fun `every operator round trips through its rule`() {
        val lines = EditorOp.TEXT.flatMap { op ->
            listOf(EditorRule("Thread", op, "main"), EditorRule("Thread", op, "main", matchCase = op.usesCase))
        }.map { it to ColumnType.DICT } +
            EditorOp.LEVEL.map { EditorRule("Level", it, level = Level.WARN) to ColumnType.LEVEL } +
            EditorOp.TIME.map { EditorRule("Time", it, "2026-10-09 08:30") to ColumnType.TIMESTAMP } +
            EditorOp.NUMBER.map { EditorRule("Took", it, "-1.5") to ColumnType.SPAN }
        for ((line, type) in lines) {
            val rule = line.rule(template = false, includeEmpty = false)!!
            assertEquals(line, load(rule, type), "$line as ${QueryPrinter.print(rule)}")
        }
        for (op in listOf(EditorOp.IS, EditorOp.IS_NOT)) {
            val line = EditorRule(Query.TEMPLATE_FIELD, op, "Request {1} done")
            assertEquals(line, load(line.rule(template = true, includeEmpty = false)!!, null, template = true))
        }
    }

    @Test
    fun `lines give their rules`() {
        for ((line, expected) in listOf(
            EditorRule("Message", EditorOp.CONTAINS, "time*out") to """Message == "*time*out*"""",
            EditorRule("Message", EditorOp.NOT_CONTAINS, "x", matchCase = true) to """Message !== "*x*"""",
            EditorRule("Message", EditorOp.STARTS_WITH, "x") to """Message == "x*"""",
            EditorRule("Message", EditorOp.ENDS_WITH, "x") to """Message == "*x"""",
            EditorRule("Message", EditorOp.MATCHES, "^a\\d", matchCase = true) to """Message =~ "^a\d"""",
            EditorRule("Level", EditorOp.LEVEL_AT_LEAST, "ignored", Level.WARN) to "Level >= WARN",
            EditorRule("Level", EditorOp.LEVEL_IS_NOT, level = Level.ERROR) to "Level != ERROR",
            EditorRule("Time", EditorOp.TIME_FROM, "08:30") to """Time >= "08:30"""",
            EditorRule("Took", EditorOp.NUMBER_BELOW, "500") to "Took < 500",
        )) {
            assertEquals(expected, printed(line), line.toString())
        }
        val template = EditorRule(Query.TEMPLATE_FIELD, EditorOp.IS, "Request {1} done", matchCase = true)
        assertEquals("""Template == "Request {1} done"""", printed(template, template = true))
    }

    @Test
    fun `wildcard shapes load as the plainest operator`() {
        for ((rule, expected) in listOf(
            Query.Rule("Message", QueryOp.EQUALS, "*x*") to EditorRule("Message", EditorOp.CONTAINS, "x"),
            Query.Rule("Message", QueryOp.EQUALS, "x*") to EditorRule("Message", EditorOp.STARTS_WITH, "x"),
            Query.Rule("Message", QueryOp.EQUALS, "*x") to EditorRule("Message", EditorOp.ENDS_WITH, "x"),
            Query.Rule("Message", QueryOp.EQUALS, "*") to EditorRule("Message", EditorOp.STARTS_WITH, ""),
            Query.Rule("Message", QueryOp.EQUALS, "*x*y*") to EditorRule("Message", EditorOp.IS, "*x*y*"),
            Query.Rule("Message", QueryOp.EQUALS, """\*x\*""") to EditorRule("Message", EditorOp.IS, """\*x\*"""),
            Query.Rule("Message", QueryOp.EQUALS, """*x\*""") to EditorRule("Message", EditorOp.ENDS_WITH, """x\*"""),
            Query.Rule("Message", QueryOp.NOT_EQUALS, "*x*") to EditorRule("Message", EditorOp.NOT_CONTAINS, "x"),
            Query.Rule("Message", QueryOp.NOT_EQUALS, "x*") to EditorRule("Message", EditorOp.IS_NOT, "x*"),
            Query.Rule("Message", QueryOp.EQUALS_CASE, "*x*") to
                EditorRule("Message", EditorOp.CONTAINS, "x", matchCase = true),
        )) {
            assertEquals(expected, load(rule), rule.toString())
        }
    }

    @Test
    fun `a level named by the whole value is picked for the level operators`() {
        assertEquals(
            EditorRule("Level", EditorOp.CONTAINS, "warn"),
            load(Query.Rule("Level", QueryOp.EQUALS, "*warn*"), ColumnType.LEVEL),
        )
        assertEquals(
            EditorRule("Level", EditorOp.IS, "WARN", Level.WARN),
            load(Query.Rule("Level", QueryOp.EQUALS, "WARN"), ColumnType.LEVEL),
        )
        assertEquals(
            EditorRule("Level", EditorOp.LEVEL_IS, level = Level.WARN),
            load(Query.Rule("Level", QueryOp.EQUALS, "WARN", bare = true), ColumnType.LEVEL),
        )
    }

    @Test
    fun `empty lines give placeholder rules`() {
        assertNull(printed(EditorRule("Message", EditorOp.CONTAINS, "")))
        assertEquals("""Message == """"", printed(EditorRule("Message", EditorOp.CONTAINS, ""), includeEmpty = true))
        assertEquals(
            """Message === """"",
            printed(EditorRule("Message", EditorOp.ENDS_WITH, "", matchCase = true), includeEmpty = true),
        )
        assertEquals("""Message =~ """"", printed(EditorRule("Message", EditorOp.NOT_MATCHES, ""), includeEmpty = true))
        // "is not" nothing keeps the records with a value.
        assertEquals("""Message != """"", printed(EditorRule("Message", EditorOp.IS_NOT, "")))
        assertEquals(EditorRule("Message", EditorOp.CONTAINS, ""), load(Query.Rule("Message", QueryOp.EQUALS, "")))
    }

    // Not the same filter for records without a value: see #130.
    @Test
    fun `a negated rule loads as its opposite operator`() {
        for ((rule, expected) in listOf(
            Query.Rule("Took", QueryOp.AT_LEAST, "5", bare = true, negated = true) to
                EditorRule("Took", EditorOp.NUMBER_BELOW, "5"),
            Query.Rule("Took", QueryOp.ABOVE, "5", bare = true, negated = true) to
                EditorRule("Took", EditorOp.NUMBER_AT_MOST, "5"),
            Query.Rule("Message", QueryOp.EQUALS, "*x*", negated = true) to
                EditorRule("Message", EditorOp.NOT_CONTAINS, "x"),
            Query.Rule("Message", QueryOp.NOT_MATCHES, "x", negated = true) to
                EditorRule("Message", EditorOp.MATCHES, "x"),
        )) {
            assertEquals(expected, load(rule), rule.toString())
        }
    }
}
