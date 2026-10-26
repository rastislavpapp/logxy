package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.filter.Glob
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Finds the text a query's positive text conditions matched, to mark it in the table and the detail pane. */
class Marker private constructor(private val terms: List<Term>) {

    /** A [field] of null marks text in any field. */
    private class Term(val field: String?, val pattern: Pattern)

    val isEmpty get() = terms.isEmpty()

    /**
     * The parts of [text] that the rules on [field] match, or that all rules match when [field] is null. They come
     * as sorted, non-overlapping `[start, end)` pairs flattened into one array, e.g. `[0, 4, 10, 12]` for two
     * matches. Wildcard rules mark each literal part between the `*`s. Gives up after [budgetNanos] with the
     * matches found so far, as a user's regex can backtrack for minutes.
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
        val NONE = Marker(emptyList())

        /** Marks what [search] finds, in any field; nothing for an empty or invalid one. */
        fun of(search: TextSearch): Marker {
            if (search.isEmpty) return NONE
            val pattern = try {
                search.pattern()
            } catch (e: QueryException) {
                return NONE
            }
            return Marker(listOf(Term(null, pattern)))
        }

        /**
         * Marks what the text rules of [query] match. Negated rules and groups, negated ops, comparisons, empty
         * values and invalid regexes mark nothing.
         */
        fun of(query: Query): Marker {
            val terms = ArrayList<Term>()
            collect(query, terms)
            return if (terms.isEmpty()) NONE else Marker(terms)
        }

        private fun collect(query: Query, into: MutableList<Term>) {
            if (query.negated) return
            when (query) {
                is Query.Group -> query.items.forEach { collect(it, into) }
                is Query.Rule -> {
                    // A template is no text to find, and a variable `{n}` no field it could be marked in.
                    if (query.value.isEmpty() || query.field.equals(Query.TEMPLATE_FIELD, ignoreCase = true) ||
                        query.field.startsWith("{")
                    ) {
                        return
                    }
                    pattern(query)?.let { into += Term(query.field, it) }
                }
            }
        }

        private fun pattern(rule: Query.Rule): Pattern? {
            val ignoreCase = rule.op == QueryOp.EQUALS || rule.op == QueryOp.MATCHES
            val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            return try {
                when (rule.op) {
                    QueryOp.EQUALS, QueryOp.EQUALS_CASE -> {
                        val glob = Glob.parse(rule.value)
                        val parts = glob.segments.mapIndexedNotNull { i, segment ->
                            if (segment.all { it == Glob.ANY_ONE }) return@mapIndexedNotNull null
                            val body = segment.split(Glob.ANY_ONE)
                                .joinToString(".") { if (it.isEmpty()) "" else Pattern.quote(it) }
                            // Without a `*` at that end, the value must meet the field's edge. In the detail pane's
                            // whole record it must at least meet a word's edge, so `thread == main` does not mark
                            // "domain".
                            val atStart = i == 0 && !glob.leadingStar && isWordChar(segment.first())
                            val atEnd = i == glob.segments.lastIndex && !glob.trailingStar && isWordChar(segment.last())
                            (if (atStart) NOT_AFTER_WORD else "") + body + (if (atEnd) NOT_BEFORE_WORD else "")
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
