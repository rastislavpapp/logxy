package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import java.io.Closeable
import java.nio.file.Path

/** Bytes appended to a file, read by [LogData.readAppended] and applied with [LogData.append]. */
interface AppendedData

/**
 * An indexed log file as rows ("records") of fields described by [schema]; the common view of raw
 * and parsed logs.
 *
 * Not thread-safe: [append] and all readers must run on one thread; only [readAppended] may run
 * on another, but not during [append]. Filters read a snapshot on other threads, see
 * [io.github.rastislavpapp.logxy.core.filter.FilterEngine].
 */
abstract class LogData : Closeable {
    abstract val path: Path
    abstract val schema: Schema
    abstract val recordCount: Int

    /** Number of bytes of the file covered. */
    abstract val indexedSize: Long

    /** Raw text: one record per line, the line its only field, no levels. */
    abstract val isRaw: Boolean

    /** How level texts map to [Level]; may change while the log is open. */
    abstract var levelMapping: LevelMapping

    /** Canonical level of [record], or null if the log has no level or the record lacks one. */
    abstract fun level(record: Int): Level?

    /** Display text of every field of [record], each cut to its first line and at most [maxBytes]. */
    abstract fun rowValues(record: Int, maxBytes: Int): Array<String>

    /** The whole record as it appears in the file, cut at [maxBytes]. */
    abstract fun recordText(record: Int, maxBytes: Int): String

    /** Reads bytes between [indexedSize] and [size] without changing this log. */
    abstract fun readAppended(size: Long): AppendedData

    abstract fun append(data: AppendedData)

    /** The first [recordCount] records, for reading on other threads. Call on the thread that owns the log. */
    internal abstract fun filterSnapshot(): RecordSnapshot
}
