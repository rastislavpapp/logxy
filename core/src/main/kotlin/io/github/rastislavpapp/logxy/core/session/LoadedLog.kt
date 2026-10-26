package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.log.AppendedData
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.template.RowOrigin
import io.github.rastislavpapp.logxy.core.template.TemplateIndex

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

    /** The record of [records] that is [part]'s [record]; -1 if that one is not merged yet. */
    fun recordOf(part: Int, record: Int): Int

    /** The template of [record]'s message; empty if it has none. */
    fun templateOf(record: Int): String

    /**
     * A reader of [field] in [count] records: given an index i, it returns the field's text in record
     * `records[i]`, or in record i if [records] is null. Created on the owner thread; the reader itself may run
     * on any thread.
     */
    fun fieldReader(field: Int, records: IntArray?, count: Int): (Int) -> String

    /** Reads the records there are now as written in the files. Created on the owner thread. */
    fun sourceReader(): SourceReader

    /** Applies [data] appended to [part]; returns the record that may have more text now, -1 if none. */
    fun append(part: Int, data: AppendedData): Int

    /** Takes in what the parts got by [append] since the last call, so that [records] has it. */
    suspend fun settle(): Appended
}

/**
 * Where records are written in the files, and their bytes there, for the records there were when it was created.
 * Usable on any thread, but by one at a time.
 */
internal interface SourceReader {
    /**
     * Calls [span] for each run of records [from] to [to] (exclusive) that follow each other in one file, in order:
     * the run's part, and where it starts and ends there, line ends included. Reads only the index.
     */
    fun spans(from: Int, to: Int, span: (part: Int, start: Long, end: Long) -> Unit)

    /** [length] bytes of [part]'s file from [position]; fewer if the file ends before. */
    fun read(part: Int, position: Long, length: Int): ByteArray
}

/** A [SourceReader] over the snapshots of the parts; [partOf] and [recordOf] map a record to its part and back. */
private class SnapshotSourceReader(
    private val snapshots: List<RecordSnapshot>,
    private val partOf: (Int) -> Int,
    private val recordOf: (Int) -> Int,
) : SourceReader {
    private val cursors = snapshots.map { it.cursor() }

    override fun spans(from: Int, to: Int, span: (part: Int, start: Long, end: Long) -> Unit) {
        var first = from
        while (first < to) {
            val part = partOf(first)
            // A single file's records all follow each other: one run, without looking at each.
            var last = if (snapshots.size == 1) to - 1 else first
            while (last + 1 < to && partOf(last + 1) == part && recordOf(last + 1) == recordOf(last) + 1) last++
            val cursor = cursors[part]
            cursor.moveTo(recordOf(first))
            val start = cursor.start
            cursor.moveTo(recordOf(last))
            span(part, start, cursor.end)
            first = last + 1
        }
    }

    override fun read(part: Int, position: Long, length: Int) = snapshots[part].read(position, length)
}

/** The records of [parts]: the single one's, or [parts] merged and named by [names]. */
internal suspend fun loadedLog(parts: List<LogData>, names: List<String>): LoadedLog =
    if (parts.size == 1) SingleLoad(parts.single()) else MergedLoad(MergedLog.build(parts, names))

/** A load of one file: its records are the log's own, and a row's origin is the record itself. */
internal class SingleLoad(private val log: LogData) : LoadedLog {
    private var schema: Schema = log.schema

    override val records: Records get() = log
    override val parts = listOf(log)
    override val origin: RowOrigin? get() = null

    override fun newFilter(): RecordFilter = FilterEngine(log)

    override fun firstRecordFrom(part: Int, record: Int) = record

    override fun recordOf(part: Int, record: Int) = record

    override fun templateOf(record: Int) = TemplateIndex.templateOf(log.filterSnapshot(), record)

    override fun fieldReader(field: Int, records: IntArray?, count: Int): (Int) -> String {
        val snapshot = log.filterSnapshot()
        if (records == null) return { i -> snapshot.fieldText(i, field) }
        return { i -> snapshot.fieldText(records[i], field) }
    }

    override fun sourceReader(): SourceReader = SnapshotSourceReader(listOf(log.filterSnapshot()), { 0 }, { it })

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

/**
 * A load of several files merged by time; [settle] merges appended records in, or asks to merge again when they
 * no longer fit after the others.
 */
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

    override fun recordOf(part: Int, record: Int) =
        if (record < log.mergedCount(part)) log.position(part, record) else -1

    override fun templateOf(record: Int) =
        TemplateIndex.templateOf(log.parts[log.part(record)].filterSnapshot(), log.record(record))

    override fun fieldReader(field: Int, records: IntArray?, count: Int) = log.fieldReader(field, records, count)

    // The merge's lists of parts and records may be read on other threads below the count there was.
    override fun sourceReader(): SourceReader =
        SnapshotSourceReader(log.parts.map { it.filterSnapshot() }, log::part, log::record)

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
