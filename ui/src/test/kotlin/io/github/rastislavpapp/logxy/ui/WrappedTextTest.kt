package io.github.rastislavpapp.logxy.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class WrappedTextTest {

    private fun WrappedText.lines() = List(lineCount) { text.substring(start(it), end(it)) }

    @Test
    fun `short lines stay whole`() {
        val wrapped = WrappedText("first\nat a\nat b", width = 10, firstWidth = 10)
        assertEquals(listOf("first", "at a", "at b"), wrapped.lines())
    }

    @Test
    fun `long lines break at the width`() {
        val wrapped = WrappedText("abcdefghij\nklmnopq", width = 4, firstWidth = 4)
        assertEquals(listOf("abcd", "efgh", "ij", "klmn", "opq"), wrapped.lines())
    }

    @Test
    fun `the first line shown breaks at its own width`() {
        val wrapped = WrappedText("abcdefghij\nklmnop", width = 6, firstWidth = 4)
        assertEquals(listOf("abcd", "efghij", "klmnop"), wrapped.lines())
    }

    @Test
    fun `empty lines are lines`() {
        val wrapped = WrappedText("a\n\nb\n", width = 4, firstWidth = 4)
        assertEquals(listOf("a", "", "b", ""), wrapped.lines())
    }

    @Test
    fun `a text offset is on the line that shows it`() {
        val wrapped = WrappedText("abcdefghij\nklm", width = 4, firstWidth = 4)
        assertEquals(0, wrapped.lineOf(3))
        assertEquals(1, wrapped.lineOf(4))
        assertEquals(2, wrapped.lineOf(10))
        assertEquals(3, wrapped.lineOf(12))
    }
}
