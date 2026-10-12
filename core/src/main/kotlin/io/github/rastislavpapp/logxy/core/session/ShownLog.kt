package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** What [ShownLog.copyColumn] got. */
sealed interface CopyResult {
    data class Copied(val text: String) : CopyResult

    /** The session reloaded or closed the files while copying. */
    data object FileChanged : CopyResult

    data class Failed(val message: String) : CopyResult
}

/**
 * The records of one load of a [LogSession] as the user sees them: all of them, or a filter's result.
 * A row is a record's position among those shown. The session changes it as records are appended
 * and filtered; use it on the session's thread. Once the session reloads or closes, it is [ended]:
 * it shows no rows and its reads return empty (see docs/adr/0001).
 */
class ShownLog internal constructor(private val records: Records) {

    // The rows shown, or null for all records.
    private var rows: FilteredRows? = null

    var ended = false
        private set

    val schema: Schema get() = records.schema

    /** Raw text: one record per line, the line its only field, no levels. */
    val isRaw: Boolean get() = records.isRaw

    /** Whether a filter's result is shown rather than all records. */
    val isFiltered: Boolean get() = rows != null

    /** Number of rows. */
    val size: Int get() = if (ended) 0 else rows?.size ?: records.recordCount

    /** The record shown at [row], which must be below [size]. */
    fun recordAt(row: Int): Int = rows?.get(row) ?: row

    /** The row of [record], or of the first record shown after it; -1 if there is none. */
    fun rowOf(record: Int): Int {
        if (ended) return -1
        val rows = rows ?: return if (record < records.recordCount) record else -1
        return rows.indexOf(record)
    }

    fun level(record: Int): Level? = if (ended) null else records.level(record)

    /** Epoch nanoseconds of [record]'s timestamp, or null if there is none. */
    fun timestamp(record: Int): Long? = if (ended) null else records.timestamp(record)

    /** Display text of every field of [record], each cut to its first line and at most [maxBytes]. */
    fun rowValues(record: Int, maxBytes: Int): Array<String> =
        if (ended) Array(schema.fields.size) { "" } else records.rowValues(record, maxBytes)

    /** The whole value of [field] in [record]: all its lines, not cut. */
    fun fieldText(record: Int, field: Int): String = if (ended) "" else records.fieldText(record, field)

    /** The whole record as it appears in the file, cut at [maxBytes]. */
    fun recordText(record: Int, maxBytes: Int): String = if (ended) "" else records.recordText(record, maxBytes)

    /** The bytes of [record] exactly as in the file, its line ends included. */
    fun sourceText(record: Int): String = if (ended) "" else records.sourceText(record)

    /** The time of [row], or of the next row with one; [Records.NO_TIMESTAMP] if none is near. */
    fun timeFrom(row: Int): Long {
        for (i in maxOf(0, row)..<minOf(size, row + UNTIMED_ROWS)) {
            val time = records.timestampNanos(recordAt(i))
            if (time != Records.NO_TIMESTAMP) return time
        }
        return Records.NO_TIMESTAMP
    }

    /** The time of [row], or of the previous row with one; [Records.NO_TIMESTAMP] if none is near. */
    internal fun timeUntil(row: Int): Long {
        for (i in minOf(row, size - 1) downTo maxOf(0, row - UNTIMED_ROWS + 1)) {
            val time = records.timestampNanos(recordAt(i))
            if (time != Records.NO_TIMESTAMP) return time
        }
        return Records.NO_TIMESTAMP
    }

    /** The first row at [time] or later, or the last row if none is; -1 if there are no rows. */
    fun firstRowAt(time: Long): Int {
        val size = size
        if (size == 0) return -1
        // Rows are about in time order; a few out of order only make this land near them.
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            val found = timeFrom(middle)
            if (found != Records.NO_TIMESTAMP && found < time) low = middle + 1 else high = middle
        }
        return minOf(low, size - 1)
    }

    /** Copies [field] of every row, one value per line, reading on another thread. */
    suspend fun copyColumn(field: Int): CopyResult {
        if (ended) return CopyResult.FileChanged
        return copy(field, rows?.let { it.array().copyOf(it.size) }, size)
    }

    /** Copies [field] of [records], one value per line, reading on another thread. */
    suspend fun copyColumn(field: Int, records: IntArray): CopyResult {
        if (ended) return CopyResult.FileChanged
        return copy(field, records, records.size)
    }

    // The records are resolved here, on the owner thread, and read through snapshots on another.
    private suspend fun copy(field: Int, records: IntArray?, count: Int): CopyResult {
        val read = fieldReader(field, records, count)
        val result = try {
            withContext(Dispatchers.IO) {
                val text = StringBuilder()
                for (i in 0..<count) {
                    if (i % CANCEL_CHECK_ROWS == 0) ensureActive()
                    text.append(read(i)).append('\n')
                }
                CopyResult.Copied(text.toString())
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Closing the files under the read fails it.
            if (ended) return CopyResult.FileChanged
            if (e !is IOException) throw e
            CopyResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        return if (ended) CopyResult.FileChanged else result
    }

    private fun fieldReader(field: Int, records: IntArray?, count: Int): (Int) -> String = when (val log = this.records) {
        is MergedLog -> log.fieldReader(field, records, count)
        is LogData -> {
            val snapshot = log.filterSnapshot()
            if (records == null) { i -> snapshot.fieldText(i, field) } else { i -> snapshot.fieldText(records[i], field) }
        }
        else -> throw UnsupportedOperationException("Cannot copy from ${log.javaClass.simpleName}")
    }

    /** The rows shown, null for all records, and how many; for passes over them on other threads. */
    internal val rowArray: IntArray? get() = rows?.array()

    /** The record count while all are shown, or the filter result's size; unlike [size], also once [ended]. */
    internal val rowCount: Int get() = rows?.size ?: records.recordCount

    /** Shows [rows] (sorted records), or all records if null. */
    internal fun showRows(rows: IntArray?) {
        this.rows = rows?.let(::FilteredRows)
    }

    /**
     * Replaces the shown records from record [from] on with [rows] (sorted, all `>= from`), e.g. a filter
     * result for appended records. Returns the first row that changed.
     */
    internal fun replaceRowsFrom(from: Int, rows: IntArray): Int = this.rows!!.replaceFrom(from, rows)

    internal fun end() {
        ended = true
    }

    internal companion object {
        /** Rows without a time looked past for one. */
        const val UNTIMED_ROWS = 1000

        private const val CANCEL_CHECK_ROWS = 4096
    }
}
