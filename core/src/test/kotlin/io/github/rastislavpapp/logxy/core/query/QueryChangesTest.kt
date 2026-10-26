package io.github.rastislavpapp.logxy.core.query

import kotlin.test.Test
import kotlin.test.assertEquals

class QueryChangesTest {

    private val rule = Query.Rule("logger", QueryOp.EQUALS, "db")

    private fun parse(text: String) = QueryParser.parse(text)

    @Test
    fun `a rule is joined with AND`() {
        for ((current, expected) in listOf(
            "" to """logger == "db"""",
            "level >= WARN" to """level >= WARN AND logger == "db"""",
            "level >= WARN AND thread == main" to """level >= WARN AND thread == main AND logger == "db"""",
            "level >= WARN OR thread == main" to """(level >= WARN OR thread == main) AND logger == "db"""",
            "NOT (level >= WARN AND thread == main)" to
                """NOT (level >= WARN AND thread == main) AND logger == "db"""",
        )) {
            assertEquals(expected, QueryPrinter.print(parse(current).and(rule)), current)
        }
    }

    @Test
    fun `an OR group plus a rule gives AND of both`() {
        val or = Query.Group(all = false, items = listOf(Query.Rule("a", QueryOp.EQUALS, "1")))

        assertEquals(Query.Group(all = true, items = listOf(or, rule)), or.and(rule))
        assertEquals(Query.Group(all = true, items = listOf(rule)), Query.EMPTY.and(rule))
    }

    @Test
    fun `a time range replaces the comparisons on its field at the top`() {
        val from = "2026-10-09 08:00:00.000"
        val to = "2026-10-09 09:00:00.000"
        val range = """time >= "$from" AND time < "$to""""
        for ((current, expected) in listOf(
            "" to range,
            """time > "2026-10-01" AND level >= WARN AND TIME <= "2026-10-02"""" to "level >= WARN AND $range",
            """NOT time >= "2026-10-01" AND other >= 5""" to """NOT time >= "2026-10-01" AND other >= 5 AND $range""",
            """time == "*08:*"""" to """time == "*08:*" AND $range""",
            """x == 1 AND (time >= "2026-10-01" OR y == 2)""" to
                """x == 1 AND (time >= "2026-10-01" OR y == 2) AND $range""",
            """time >= "2026-10-01" OR x == 1""" to """(time >= "2026-10-01" OR x == 1) AND $range""",
        )) {
            assertEquals(expected, QueryPrinter.print(parse(current).withTimeRange("time", from, to)), current)
        }
    }

    @Test
    fun `clearing a time range removes the comparisons on its field at the top`() {
        val current = parse("""time >= "2026-10-01" AND level >= WARN AND NOT time < "2026-10-02"""")

        val cleared = QueryPrinter.print(current.withoutTimeRange("time"))
        assertEquals("""level >= WARN AND NOT time < "2026-10-02"""", cleared)
        assertEquals(Query.EMPTY, Query.EMPTY.withoutTimeRange("time"))
    }

    @Test
    fun `the filter has a time range with a comparison on the field at its top`() {
        for ((current, expected) in listOf(
            "" to false,
            """Time < "2026-10-01"""" to true,
            """level >= WARN AND time >= "2026-10-01"""" to true,
            """NOT time >= "2026-10-01"""" to false,
            """time == "2026-10-01"""" to false,
            """other >= 5""" to false,
            """time >= "2026-10-01" OR x == 1""" to false,
            """NOT (time >= "2026-10-01" AND x == 1)""" to false,
        )) {
            assertEquals(expected, parse(current).hasTimeRange("time"), current)
        }
    }
}
