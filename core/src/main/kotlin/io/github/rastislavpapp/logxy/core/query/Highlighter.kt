package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.filter.Glob
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Finds the text a query's positive text conditions matched, to mark it in the table and the detail pane. */
class Highlighter private constructor(private val terms: List<Term>) {

    /** A [field] of null marks text in any field. */
    private class Term(val field: String?, val pattern: Pattern)

    val isEmpty get() = terms.isEmpty()

    /**
     * Sorted, non-overlapping `[start, end)` pairs, flattened, of matches in [text]; of rules on [field], or of
     * all rules when [field] is null. Wildcard rules mark each literal part between the `*`s. Gives up after
     * [budgetNanos] with the matches found so far, as a user's regex can backtrack for minutes.
     */
    fun ranges(field: String?, text: String, budgetNanos: Long = CELL_BUDGET_NANOS): IntArray {
        if (terms.isEmpty() || text.isEmpty()) return EMPTY
        val timed = TimedText(text, System.nanoTime() + budgetNanos)
        // Each match packed as start << 32 | end, so sorting them sorts by start.
        var found = LongArray(16)
        var count = 0
        var sorted = true
        try {
            for (term in terms) {
                if (field != null && term.field != null && !term.field.equals(field, ignoreCase = true)) continue
                val matcher = term.pattern.matcher(timed)
                while (matcher.find()) {
                    if (matcher.end() == matcher.start()) continue
                    val match = matcher.start().toLong() shl 32 or matcher.end().toLong()
                    if (count == found.size) found = found.copyOf(count * 2)
                    if (count > 0 && match < found[count - 1]) sorted = false
                    found[count++] = match
                }
            }
        } catch (_: TimeOut) {
        }
        if (count == 0) return EMPTY
        if (!sorted) found.sort(0, count)
        return merge(found, count)
    }

    private fun merge(found: LongArray, count: Int): IntArray {
        val result = IntArray(count * 2)
        var size = 0
        for (i in 0..<count) {
            val start = (found[i] ushr 32).toInt()
            val end = found[i].toInt()
            if (size > 0 && start <= result[size - 1]) {
                result[size - 1] = maxOf(result[size - 1], end)
            } else {
                result[size++] = start
                result[size++] = end
            }
        }
        return result.copyOf(size)
    }

    /** [text] for a matcher, stopping it once [deadline] passes. */
    private class TimedText(private val text: String, private val deadline: Long) : CharSequence {
        private var reads = 0
        override val length get() = text.length

        override fun get(index: Int): Char {
            // The clock costs more than a read: check it now and then.
            if (++reads and 0xFFF == 0 && System.nanoTime() > deadline) throw TimeOut
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int) = text.subSequence(startIndex, endIndex)
        override fun toString() = text
    }

    private object TimeOut : RuntimeException(null, null, false, false)

    companion object {
        /** Time a table cell may take: many are painted at once. */
        const val CELL_BUDGET_NANOS = 5_000_000L

        /** Time the detail pane's whole record may take. */
        const val DETAIL_BUDGET_NANOS = 50_000_000L

        private val EMPTY = IntArray(0)
        private const val NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}_])"
        private const val NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}_])"
        val NONE = Highlighter(emptyList())

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

        /** Marks what [search] finds, in any field; nothing for an empty or invalid one. */
        fun of(search: TextSearch): Highlighter {
            if (search.isEmpty) return NONE
            val pattern = try {
                search.pattern()
            } catch (e: QueryException) {
                return NONE
            }
            return Highlighter(listOf(Term(null, pattern)))
        }

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
                is Query.Rule -> {
                    // A template is no text to find.
                    if (query.value.isEmpty() || query.field.equals(Query.PATTERN_FIELD, ignoreCase = true)) return
                    pattern(query)?.let { into += Term(query.field, it) }
                }
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
