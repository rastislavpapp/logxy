package io.github.rastislavpapp.logxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BytesTest {

    private val text = "a - b - c".toByteArray()

    @Test
    fun `search within a range`() {
        val dash = " - ".toByteArray()
        assertEquals(1, Bytes.indexOf(text, dash, 0, text.size))
        assertEquals(5, Bytes.indexOf(text, dash, 2, text.size))
        assertEquals(-1, Bytes.indexOf(text, dash, 0, 3))
        assertEquals(5, Bytes.lastIndexOf(text, dash, 0, text.size))
        assertEquals(1, Bytes.lastIndexOf(text, dash, 0, 7))
        assertEquals(3, Bytes.indexOf(text, ByteArray(0), 3, 5))
        assertTrue(Bytes.regionMatches(text, 4, text.size, "b".toByteArray()))
        assertFalse(Bytes.regionMatches(text, 8, text.size, "c - ".toByteArray()))
        assertTrue(Bytes.startsWith(text, 4, text.size, "b -"))
        assertFalse(Bytes.startsWith(text, 4, 5, "b -"))
    }

    @Test
    fun `line ends are trimmed once`() {
        val bytes = "x\r\n\r\n".toByteArray()
        assertEquals(3, Bytes.trimLineEnd(bytes, 0, 5))
        assertEquals(1, Bytes.trimLineEnd(bytes, 0, 3))
        assertEquals(1, Bytes.trimLineEnd(bytes, 1, 1))
    }

    @Test
    fun `long text is cut on a character boundary`() {
        val bytes = "ab€cd".toByteArray()   // € is three bytes
        assertEquals("ab€cd", Bytes.utf8Text(bytes, 0, bytes.size, 7))
        assertEquals("ab…", Bytes.utf8Text(bytes, 0, bytes.size, 4))
        assertEquals("ab€…", Bytes.utf8Text(bytes, 0, bytes.size, 5))
        assertEquals("b€…", Bytes.utf8Text(bytes, 1, bytes.size, 4))
    }
}
