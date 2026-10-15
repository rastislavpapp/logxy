package io.github.rastislavpapp.logxy.core.query

import io.github.rastislavpapp.logxy.core.filter.RecordTextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Text to find in records, as in a text editor's find bar: literal text unless [regex]. */
data class TextSearch(
    val text: String,
    val matchCase: Boolean = false,
    val wholeWords: Boolean = false,
    val regex: Boolean = false,
) {
    val isEmpty get() = text.isEmpty()

    /**
     * Whether this search can only match records that [previous] matches, so it may look among those alone: the
     * same plain text with more typed after it. A regex or whole words can match elsewhere for more text.
     */
    fun narrows(previous: TextSearch): Boolean =
        !regex && !wholeWords && !previous.regex && !previous.wholeWords && matchCase == previous.matchCase &&
            previous.text.isNotEmpty() && text.length > previous.text.length && text.startsWith(previous.text)

    /** The condition on the records' whole text; throws [QueryException] for an invalid regex. */
    fun condition(): RecordTextCondition {
        // Plain text is matched straight on the bytes, as a wildcard pattern with its own wildcards escaped.
        if (!regex && !wholeWords) {
            val escaped = text.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
            return RecordTextCondition(TextOp.EQUALS, "*$escaped*", ignoreCase = !matchCase)
        }
        return RecordTextCondition(TextOp.MATCHES, pattern().pattern(), ignoreCase = !matchCase)
    }

    /** What [condition] looks for, as a regex; throws [QueryException] if [text] is an invalid one. */
    fun pattern(): Pattern {
        var body = if (regex) text else Pattern.quote(text)
        if (wholeWords) {
            // Literal text only needs a word boundary where it starts or ends with a word character.
            val start = if (regex || isWordChar(text.first())) NOT_AFTER_WORD else ""
            val end = if (regex || isWordChar(text.last())) NOT_BEFORE_WORD else ""
            body = "$start(?:$body)$end"
        }
        val flags = if (matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return try {
            Pattern.compile(body, flags)
        } catch (e: PatternSyntaxException) {
            throw QueryException("Invalid regex: ${e.description}")
        }
    }

    private companion object {
        const val NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}_])"
        const val NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}_])"

        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
    }
}
