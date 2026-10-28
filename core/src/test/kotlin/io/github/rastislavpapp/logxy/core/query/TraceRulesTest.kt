package io.github.rastislavpapp.logxy.core.query

import kotlin.test.Test
import kotlin.test.assertEquals

class TraceRulesTest {

    private fun showOnly(vararg ids: String) = QueryPrinter.print(TraceRules.showOnly("requestId", ids.toList()))

    @Test
    fun `one trace is shown by its trace id, more by any of theirs`() {
        assertEquals("requestId == \"r1\"", showOnly("r1"))
        assertEquals("requestId == \"r1\" OR requestId == \"r2\"", showOnly("r1", "r2"))
    }

    @Test
    fun `a trace id is matched as it is, not as a wildcard pattern`() {
        val rule = Query.Rule("requestId", QueryOp.EQUALS, """a\*b\?""")
        assertEquals(Query.Group(all = true, items = listOf(rule)), TraceRules.showOnly("requestId", listOf("a*b?")))
    }
}
