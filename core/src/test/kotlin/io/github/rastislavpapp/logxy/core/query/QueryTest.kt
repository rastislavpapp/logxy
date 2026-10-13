package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.NumberCondition
import io.github.rastislavpapp.logxy.core.filter.PatternCondition
import io.github.rastislavpapp.logxy.core.filter.PresenceCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.filter.TimeCondition
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.parse.PatternFormat
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryTest {

    private val schema = PatternFormat("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c %X{request id} - %m%n", ZoneOffset.UTC).schema
    private val compiler = QueryCompiler(schema, ZoneOffset.UTC)

    private fun roundTrip(text: String) = QueryPrinter.print(QueryParser.parse(text))

    @Test
    fun `AND binds tighter than OR`() {
        val query = QueryParser.parse("""level >= WARN AND thread == main OR message == "*x*"""")
        assertEquals(
            Query.Group(all = false, items = listOf(
                Query.Group(all = true, items = listOf(
                    Query.Rule("level", QueryOp.AT_LEAST, "WARN", bare = true),
                    Query.Rule("thread", QueryOp.EQUALS, "main", bare = true),
                )),
                Query.Rule("message", QueryOp.EQUALS, "*x*"),
            )),
            query,
        )
    }

    @Test
    fun `text form round trips`() {
        for (text in listOf(
            """level >= WARN AND (message == "*timeout*" OR NOT thread =~ "^worker\d")""",
            """NOT (logger === "com.example.*" OR `request id` !== "?") AND message !~ "say \"hi\""""",
            """timestamp >= "2026-10-09 08:00" AND timestamp < "2026-10-09 09:00"""",
            "",
        )) {
            assertEquals(text, roundTrip(text))
        }
        assertEquals("""thread == main AND LEVEL != ERROR""", roundTrip("thread==main and LEVEL!=ERROR"))
    }

    @Test
    fun `syntax errors point at the problem`() {
        fun error(text: String) = assertFailsWith<QueryException> { QueryParser.parse(text) }.let { Triple(it.message, it.offset, it.length) }
        assertEquals(Triple("Use == to compare", 7, 1), error("thread = main"))
        assertEquals(Triple("Missing closing quote", 11, 4), error("message == \"abc"))
        assertEquals(Triple("Missing “)” for this “(”", 0, 1), error("(level >= WARN"))
        assertEquals(Triple("Expected a value after ==, but the text ends", 10, 1), error("message =="))
        assertEquals(Triple("Expected AND or OR", 14, 6), error("level >= WARN thread == main"))
        assertEquals(Triple("Expected a field name, not AND", 0, 3), error("AND x == 1"))
    }

    @Test
    fun `compiles to engine conditions`() {
        fun compile(text: String) = compiler.compile(QueryParser.parse(text))
        assertEquals(
            Filter.And(listOf(LevelCondition("Level", setOf(Level.WARN, Level.ERROR, Level.FATAL)))),
            compile("LEVEL >= warn"),
        )
        assertEquals(Filter.And(listOf(Filter.Not(LevelCondition("Level", setOf(Level.DEBUG))))), compile("level != DEBUG"))
        assertEquals(Filter.And(listOf(TextCondition("Level", TextOp.EQUALS, "WARN*", ignoreCase = true))), compile("level == \"WARN*\""))
        assertEquals(Filter.And(listOf(TextCondition("Thread", TextOp.EQUALS, "Main", ignoreCase = false))), compile("thread === Main"))
        assertEquals(Filter.And(listOf(TextCondition("request id", TextOp.NOT_MATCHES, "^\\d+$", ignoreCase = true))), compile("`Request ID` !~ \"^\\d+$\""))
        assertEquals(
            Filter.And(listOf(TimeCondition("Timestamp", from = 1_791_533_400_000_000_000L + 1))),
            compile("timestamp > \"2026-10-09 08:10\""),
        )
        assertEquals(Filter.And(listOf(NumberCondition("request id", from = 500.0))), compile("`request id` >= 500"))
        assertEquals(Filter.And(listOf(NumberCondition("Thread", from = Math.nextUp(2.5)))), compile("thread > 2.5"))
        assertEquals(Filter.And(listOf(NumberCondition("Message", to = Math.nextDown(-1.0)))), compile("message < \"-1\""))
        assertEquals(Filter.And(emptyList()), compile(""))
        assertEquals(Filter.And(emptyList()), compile("message == \"\""))
        assertEquals(
            Filter.And(listOf(TextCondition("Thread", TextOp.EQUALS, "main", ignoreCase = true))),
            compile("thread == main AND (message == \"\" OR logger =~ \"\") AND NOT level >= \"\""),
        )
    }

    @Test
    fun `compile errors explain what is wrong`() {
        fun error(text: String) = assertFailsWith<QueryException> { compiler.compile(QueryParser.parse(text)) }.message!!
        assertEquals("Unknown field “thred”; this log has Timestamp, Thread, Level, Logger, request id, Message", error("thred == x"))
        assertEquals("Unknown level “WARNING”; use one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN", error("level >= WARNING"))
        assertEquals(">= on Thread compares numbers; “x” is no number", error("thread >= x"))
        assertEquals("Invalid time “yesterday”; write e.g. 2026-10-09 08:30:00.000, 2026-10-09 or 08:30", error("timestamp >= yesterday"))
        assertEquals("Invalid regex “(”: Unclosed group", error("message =~ \"(\""))
    }

    @Test
    fun `pattern compiles to a template condition on the message`() {
        fun compile(text: String, compiler: QueryCompiler = this.compiler) = compiler.compile(QueryParser.parse(text))
        val template = "Request from {} took {} ms"
        assertEquals(Filter.And(listOf(PatternCondition("Message", setOf(template)))), compile("pattern == \"$template\""))
        assertEquals(Filter.And(listOf(Filter.Not(PatternCondition("Message", setOf(template))))), compile("Pattern !== \"$template\""))
        assertEquals(Filter.And(emptyList()), compile("Pattern == \"\""))
        assertEquals("Pattern works with == and !=, not =~", assertFailsWith<QueryException> { compile("Pattern =~ x") }.message)

        val noMessage = QueryCompiler(Schema(listOf(Field("Level", ColumnType.LEVEL, Role.LEVEL))))
        assertEquals("Pattern needs a message, which this log does not have", assertFailsWith<QueryException> { compile("Pattern == x", noMessage) }.message)

        // A field of the log with that name is used as it is.
        val withField = QueryCompiler(PatternFormat("%d{yyyy-MM-dd HH:mm:ss.SSS} %X{Pattern} - %m%n", ZoneOffset.UTC).schema)
        assertEquals(Filter.And(listOf(TextCondition("Pattern", TextOp.EQUALS, "x", ignoreCase = true))), compile("pattern == x", withField))
    }

    @Test
    fun `an empty value is a placeholder, except for not equals`() {
        assertEquals(Filter.And(emptyList()), compiler.compile(QueryParser.parse("""thread == "" AND message =~ """"")))
        assertEquals(
            Filter.And(listOf(Filter.And(listOf(PresenceCondition("Thread"), TextCondition("Thread", TextOp.NOT_EQUALS, "", ignoreCase = true))))),
            compiler.compile(QueryParser.parse("""thread != """"")),
        )
    }

    @Test
    fun `time literals`() {
        assertEquals(1_791_504_000_000_000_000L, TimeLiteral.parse("2026-10-09", ZoneOffset.UTC))
        assertEquals(1_791_533_415_250_000_000L, TimeLiteral.parse("2026-10-09T08:10:15,25", ZoneOffset.UTC))
        assertEquals(29_400_000_000_000L, TimeLiteral.parse("08:10", ZoneOffset.UTC))
    }

    @Test
    fun `formatted time literals parse back`() {
        val zone = java.time.ZoneId.of("Europe/Bratislava")
        assertEquals("2026-10-09 10:10:15.250", TimeLiteral.format(1_791_533_415_250_000_000L, zone))
        for (nanos in listOf(1_791_533_415_250_000_000L, 1_791_533_415_000_000_123L, 29_400_000_000_000L)) {
            assertEquals(nanos, TimeLiteral.parse(TimeLiteral.format(nanos, zone), zone))
        }
    }
}
