package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.log.AppendedData
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.pattern.PatternIndex
import io.github.rastislavpapp.logxy.core.pattern.RowOrigin

/** What appending to the files of a load did to its records. */
internal enum class Appended {
    GREW,

    /** Grew, and the schema shows a field no record had before. */
    NEW_FIELDS,

    /** A file's last record was parsed differently, so the records must be built again. */
    MERGE_AGAIN,
}

/**
 * The records of one load, of a single file or merged from several: what the session does differently
 * for the two. Use on the thread that owns the files.
 */
internal interface LoadedLog {
    /** The records shown: the single file's log itself, or the merge. */
    val records: Records

    /** The files' logs, in the session's order. */
    val parts: List<LogData>

    /** The part and its record for each record of [records]; null for a single file, where both are the record. */
    val origin: RowOrigin?

    /** A new filter for [records]. */
    fun newFilter(): RecordFilter

    /** The first record of [part] at [record] or later; the part's record count if none. */
    fun firstRecordFrom(part: Int, record: Int): Int

    /** The template of [record]'s message; empty if it has none. */
    fun templateOf(record: Int): String

    /**
     * Reads [field] of the [count] records in [records] (null: the first [count]) by their index there,
     * on any thread. Call on the owner thread.
     */
    fun fieldReader(field: Int, records: IntArray?, count: Int): (Int) -> String

    /** Applies [data] appended to [part]; returns the record that may have more text now, -1 if none. */
    fun append(part: Int, data: AppendedData): Int

    /** Shows what the parts got by [append] since the last time. */
    suspend fun settle(): Appended
}

/** The records of [parts]: the single one's, or [parts] merged and named by [names]. */
internal suspend fun loadedLog(parts: List<LogData>, names: List<String>): LoadedLog =
    if (parts.size == 1) SingleLoad(parts.single()) else MergedLoad(MergedLog.build(parts, names))

internal class SingleLoad(private val log: LogData) : LoadedLog {
    private var schema: Schema = log.schema

    override val records: Records get() = log
    override val parts = listOf(log)
    override val origin: RowOrigin? get() = null

    override fun newFilter(): RecordFilter = FilterEngine(log)

    override fun firstRecordFrom(part: Int, record: Int) = record

    override fun templateOf(record: Int) = PatternIndex.templateOf(log.filterSnapshot(), record)

    override fun fieldReader(field: Int, records: IntArray?, count: Int): (Int) -> String {
        val snapshot = log.filterSnapshot()
        return if (records == null) { i -> snapshot.fieldText(i, field) } else { i -> snapshot.fieldText(records[i], field) }
    }

    override fun append(part: Int, data: AppendedData): Int {
        val changed = log.recordCount - 1
        log.append(data)
        return changed
    }

    override suspend fun settle(): Appended {
        val old = schema
        schema = log.schema
        return if (schema !== old) Appended.NEW_FIELDS else Appended.GREW
    }
}

internal class MergedLoad(private val log: MergedLog) : LoadedLog {
    private var schema: Schema = log.schema

    override val records: Records get() = log
    override val parts get() = log.parts

    override val origin: RowOrigin = object : RowOrigin {
        override fun part(row: Int) = log.part(row)
        override fun record(row: Int) = log.record(row)
    }

    override fun newFilter(): RecordFilter = MergedFilter(log)

    override fun firstRecordFrom(part: Int, record: Int) = log.firstRecordFrom(part, record)

    override fun templateOf(record: Int) =
        PatternIndex.templateOf(log.parts[log.part(record)].filterSnapshot(), log.record(record))

    override fun fieldReader(field: Int, records: IntArray?, count: Int) = log.fieldReader(field, records, count)

    override fun append(part: Int, data: AppendedData): Int {
        val merged = log.mergedCount(part)
        val changed = if (merged > 0) log.position(part, merged - 1) else -1
        log.parts[part].append(data)
        return changed
    }

    override suspend fun settle(): Appended {
        if (!log.appendNew()) return Appended.MERGE_AGAIN
        val old = schema
        schema = log.schema
        return if (schema !== old) Appended.NEW_FIELDS else Appended.GREW
    }
}
