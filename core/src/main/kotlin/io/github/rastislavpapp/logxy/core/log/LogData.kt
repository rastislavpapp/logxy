package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import java.io.Closeable
import java.nio.file.Path

/** Bytes appended to a file, read by [LogData.readAppended] and applied with [LogData.append]. */
interface AppendedData

/**
 * An indexed log file as [Records]; the common view of raw and parsed logs.
 *
 * Not thread-safe: [append] and all readers must run on one thread; only [readAppended] may run
 * on another, but not during [append]. Filters read a snapshot on other threads, see
 * [io.github.rastislavpapp.logxy.core.filter.FilterEngine].
 */
abstract class LogData : Records, Closeable {
    abstract val path: Path

    /** Number of bytes of the file covered. */
    abstract val indexedSize: Long

    /** How level texts map to [Level]; may change while the log is open. */
    abstract var levelMapping: LevelMapping

    /** Reads bytes between [indexedSize] and [size] without changing this log. */
    abstract fun readAppended(size: Long): AppendedData

    /**
     * Adds what [readAppended] read, which must start where this log's indexed bytes end (or its unfinished
     * last line starts); records grow, are added, and the last may be replaced.
     */
    abstract fun append(data: AppendedData)

    /** The first [recordCount] records, for reading on other threads. Call on the thread that owns the log. */
    internal abstract fun filterSnapshot(): RecordSnapshot
}
