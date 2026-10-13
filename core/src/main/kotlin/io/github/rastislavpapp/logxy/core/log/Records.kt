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
    fun timestamp(record: Int): Long? = timestampNanos(record).takeIf { it != NO_TIMESTAMP }

    /** Like [timestamp], but [NO_TIMESTAMP] if there is none; no boxing, for passes over many records. */
    fun timestampNanos(record: Int): Long

    /** Display text of every field of [record], each cut to its first line and at most [maxBytes]. */
    fun rowValues(record: Int, maxBytes: Int): Array<String>

    /** The whole value of [field] in [record]: all its lines, not cut. */
    fun fieldText(record: Int, field: Int): String

    /** The whole record as it appears in the file, cut at [maxBytes]. */
    fun recordText(record: Int, maxBytes: Int): String

    /** The bytes of [record] exactly as in the file, its line ends included, decoded as UTF-8. */
    fun sourceText(record: Int): String

    companion object {
        const val NO_TIMESTAMP = Columns.ABSENT_TIMESTAMP
    }
}
