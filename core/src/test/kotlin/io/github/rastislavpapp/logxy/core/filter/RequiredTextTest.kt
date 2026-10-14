package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.query.TextSearch
import kotlin.test.Test
import kotlin.test.assertEquals

class RequiredTextTest {

    /** The required text of [regex] and whether it is searched ignoring case, or null for none. */
    private fun literal(regex: String, ignoreCase: Boolean = false): Pair<String, Boolean>? =
        RequiredText.of(regex, ignoreCase)?.let { String(it.bytes, Charsets.UTF_8) to it.ignoreCase }

    @Test
    fun `literal runs end at classes, anchors and optional atoms`() {
        assertEquals("timeout after " to false, literal("timeout after \\d+ ms"))
        assertEquals("colo" to false, literal("colou?r"))
        assertEquals("bar" to false, literal("(foo)?bar"))
        assertEquals("yzw" to false, literal("x{0,2}yzw"))
        assertEquals("abcx" to false, literal("abcx{2}def"))
        assertEquals("abcx" to false, literal("abcx+def"))
        assertEquals("long" to false, literal("ab*?long"))
        assertEquals("tail" to false, literal("^.[a-z]\\w\\bfoo\\1tail$"))
        assertEquals("order " to false, literal("order \\d{6}:"))
        assertEquals("žluť" to false, literal("žluť"))
    }

    @Test
    fun `escapes and quoting are literal text`() {
        assertEquals("a.b" to false, literal("\\Qa.b\\E"))
        assertEquals("a.b" to false, literal("a\\.b"))
        assertEquals("a\tb" to false, literal("a\\tb"))
        assertEquals("ABC" to false, literal("\\x41\\u0042C"))
        assertEquals("abc" to false, literal("\\Qabcd\\E?"))
    }

    @Test
    fun `groups contribute their inner runs but end the run at their edges`() {
        assertEquals("inner" to false, literal("ab(inner)cd"))
        assertEquals("named" to false, literal("x(?<id>named)y"))
        assertEquals("deep" to false, literal("(?:a(deep)b)+"))
        assertEquals("rest" to false, literal("(?=lookahead)rest(?<!behind)(?>atomic)"))
        assertEquals("both" to false, literal("(slow|timeout)both"))
    }

    @Test
    fun `no literal when unsure`() {
        assertEquals(null, literal("a|b"))
        assertEquals(null, literal("slow|timeout"))
        assertEquals(null, literal("(?x) a b"))
        assertEquals(null, literal("(?u)abc"))
        assertEquals(null, literal("[]abc]"))
        assertEquals(null, literal("ab"))
        assertEquals(null, literal("x{0,2}yz"))
        assertEquals(null, literal("\\0101bcd"))
    }

    @Test
    fun `case-insensitive runs avoid letters that match non-ASCII text`() {
        assertEquals("meout" to true, literal("timeout", ignoreCase = true))
        assertEquals("meout after " to true, literal("TIMEOUT AFTER \\d+", ignoreCase = true))
        assertEquals("ABC" to false, literal("(?i)xy(?-i)ABC"))
        assertEquals("qwer" to true, literal("ab(?i)QWER"))
        assertEquals("abcd" to false, literal("(?i:x)abcd"))
        assertEquals(null, literal("žluťoučký", ignoreCase = true))
        assertEquals(null, literal("a�bc"))
    }

    @Test
    fun `whole-word search finds its text`() {
        val pattern = TextSearch("timeout", wholeWords = true, matchCase = true).pattern().pattern()
        assertEquals("timeout" to false, literal(pattern))
        val anyCase = TextSearch("timeout", wholeWords = true).pattern().pattern()
        assertEquals("meout" to true, literal(anyCase, ignoreCase = true))
    }
}
