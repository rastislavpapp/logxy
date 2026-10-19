package io.github.rastislavpapp.logxy.core.query

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class HighlighterTest {

    private fun rule(op: QueryOp, value: String, field: String = "message", negated: Boolean = false) =
        Query.Rule(field, op, value, negated = negated)

    private fun of(vararg items: Query) = Highlighter.of(Query.Group(all = true, items = items.toList()))

    @Test
    fun marksSearchedTextInAnyField() {
        val h = Highlighter.of(TextSearch("a*b"))
        assertContentEquals(intArrayOf(1, 4, 5, 8), h.ranges("thread", "xA*bya*b ab"))
        assertContentEquals(intArrayOf(), Highlighter.of(TextSearch("(", regex = true)).ranges(null, "("))
    }

    @Test
    fun marksWildcardLiteralsIgnoringCase() {
        val h = of(rule(QueryOp.EQUALS, "*Timeout*"))
        assertContentEquals(intArrayOf(8, 15, 17, 24), h.ranges("message", "failed: timeout, TIMEOUT"))
    }

    @Test
    fun marksRegexMatches() {
        val h = of(rule(QueryOp.MATCHES, "\\d+ ms"))
        assertContentEquals(intArrayOf(6, 13), h.ranges("message", "after 2905 ms"))
    }

    @Test
    fun onlyTheRulesField() {
        val h = of(rule(QueryOp.EQUALS, "*a*", field = "thread"))
        assertContentEquals(intArrayOf(), h.ranges("message", "a"))
        assertContentEquals(intArrayOf(0, 1), h.ranges("thread", "a"))
        assertContentEquals(intArrayOf(0, 1), h.ranges(null, "a"))
    }

    @Test
    fun exactValuesMarkWholeWordsOnly() {
        val h = of(
            rule(QueryOp.EQUALS, "main", field = "thread"),
            rule(QueryOp.EQUALS, "com.example*", field = "logger"),
        )
        assertContentEquals(intArrayOf(0, 4, 20, 31), h.ranges(null, "main domain mainly [com.example.Order]"))
        assertContentEquals(intArrayOf(0, 4), h.ranges("thread", "main"))
    }

    @Test
    fun skipsNegatedAndInvalid() {
        val h = of(
            rule(QueryOp.NOT_EQUALS, "*a*"),
            rule(QueryOp.EQUALS, "*a*", negated = true),
            rule(QueryOp.MATCHES, "("),
        )
        assertTrue(h.isEmpty)
    }

    @Test
    fun skipsTemplates() {
        assertTrue(of(rule(QueryOp.EQUALS, "Request from {} took {} ms", field = "Template")).isEmpty)
    }

    @Test
    fun mergesOverlaps() {
        val h = of(rule(QueryOp.EQUALS, "*abc*"), rule(QueryOp.EQUALS, "*bcd*"))
        assertContentEquals(intArrayOf(0, 4), h.ranges("message", "abcd"))
    }

    @Test
    fun sortsMatchesOfSeveralRules() {
        val h = of(rule(QueryOp.EQUALS, "*y*"), rule(QueryOp.EQUALS, "*x*"))
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 6), h.ranges("message", "x y xy"))
    }

    @Test
    fun marksManyMatches() {
        val ranges = Highlighter.of(TextSearch("e")).ranges(null, "e.".repeat(500_000), budgetNanos = 10_000_000_000)
        assertContentEquals(IntArray(1_000_000) { it }, ranges)
    }

    @Test
    fun givesUpOnARegexThatBacktracksForLong() {
        val h = of(rule(QueryOp.EQUALS, "*start*"), rule(QueryOp.MATCHES, "(a+)+b"))
        val started = System.nanoTime()
        val ranges = h.ranges("message", "start " + "a".repeat(40), budgetNanos = 50_000_000)
        assertTrue(System.nanoTime() - started < 2_000_000_000)
        assertContentEquals(intArrayOf(0, 5), ranges)
    }
}
