package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatternFormatTest {

    private fun matches(pattern: String, line: String): Boolean {
        val bytes = line.toByteArray()
        return PatternFormat(pattern).newParser().match(bytes, 0, bytes.size)
    }

    @Test
    fun `schema follows the conversions`() {
        val schema = PatternFormat("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c{1} %X{user} - %m%n").schema
        assertEquals(listOf("Timestamp", "Thread", "Level", "Logger", "user", "Message"), schema.fields.map { it.name })
        assertEquals(
            listOf(ColumnType.TIMESTAMP, ColumnType.DICT, ColumnType.LEVEL, ColumnType.DICT, ColumnType.SPAN, ColumnType.SPAN),
            schema.fields.map { it.type },
        )
        assertEquals(5, schema.indexOf(Role.MESSAGE))
    }

    @Test
    fun `matches first lines but not stack trace lines`() {
        val pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n"
        assertTrue(matches(pattern, "2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started"))
        assertTrue(matches(pattern, "2026-10-01 08:00:00.001 [pool 1 thread] ERROR com.example.App - x - y"))
        assertFalse(matches(pattern, "\tat com.example.App.main(App.java:10)"))
        assertFalse(matches(pattern, "java.lang.IllegalStateException: boom"))
        assertFalse(matches(pattern, "2026-10-01 08:00:00.001 [main] INFO  no separator here"))
    }

    @Test
    fun `wrappers in log4j2 and logback syntax`() {
        assertTrue(matches("%d{HH:mm:ss.SSS} %highlight{%-5level} %logger - %msg%n", "12:00:00.000 WARN  a.B - hi"))
        assertTrue(matches("%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan(%logger) - %msg%n", "12:00:00.000 WARN  a.B - hi"))
    }

    @Test
    fun `invalid patterns are reported`() {
        assertFailsWith<IllegalArgumentException> { PatternFormat("%d %foo %m") }
        assertFailsWith<IllegalArgumentException> { PatternFormat("%t%c - %m") }
        assertFailsWith<IllegalArgumentException> { PatternFormat("%d{HH:mm %m") }
    }
}
