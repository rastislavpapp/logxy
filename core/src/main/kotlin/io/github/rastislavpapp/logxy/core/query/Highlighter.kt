package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.filter.Glob
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Finds the text a query's positive text conditions matched, to mark it in the table and the detail pane. */
class Highlighter private constructor(private val terms: List<Term>) {

    private class Term(val field: String, val pattern: Pattern)

    val isEmpty get() = terms.isEmpty()

    /**
     * Sorted, non-overlapping `[start, end)` pairs, flattened, of matches in [text]; of rules on [field], or of
     * all rules when [field] is null. Wildcard rules mark each literal part between the `*`s.
     */
    fun ranges(field: String?, text: String): IntArray {
        if (terms.isEmpty() || text.isEmpty()) return EMPTY
        var found = IntArray(0)
        for (term in terms) {
            if (field != null && !term.field.equals(field, ignoreCase = true)) continue
            val matcher = term.pattern.matcher(text)
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) continue
                found += intArrayOf(matcher.start(), matcher.end())
            }
        }
        return merge(found)
    }

    private fun merge(found: IntArray): IntArray {
        if (found.size <= 2) return found
        val order = (0..<found.size / 2).sortedBy { found[it * 2] }
        val result = ArrayList<Int>()
        var start = found[order[0] * 2]
        var end = found[order[0] * 2 + 1]
        for (i in order.drop(1)) {
            if (found[i * 2] <= end) {
                end = maxOf(end, found[i * 2 + 1])
            } else {
                result += start
                result += end
                start = found[i * 2]
                end = found[i * 2 + 1]
            }
        }
        result += start
        result += end
        return result.toIntArray()
    }

    companion object {
        private val EMPTY = IntArray(0)
        private const val NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}_])"
        private const val NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}_])"
        val NONE = Highlighter(emptyList())

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

        /** Negated rules, groups and ops, comparisons and empty values mark nothing; neither do invalid regexes. */
        fun of(query: Query): Highlighter {
            val terms = ArrayList<Term>()
            collect(query, terms)
            return if (terms.isEmpty()) NONE else Highlighter(terms)
        }

        private fun collect(query: Query, into: MutableList<Term>) {
            if (query.negated) return
            when (query) {
                is Query.Group -> query.items.forEach { collect(it, into) }
                is Query.Rule -> if (query.value.isNotEmpty()) pattern(query)?.let { into += Term(query.field, it) }
            }
        }

        private fun pattern(rule: Query.Rule): Pattern? {
            val flags = if (rule.op == QueryOp.EQUALS || rule.op == QueryOp.MATCHES) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            return try {
                when (rule.op) {
                    QueryOp.EQUALS, QueryOp.EQUALS_CASE -> {
                        val glob = Glob.parse(rule.value)
                        val parts = glob.segments.mapIndexedNotNull { i, segment ->
                            if (segment.all { it == Glob.ANY_ONE }) return@mapIndexedNotNull null
                            val body = segment.split(Glob.ANY_ONE).joinToString(".") { if (it.isEmpty()) "" else Pattern.quote(it) }
                            // Without a `*` there, the value meets the field's edge; in the detail pane's whole record
                            // that is at least a word's, so `thread == main` does not mark "domain".
                            val start = if (i == 0 && !glob.leadingStar && isWordChar(segment.first())) NOT_AFTER_WORD else ""
                            val end = if (i == glob.segments.lastIndex && !glob.trailingStar && isWordChar(segment.last())) NOT_BEFORE_WORD else ""
                            start + body + end
                        }
                        if (parts.isEmpty()) null else Pattern.compile(parts.joinToString("|"), flags or Pattern.DOTALL)
                    }
                    QueryOp.MATCHES -> Pattern.compile(rule.value, flags)
                    else -> null
                }
            } catch (e: PatternSyntaxException) {
                null
            }
        }
    }
}
