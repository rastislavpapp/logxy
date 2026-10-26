package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CellRulesTest {

    private val message = Field("Message", ColumnType.SPAN, Role.MESSAGE)
    private val level = Field("Level", ColumnType.LEVEL, Role.LEVEL)
    private val time = Field("Time", ColumnType.TIMESTAMP, Role.TIMESTAMP)

    private fun printed(rules: List<Query.Rule>) = rules.map(QueryPrinter::print)

    @Test
    fun `a value becomes rules matching it as it is`() {
        val long = "x".repeat(CellRules.MAX_CHARS + 5)
        val cut = "x".repeat(CellRules.MAX_CHARS)
        for ((value, expected) in listOf(
            "Order 42 done" to """"Order 42 done"""",
            """Bad *glob? at C:\temp""" to """"Bad \*glob\? at C:\\temp"""",
            "Failed *x*\n\tat Foo.bar(Foo.java:1)" to """"Failed \*x\**"""",
            long to """"$cut*"""",
        )) {
            val rules = printed(CellRules.values(message, value, level = null))
            assertEquals(listOf("Message == $expected", "Message != $expected"), rules, value)
        }
    }

    @Test
    fun `an empty value has only the rule keeping records with a value`() {
        assertEquals(listOf("""Message != """""), printed(CellRules.values(message, "", level = null)))
    }

    @Test
    fun `a level cell gives bare level rules`() {
        assertEquals(listOf("Level == WARN", "Level != WARN"), printed(CellRules.values(level, "warning", Level.WARN)))
        assertEquals(
            listOf("Level >= WARN", "Level > WARN", "Level <= WARN", "Level < WARN"),
            printed(CellRules.levelRanges(level, Level.WARN)),
        )
    }

    @Test
    fun `an unknown level or a level outside the level field is matched as text`() {
        val unknown = printed(CellRules.values(level, "???", Level.UNKNOWN))
        assertEquals(listOf("""Level == "\?\?\?"""", """Level != "\?\?\?""""), unknown)
        assertEquals(emptyList(), CellRules.levelRanges(level, Level.UNKNOWN))
        val text = printed(CellRules.values(message, "WARN", Level.WARN))
        assertEquals(listOf("""Message == "WARN"""", """Message != "WARN""""), text)
        assertEquals(emptyList(), CellRules.levelRanges(message, Level.WARN))
    }

    @Test
    fun `a time cell gives comparisons with a time literal in the zone`() {
        // 2026-10-09 06:00:00.250 UTC.
        val nanos = 1_791_525_600_250_000_000L
        val literal = """"2026-10-09 08:00:00.250""""

        assertEquals(
            listOf("Time >= $literal", "Time > $literal", "Time <= $literal", "Time < $literal"),
            printed(CellRules.timeRanges(time, nanos, ZoneOffset.ofHours(2))),
        )
        assertEquals(emptyList(), CellRules.timeRanges(time, null, ZoneOffset.UTC))
        assertEquals(emptyList(), CellRules.timeRanges(message, nanos, ZoneOffset.UTC))
    }
}
