package io.github.rastislavpapp.logxy.core.filter

import io.github.rastislavpapp.logxy.core.Bytes
import java.util.regex.Pattern

/**
 * Tests text against a [TextCondition]. Wildcard patterns made of `*` and literal text (the usual
 * "contains", "starts with", "is") are matched straight on UTF-8 bytes; others go through a regex.
 */
internal class TextMatcher(condition: TextCondition) {

    private val negated = condition.op == TextOp.NOT_EQUALS || condition.op == TextOp.NOT_MATCHES
    private val ignoreCase = condition.ignoreCase
    private val glob = if (condition.op == TextOp.EQUALS || condition.op == TextOp.NOT_EQUALS) Glob.parse(condition.value) else null

    /** Literal segments between `*`s, for byte matching; null if the pattern needs the regex. */
    private val segments: List<ByteArray>? = glob
        ?.takeIf { g -> !g.hasAnyOne && (!ignoreCase || g.segments.all { s -> s.all { it.code < 128 } }) }
        ?.segments?.map { segment -> segment.toByteArray(Charsets.UTF_8).also { if (ignoreCase) lowerInPlace(it) } }

    private val regex: Pattern = if (glob != null) {
        Pattern.compile(glob.toRegex(), Pattern.DOTALL or caseFlags())
    } else {
        Pattern.compile(condition.value, caseFlags())
    }

    private fun caseFlags() = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0

    /** What an absent value counts as: only negated conditions accept it. */
    val matchesAbsent get() = negated

    /** Whether [text] (a value as shown, or a record's text as written) satisfies the condition: a wildcard pattern must match all of it, a regex any part. */
    fun matches(text: String): Boolean {
        val matcher = regex.matcher(text)
        return (if (glob != null) matcher.matches() else matcher.find()) != negated
    }

    /** As [matches] on the UTF-8 text `bytes[from, to)`, without decoding it when the pattern allows. */
    fun matches(bytes: ByteArray, from: Int, to: Int): Boolean {
        val segments = segments ?: return matches(String(bytes, from, to - from, Charsets.UTF_8))
        return globMatches(segments, bytes, from, to) != negated
    }

    private fun globMatches(segments: List<ByteArray>, bytes: ByteArray, from: Int, to: Int): Boolean {
        val glob = glob!!
        if (segments.size == 1 && !glob.leadingStar && !glob.trailingStar) {
            return to - from == segments[0].size && regionMatches(bytes, from, segments[0])
        }
        var position = from
        var end = to
        var first = 0
        var last = segments.lastIndex
        if (!glob.leadingStar) {
            if (to - from < segments[0].size || !regionMatches(bytes, from, segments[0])) return false
            position += segments[0].size
            first = 1
        }
        if (!glob.trailingStar) {
            val tail = segments[last]
            end = to - tail.size
            if (end < position || !regionMatches(bytes, end, tail)) return false
            last--
        }
        for (i in first..last) {
            val found = indexOf(bytes, segments[i], position, end)
            if (found < 0) return false
            position = found + segments[i].size
        }
        return true
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        if (!ignoreCase) return Bytes.indexOf(bytes, needle, from, to)
        if (needle.isEmpty()) return from
        for (i in from..to - needle.size) {
            if (regionMatches(bytes, i, needle)) return i
        }
        return -1
    }

    private fun regionMatches(bytes: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (ignoreCase) {
            for (i in needle.indices) if (lower(bytes[at + i]) != needle[i]) return false
        } else {
            for (i in needle.indices) if (bytes[at + i] != needle[i]) return false
        }
        return true
    }

    private fun lowerInPlace(bytes: ByteArray) {
        for (i in bytes.indices) bytes[i] = lower(bytes[i])
    }

    private fun lower(b: Byte): Byte = if (b in 'A'.code.toByte()..'Z'.code.toByte()) (b + 32).toByte() else b
}

/**
 * A wildcard pattern: `*` matches any run of characters (also line breaks), `?` exactly one;
 * a backslash makes the next `*`, `?` or backslash literal.
 */
internal class Glob private constructor(
    /** Text between `*`s, with [ANY_ONE] for `?`. */
    val segments: List<String>,
    val leadingStar: Boolean,
    val trailingStar: Boolean,
) {
    val hasAnyOne = segments.any { ANY_ONE in it }

    /** The same pattern as a regex that must match the whole text; literal text is quoted. */
    fun toRegex(): String = segments.joinToString(".*", prefix = if (leadingStar) ".*" else "", postfix = if (trailingStar) ".*" else "") { segment ->
        segment.split(ANY_ONE).joinToString(".") { if (it.isEmpty()) "" else Pattern.quote(it) }
    }

    companion object {
        /** Stands for `?` inside [segments]; a code point that does not occur in logs. */
        const val ANY_ONE = '￿'

        /** The wildcard pattern [pattern] as the user typed it; every string is a valid pattern. */
        fun parse(pattern: String): Glob {
            val segments = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            var leadingStar = false
            var trailingStar = false
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\\' && i + 1 < pattern.length && pattern[i + 1] in "*?\\" -> {
                        current.append(pattern[i + 1])
                        i += 2
                        continue
                    }
                    c == '*' -> {
                        if (segments.isEmpty() && current.isEmpty()) leadingStar = true else segments += current.toString()
                        current.clear()
                        trailingStar = true
                    }
                    c == '?' -> {
                        current.append(ANY_ONE)
                        trailingStar = false
                    }
                    else -> {
                        current.append(c)
                        trailingStar = false
                    }
                }
                i++
            }
            if (current.isNotEmpty() || !trailingStar) segments += current.toString()
            if (segments.isEmpty()) segments += ""
            return Glob(segments, leadingStar, trailingStar)
        }
    }
}
