package io.github.rastislavpapp.logxy.core.template

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplatesTest {

    private fun mask(text: String) = Templates.mask(text)

    @Test
    fun `words with digits become placeholders`() {
        assertEquals(
            "Failed to process message {}: timeout after {} ms",
            mask("Failed to process message 8a05f3c2-1b7e-4c1d-9f00-2e7b1d3c4a5b: timeout after 2905 ms"),
        )
        assertEquals("Request from {} took {}", mask("Request from 10.0.3.17 took 12ms"))
        assertEquals("user=bob id={} path={}", mask("user=bob id=42 path=/api/v1/users/7"))
        assertEquals("Order {} ({} items) done.", mask("Order #1234 (3 items) done."))
        assertEquals("[{}] {}, {}", mask("[pool-1-thread-3] 0x1F, v2.1"))
        assertEquals(
            "Payload: {\"orderId\":{},\"sku\":\"{}\"}",
            mask("Payload: {\"orderId\":709491,\"sku\":\"SKU-64198\"}"),
        )
    }

    @Test
    fun `a variable's value is the number its word starts with, before a unit`() {
        fun variable(text: String, variable: Int) = Templates.variableNumber(text, variable)
        val text = "Request 7 took 12ms, 1.5s in total, +4 tries, delta -3, 100KB/s for id 42: done"
        assertEquals(7.0, variable(text, 0))
        assertEquals(12.0, variable(text, 1))
        assertEquals(1.5, variable(text, 2))
        assertEquals(4.0, variable(text, 3))
        assertEquals(-3.0, variable(text, 4))
        assertEquals(100.0, variable(text, 5))
        assertEquals(42.0, variable(text, 6))
        assertEquals(Double.NaN, variable(text, 7))
        assertEquals(709491.0, variable("Payload: {\"orderId\":709491,\"retries\":0}", 0))
        assertEquals(0.0, variable("Payload: {\"orderId\":709491,\"retries\":0}", 1))
    }

    @Test
    fun `reads several variables at once, in the order asked`() {
        val bytes = "Request 7 took 12ms from 10.0.0.1".toByteArray()
        val into = DoubleArray(5) { -1.0 }
        Templates.variableNumbers(
            bytes, 0, bytes.size, intArrayOf(1, 2, 0), into, 1, ByteArray(Templates.OUT_SIZE),
            IntArray(Templates.PLACES_SIZE),
        )
        assertEquals(listOf(-1.0, 12.0, Double.NaN, 7.0, -1.0), into.toList())
    }

    @Test
    fun `ids, addresses and dates have no value`() {
        fun variable(text: String) = Templates.variableNumber(text, 0)
        // A number followed by more digits is part of something else.
        assertEquals(Double.NaN, variable("from 10.0.3.17"))
        assertEquals(Double.NaN, variable("flags 0x1F"))
        assertEquals(Double.NaN, variable("commit 3fa9c1d2e5"))
        assertEquals(Double.NaN, variable("id 8a05f3c2-1b7e-4c1d-9f00-2e7b1d3c4a5b"))
        assertEquals(Double.NaN, variable("on 2026-10-01"))
        // Words that are variable but start with no number.
        assertEquals(Double.NaN, variable("Order #1234 sent"))
        assertEquals(Double.NaN, variable("[pool-1-thread-3]"))
        assertEquals(Double.NaN, variable("commit deadbeefcafe added"))
    }

    @Test
    fun `a literal placeholder in the message is a variable without a value`() {
        // An unformatted SLF4J message: its `{}` shows in the template like the variable after it.
        val text = "Value {} is bad: 17"
        assertEquals("Value {} is bad: {}", Templates.mask(text))
        assertEquals(Double.NaN, Templates.variableNumber(text, 0))
        assertEquals(17.0, Templates.variableNumber(text, 1))
    }

    @Test
    fun `finds the variable at a place in the text`() {
        val text = "Request §7 took 12ms: done"
        // `§` takes two bytes in UTF-8: places are characters, not bytes.
        assertEquals(-1, Templates.variableAt(text, 0))
        assertEquals(-1, Templates.variableAt(text, 7))
        assertEquals(0, Templates.variableAt(text, 8))
        assertEquals(0, Templates.variableAt(text, 9))
        assertEquals(1, Templates.variableAt(text, 16))
        assertEquals(1, Templates.variableAt(text, 19))
        assertEquals(-1, Templates.variableAt(text, 20))
        assertEquals("§7", Templates.variableWord(text, 0))
        assertEquals("12ms", Templates.variableWord(text, 1))
        assertEquals(null, Templates.variableWord(text, 2))
        assertEquals(null, Templates.variableWord("Value {} is 3", 0))
    }

    @Test
    fun `variables are counted in the template`() {
        assertEquals(0, Templates.variableCount("Cache warmed"))
        assertEquals(2, Templates.variableCount("Request from {} took {} ms"))
        assertEquals(1, Templates.variableCount("{{}}"))
    }

    @Test
    fun `long hex words are variable, short ones and real words are not`() {
        assertEquals("commit {} added to face", mask("commit deadbeefcafe added to face"))
        assertEquals("{}", mask("DEADBEEF"))
        assertEquals("feedface?", mask("feedface?"))
    }

    @Test
    fun `only the first line counts`() {
        assertEquals("Query failed", mask("Query failed\njava.sql.SQLException: boom\n\tat app.Db.run(Db.java:42)"))
        assertEquals("Windows line", mask("Windows line\r\nnext"))
    }

    @Test
    fun `other text stays as written`() {
        assertEquals("Žluťoučký kůň {} úpěl", mask("Žluťoučký kůň 3 úpěl"))
        assertEquals("Got {} and {}", mask("Got {} and 5"))
        assertEquals("", mask(""))
        assertEquals("Cache warmed", mask("Cache warmed"))
    }

    @Test
    fun `long lines are cut at a character boundary`() {
        val template = mask("é".repeat(Templates.MAX_BYTES))
        assertEquals("é".repeat(Templates.MAX_BYTES / 2), template)
        assertEquals("x".repeat(Templates.MAX_BYTES - 1), mask("x".repeat(Templates.MAX_BYTES - 1) + "é tail"))
    }
}
