package io.github.rastislavpapp.logxy.core.log

import java.io.Closeable
import java.nio.file.Path

/** Bytes appended to a file, read by [LogData.readAppended] and applied with [LogData.append]. */
interface AppendedData

/**
 * An indexed log file as rows ("records") of fields described by [schema]; the common view of raw
 * and parsed logs.
 *
 * Not thread-safe: [append] and all readers must run on one thread; only [readAppended] may run
 * on another, but not during [append].
 */
interface LogData : Closeable {
    val path: Path
    val schema: Schema
    val recordCount: Int

    /** Number of bytes of the file covered. */
    val indexedSize: Long

    /** Display text of every field of [record], each cut to its first line and at most [maxBytes]. */
    fun rowValues(record: Int, maxBytes: Int): Array<String>

    /** The whole record as it appears in the file, cut at [maxBytes]. */
    fun recordText(record: Int, maxBytes: Int): String

    /** Reads bytes between [indexedSize] and [size] without changing this log. */
    fun readAppended(size: Long): AppendedData

    fun append(data: AppendedData)
}
