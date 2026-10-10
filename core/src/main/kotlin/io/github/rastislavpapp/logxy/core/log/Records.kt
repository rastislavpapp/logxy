package io.github.rastislavpapp.logxy.core.log

/**
 * Records as a view shows them: rows of fields described by [schema], from one log or several merged.
 * Read on the thread that owns them.
 */
interface Records {
    val schema: Schema
    val recordCount: Int

    /** Raw text: one record per line, the line its only field, no levels. */
    val isRaw: Boolean

    /** Canonical level of [record], or null if there is no level or the record lacks one. */
    fun level(record: Int): Level?

    /** Epoch nanoseconds of [record]'s timestamp, or null if there is none. */
    fun timestamp(record: Int): Long?

    /** Display text of every field of [record], each cut to its first line and at most [maxBytes]. */
    fun rowValues(record: Int, maxBytes: Int): Array<String>

    /** The whole record as it appears in the file, cut at [maxBytes]. */
    fun recordText(record: Int, maxBytes: Int): String
}
