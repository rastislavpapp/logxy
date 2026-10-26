package io.github.rastislavpapp.logxy.core.query

/** Regex lookbehind: no letter, digit or `_` right before. */
internal const val NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}_])"

/** Regex lookahead: no letter, digit or `_` right after. */
internal const val NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}_])"

/** Whether [c] is part of a word, as [NOT_AFTER_WORD] and [NOT_BEFORE_WORD] see it. */
internal fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
