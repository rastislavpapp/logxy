package io.github.rastislavpapp.logxy.core.pattern

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
        assertEquals("Payload: {\"orderId\":{},\"sku\":\"{}\"}", mask("Payload: {\"orderId\":709491,\"sku\":\"SKU-64198\"}"))
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
