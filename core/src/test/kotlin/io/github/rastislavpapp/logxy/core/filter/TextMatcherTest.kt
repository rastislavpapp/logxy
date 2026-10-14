package io.github.rastislavpapp.logxy.core.filter

import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMatcherTest {

    private val random = Random(15)

    /** Text pieces that regex literals and records share, including those Unicode case folds onto ASCII. */
    private val words = listOf(
        "time", "out", "TIME", "Out", "ask", "ſ", "K", "İ", "ı", "žlu", "ť", "a.b", "-", " ", "42", "x",
    )

    private val atoms = listOf(
        "\\d", ".", "[a-k]", "[^\\]x]", "\\w+", "\\Qa.s\\E", "\\.", "\\t", "\\x41", "\\u017F", "\\b", "^", "$",
    )

    private fun regex(depth: Int = 0): String = buildString {
        repeat(1 + random.nextInt(5)) {
            val roll = random.nextInt(20)
            when {
                roll < 9 -> append(words.random(random).replace(".", "\\."))
                roll < 12 -> append(atoms.random(random))
                roll < 14 && depth < 2 -> {
                    val open = listOf("(", "(?:", "(?i:", "(?-i:", "(?=", "(?<!", "(?>", "(?<g>").random(random)
                    append(open).append(regex(depth + 1)).append(')')
                }
                roll < 15 -> append(listOf("(?i)", "(?-i)").random(random))
                roll < 16 -> append('|')
                else -> append(listOf("time", "OUT", "sk", "ık").random(random))
            }
            if (random.nextInt(4) == 0 && last() != '|' && last() != '(' && !endsWith("i)")) {
                append(listOf("?", "*", "+", "{0,2}", "{2}", "{1,}", "+?", "*+").random(random))
            }
        }
    }

    /** UTF-8 text from [words], with malformed bytes mixed in. */
    private fun record(): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(random.nextInt(12)) {
            when (random.nextInt(10)) {
                0 -> out.write(listOf(0xFF, 0xC3, 0x80, 0xE2).random(random))
                else -> out.write(words.random(random).toByteArray(Charsets.UTF_8))
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `byte matching of regexes agrees with matching the decoded text`() {
        val records = List(300) { record() }
        var withRequiredText = 0
        repeat(2000) {
            val value = regex()
            for (ignoreCase in listOf(false, true)) {
                val expected = try {
                    val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
                    Pattern.compile(value, flags)
                } catch (_: PatternSyntaxException) {
                    continue
                }
                if (RequiredText.of(value, ignoreCase) != null) withRequiredText++
                for (op in listOf(TextOp.MATCHES, TextOp.NOT_MATCHES)) {
                    val matcher = TextMatcher(TextCondition("Message", op, value, ignoreCase))
                    // A margin around the record checks that the byte search keeps to its range.
                    for (record in records) {
                        val padded = "time".toByteArray() + record + "out".toByteArray()
                        val found = expected.matcher(String(record, Charsets.UTF_8)).find()
                        val actual = matcher.matches(padded, 4, 4 + record.size)
                        val case = "$op /$value/ ignoreCase=$ignoreCase on ${record.contentToString()}"
                        assertEquals(found != (op == TextOp.NOT_MATCHES), actual, case)
                    }
                }
            }
        }
        assertTrue(withRequiredText > 500, "only $withRequiredText regexes had required text")
    }
}
