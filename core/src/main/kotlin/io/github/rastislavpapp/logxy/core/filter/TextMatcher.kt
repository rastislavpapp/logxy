package io.github.rastislavpapp.logxy.core.filter

import java.util.regex.Pattern

/** Tests text against a [TextCondition], straight on UTF-8 bytes where possible. */
internal class TextMatcher(condition: TextCondition) {

    private val op = condition.op
    private val value = condition.value
    private val ignoreCase = condition.ignoreCase
    private val needle = value.toByteArray(Charsets.UTF_8)
    private val asciiNeedle = needle.all { it >= 0 }
    private val lowerNeedle = ByteArray(needle.size) { lower(needle[it]) }
    private val regex: Pattern? = if (op == TextOp.MATCHES) {
        Pattern.compile(value, if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
    } else {
        null
    }

    // Byte comparison is exact for case-sensitive tests and for ASCII needles ignoring ASCII case.
    private val bytewise = op != TextOp.MATCHES && (!ignoreCase || asciiNeedle)

    fun matches(text: String): Boolean = when (op) {
        TextOp.EQUALS -> text.equals(value, ignoreCase)
        TextOp.NOT_EQUALS -> !text.equals(value, ignoreCase)
        TextOp.CONTAINS -> text.contains(value, ignoreCase)
        TextOp.STARTS_WITH -> text.startsWith(value, ignoreCase)
        TextOp.MATCHES -> regex!!.matcher(text).find()
    }

    /** What an absent value counts as. */
    val matchesAbsent get() = op == TextOp.NOT_EQUALS

    fun matches(bytes: ByteArray, from: Int, to: Int): Boolean {
        if (!bytewise) return matches(String(bytes, from, to - from, Charsets.UTF_8))
        return when (op) {
            TextOp.EQUALS -> to - from == needle.size && regionMatches(bytes, from)
            TextOp.NOT_EQUALS -> !(to - from == needle.size && regionMatches(bytes, from))
            TextOp.STARTS_WITH -> to - from >= needle.size && regionMatches(bytes, from)
            TextOp.CONTAINS -> contains(bytes, from, to)
            TextOp.MATCHES -> error("not bytewise")
        }
    }

    private fun contains(bytes: ByteArray, from: Int, to: Int): Boolean {
        if (needle.isEmpty()) return true
        val first = lowerNeedle[0]
        for (i in from..to - needle.size) {
            val b = if (ignoreCase) lower(bytes[i]) else bytes[i]
            if (b == (if (ignoreCase) first else needle[0]) && regionMatches(bytes, i)) return true
        }
        return false
    }

    private fun regionMatches(bytes: ByteArray, at: Int): Boolean {
        if (ignoreCase) {
            for (i in needle.indices) if (lower(bytes[at + i]) != lowerNeedle[i]) return false
        } else {
            for (i in needle.indices) if (bytes[at + i] != needle[i]) return false
        }
        return true
    }

    private fun lower(b: Byte): Byte = if (b in 'A'.code.toByte()..'Z'.code.toByte()) (b + 32).toByte() else b
}
