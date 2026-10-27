package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.config.RowHighlight
import io.github.rastislavpapp.logxy.core.filter.Narrowing
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.RowText
import io.github.rastislavpapp.logxy.core.log.Schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException

/** What a [PreparedCopy] got. */
sealed interface CopyResult {
    /** The text, one line per row; a record of several lines takes as many. */
    data class Copied(val text: String) : CopyResult

    /**
     * Nothing was copied, as the [rows] take [bytes] in the files, more than the copy's limit. A column copy stops
     * where it went over the limit, so for it [bytes] counts the characters copied by then.
     */
    data class TooLarge(val rows: Int, val bytes: Long) : CopyResult

    /** The session reloaded or closed the files while copying. */
    data object FileChanged : CopyResult

    data class Failed(val message: String) : CopyResult
}

/** The row going to a time lands on, see [ShownLog.landingAt]; [miss] is null if it is the record at that time. */
data class TimeLanding(val row: Int, val miss: Miss?) {
    enum class Miss {
        /** The filter hides the record at the time; the row is the next one shown. */
        HIDDEN,

        /** No shown record is at the time or later; the row is the last one. */
        PAST_LAST,
    }
}

/** What [ShownLog.nextFound] gives while the search has not decided the records that may hold the match. */
const val NOT_YET_FOUND = -2

/** The most a copy may take, whatever the memory. */
const val MAX_COPY_BYTES = 512L shl 20

/**
 * The limit for a copy: [MAX_COPY_BYTES], or less with a heap of [maxMemory] bytes, as the text is held several
 * times over while it goes to the clipboard.
 */
fun copyLimit(maxMemory: Long = Runtime.getRuntime().maxMemory()): Long = minOf(MAX_COPY_BYTES, maxMemory / 4)

/** A copy taken on the session's thread, ready to read on another; see [ShownLog.copyRows]. */
interface PreparedCopy {
    /** Reads the text on another thread; cancelling the calling coroutine stops it. */
    suspend fun run(): CopyResult

    /** How far [run] got, from 0 to 1; readable from any thread. */
    val progress: Double
}

/**
 * The records of one load of a [LogSession] as the user sees them: all of them, or a filter's result.
 * A row is a record's position among those shown. The session changes it as records are appended
 * and filtered; use it on the session's thread. Once the session reloads or closes, it is [ended]:
 * it shows no rows and its reads return empty (see docs/adr/0001).
 */
class ShownLog internal constructor(private val loaded: LoadedLog) {

    private val records = loaded.records

    // The rows shown, or null for all records.
    private var rows: FilteredRows? = null

    // The records the search found, all of them shown.
    private var found = FilteredRows(IntArray(0))

    // What a search started anew has found so far, while it runs and reports chunk by chunk; null otherwise.
    private var foundSoFar: FoundChunks? = null

    // Which of [evaluatedHighlights] each record takes.
    private val recordHighlights = RecordHighlights()
    private var evaluatedHighlights = emptyList<RowHighlight>()

    var ended = false
        private set

    /**
     * Whether the search over all rows runs. A search that reports chunk by chunk has found part of the records
     * meanwhile, see [foundCount]; any other finds nothing until it is done.
     */
    var isSearching = false
        get() = field && !ended
        private set

    /** Number of records the search found, so far while it runs. */
    val foundCount: Int get() = if (ended) 0 else foundSoFar?.size ?: found.size

    /**
     * 1-based position of [record] among the found records, 0 if it is not one. While the search runs, also 0 until
     * the position is final, i.e. every record before [record] is searched.
     */
    fun foundPosition(record: Int): Int =
        if (ended) 0 else foundSoFar?.positionOf(record) ?: found.positionOf(record)

    /**
     * The found record after [record], or before it unless [forward], wrapping around; with [inclusive],
     * [record] itself counts. -1 if none is found. While the search runs, [NOT_YET_FOUND] if a record that may
     * hold the match is not searched yet: a match is only given once no match can come between it and [record].
     */
    fun nextFound(record: Int, forward: Boolean, inclusive: Boolean): Int {
        val partial = foundSoFar
        return when {
            ended -> -1
            partial != null -> partial.next(record, forward, inclusive)
            // The found records are those of the search before, which a narrowing one is about to replace.
            isSearching -> NOT_YET_FOUND
            else -> found.next(record, forward, inclusive)
        }
    }

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

    /**
     * The level of [record], as the current level mapping reads its level text; null if it has none or the shown
     * log ended.
     */
    fun level(record: Int): Level? = if (ended) null else records.level(record)

    /**
     * The row highlights that [rowHighlight] gives the index of: those of the settings when the records were last
     * evaluated. Empty once the shown log ended.
     */
    val rowHighlights: List<RowHighlight> get() = if (ended) emptyList() else evaluatedHighlights

    /**
     * The index in [rowHighlights] of [record]'s row highlight, the first one it matches; the same in every shown log
     * of the load, whatever the filter. -1 for none, also while the record is not evaluated yet and once the shown
     * log ended.
     */
    fun rowHighlight(record: Int): Int = if (ended) -1 else recordHighlights.of(record)

    /** Epoch nanoseconds of [record]'s timestamp, or null if there is none. */
    fun timestamp(record: Int): Long? = if (ended) null else records.timestamp(record)

    /** Display text of every field of [record], each cut to its first line and at most [maxBytes]. */
    fun rowValues(record: Int, maxBytes: Int): Array<String> = row(record, maxBytes, 0).values

    /** See [Records.row]; empty values and no more lines once the shown log ended. */
    fun row(record: Int, maxBytes: Int, countBytes: Int): RowText =
        if (ended) RowText(Array(schema.fields.size) { "" }, 0) else records.row(record, maxBytes, countBytes)

    /** See [Records.laterLines]. */
    fun laterLines(record: Int, maxBytes: Int, cutMark: String): String =
        if (ended) "" else records.laterLines(record, maxBytes, cutMark)

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
        return minOf(firstAt(time, size, ::timeFrom), size - 1)
    }

    /**
     * Where going to [time] lands: the first row at [time] or later, or the last row if none is, as [firstRowAt];
     * null if there are no rows. Its [TimeLanding.miss] tells why the row is not the record at [time] or just after.
     */
    fun landingAt(time: Long): TimeLanding? {
        val row = firstRowAt(time)
        if (row < 0) return null
        val rowTime = timeFrom(row)
        if (rowTime == Records.NO_TIMESTAMP || rowTime < time) return TimeLanding(row, TimeLanding.Miss.PAST_LAST)
        if (rows == null) return TimeLanding(row, miss = null)
        val firstRecord = firstAt(time, records.recordCount, ::recordTimeFrom)
        val miss = if (firstRecord < recordAt(row)) TimeLanding.Miss.HIDDEN else null
        return TimeLanding(row, miss)
    }

    /**
     * The first of [count] places, rows or records, whose time ([timeFrom] of it) is [time] or later; [count] if none
     * is. Places are about in time order; a few out of order only make this land near them.
     */
    private inline fun firstAt(time: Long, count: Int, timeFrom: (Int) -> Long): Int {
        var low = 0
        var high = count
        while (low < high) {
            val middle = (low + high) ushr 1
            val found = timeFrom(middle)
            if (found != Records.NO_TIMESTAMP && found < time) low = middle + 1 else high = middle
        }
        return low
    }

    /** Like [timeFrom], but of [record] among all records, shown or not. */
    private fun recordTimeFrom(record: Int): Long {
        for (i in record..<minOf(records.recordCount, record + UNTIMED_ROWS)) {
            val time = records.timestampNanos(i)
            if (time != Records.NO_TIMESTAMP) return time
        }
        return Records.NO_TIMESTAMP
    }

    /**
     * The first row from [from] to [to] (exclusive) whose level counts in [category], one of [TimeHistogram]'s;
     * -1 if there is none. Rows without a time between them are looked at too.
     */
    fun firstRowIn(from: Long, to: Long, category: Int): Int {
        val start = firstRowAt(from)
        if (start < 0) return -1
        for (row in start..<size) {
            val record = recordAt(row)
            val time = records.timestampNanos(record)
            if (time != Records.NO_TIMESTAMP && time >= to) break
            if (time != Records.NO_TIMESTAMP && time < from) continue
            if (TimeHistogram.category(records.level(record)) == category) return row
        }
        return -1
    }

    /**
     * Copies [rows], all below [size], as their records are written in the files, each starting on a line of its
     * own.
     * Rows taking more than [limit] bytes there are refused before any is read.
     */
    fun copyRows(rows: RowRanges, limit: Long): PreparedCopy {
        if (ended) return EndedCopy
        requireShown(rows)
        val ranges = rows.copy()
        val first = maxOf(0, ranges.min)
        // A filter's rows array may change later: the part the rows cover is copied now.
        val records = this.rows?.array()?.copyOfRange(first, ranges.max + 1)
        return RowsCopy(ranges, records, first, loaded.sourceReader(), limit)
    }

    /**
     * Copies [field] of [rows], all below [size], or of every row if null, one value per line. Stops once the text
     * takes more than [limit] characters.
     */
    fun copyColumn(field: Int, limit: Long, rows: RowRanges? = null): PreparedCopy {
        if (ended) return EndedCopy
        // The records are resolved here, on the owner thread, and read through snapshots on another.
        val shown = this.rows
        val records = when {
            rows != null -> recordsOf(rows)
            shown != null -> shown.array().copyOf(shown.size)
            else -> null
        }
        val count = records?.size ?: size
        return ColumnCopy(loaded.fieldReader(field, records, count), count, limit)
    }

    private fun requireShown(rows: RowRanges) = require(rows.max < size) { "Row ${rows.max}, size $size" }

    /** The records of [rows], in order. */
    private fun recordsOf(rows: RowRanges): IntArray {
        requireShown(rows)
        val records = IntArray(rows.count)
        var i = 0
        val shown = this.rows?.array()
        rows.forEachRange { start, end ->
            if (shown != null) {
                System.arraycopy(shown, start, records, i, end - start)
            } else {
                for (row in start..<end) records[i + row - start] = row
            }
            i += end - start
        }
        return records
    }

    /** Copies the [count] values [read] gives. */
    private inner class ColumnCopy(
        private val read: (Int) -> String,
        private val count: Int,
        private val limit: Long,
    ) : PreparedCopy {
        @Volatile
        override var progress = 0.0
            private set

        override suspend fun run(): CopyResult = readOff {
            val text = StringBuilder()
            for (i in 0..<count) {
                if (i % CANCEL_CHECK_ROWS == 0) {
                    ensureActive()
                    progress = i.toDouble() / count
                }
                text.append(read(i)).append('\n')
                if (text.length > limit) return@readOff CopyResult.TooLarge(count, text.length.toLong())
            }
            progress = 1.0
            CopyResult.Copied(text.toString())
        }
    }

    /** Reads off the owner thread; the result is [CopyResult.FileChanged] once the load ended meanwhile. */
    private suspend fun readOff(read: suspend CoroutineScope.() -> CopyResult): CopyResult {
        val result = try {
            withContext(Dispatchers.IO, read)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Closing the files under the read fails it.
            if (ended) return CopyResult.FileChanged
            if (e !is IOException) throw e
            CopyResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        return if (ended) CopyResult.FileChanged else result
    }

    /**
     * Copies the records of the rows in [ranges]. While all records are shown, a row is its record; for a filter's
     * result, [records] holds the records of the rows from [firstRow] on.
     */
    private inner class RowsCopy(
        private val ranges: RowRanges,
        private val records: IntArray?,
        private val firstRow: Int,
        private val reader: SourceReader,
        private val limit: Long,
    ) : PreparedCopy {
        @Volatile
        override var progress = 0.0
            private set

        private var bytes = 0L
        private var done = 0L

        override suspend fun run(): CopyResult = readOff {
            val job = coroutineContext.job
            forEachRun { from, to ->
                job.ensureActive()
                reader.spans(from, to) { _, start, end -> bytes += end - start }
            }
            if (bytes > limit) return@readOff CopyResult.TooLarge(ranges.count, bytes)
            // Each character takes at least a byte; a line end may be added after each part's last record.
            val capacity = minOf(bytes + ranges.rangeCount, Int.MAX_VALUE - 8L)
            val text = StringBuilder(capacity.toInt())
            forEachRun { from, to ->
                reader.spans(from, to) { part, start, end -> append(part, start, end, text, job) }
            }
            progress = 1.0
            CopyResult.Copied(text.toString())
        }

        /** Calls [run] with each run of the rows' records that follow each other, as records [from] to [to]. */
        private inline fun forEachRun(run: (from: Int, to: Int) -> Unit) {
            val records = records ?: return ranges.forEachRange(run)
            ranges.forEachRange { start, end ->
                var i = start - firstRow
                val stop = end - firstRow
                while (i < stop) {
                    var next = i + 1
                    while (next < stop && records[next] == records[next - 1] + 1) next++
                    run(records[i], records[next - 1] + 1)
                    i = next
                }
            }
        }

        /** Appends [part]'s bytes from [start] to [end], read a chunk at a time. */
        private fun append(part: Int, start: Long, end: Long, out: StringBuilder, job: Job) {
            var position = start
            var last = NEWLINE
            while (position < end) {
                job.ensureActive()
                val length = minOf(end - position, READ_BYTES.toLong()).toInt()
                val bytes = reader.read(part, position, length)
                if (bytes.size < length) throw IOException("The file is shorter than when it was read")
                val whole = if (position + length == end) length else wholeCharacters(bytes)
                out.append(String(bytes, 0, whole, Charsets.UTF_8))
                last = bytes[whole - 1]
                position += whole
                done += whole
                progress = done.toDouble() / this.bytes
            }
            // A file's last line may have no line end; the next record must still start on a line of its own.
            if (last != NEWLINE) out.append('\n')
        }
    }

    /**
     * The records of the shown rows, or null while all records are shown; with [rowCount], for passes over them
     * on other threads.
     */
    internal val rowArray: IntArray? get() = rows?.array()

    /** The record count while all are shown, or the filter result's size; unlike [size], also once [ended]. */
    internal val rowCount: Int get() = rows?.size ?: records.recordCount

    /**
     * Shows [rows], sorted record numbers, or all records if null. Returns a function that gives, for a row, the
     * record it showed before.
     */
    internal fun showRows(rows: IntArray?): (row: Int) -> Int {
        val old = this.rows
        this.rows = rows?.let(::FilteredRows)
        return { row -> old?.get(row) ?: row }
    }

    /**
     * Replaces the shown records from record [from] on with [rows] (sorted, all `>= from`), e.g. a filter
     * result for appended records. Returns the first row that changed.
     */
    internal fun replaceRowsFrom(from: Int, rows: IntArray): Int = this.rows!!.replaceFrom(from, rows)

    /** Shows [records], sorted, as what a search over all rows found; the search is done. */
    internal fun showFound(records: IntArray) {
        found = FilteredRows(records)
        foundSoFar = null
        isSearching = false
    }

    /**
     * Shows that a search over the first [recordCount] records runs and found nothing yet; [addFoundChunk] tells
     * what it finds meanwhile.
     */
    internal fun startFound(recordCount: Int) {
        found = FilteredRows(IntArray(0))
        foundSoFar = FoundChunks(recordCount)
        isSearching = true
    }

    /** Adds the matches [records] (sorted) among the records `[first, end)` to what the running search found. */
    internal fun addFoundChunk(first: Int, end: Int, records: IntArray) {
        foundSoFar?.add(first, end, records)
    }

    /** The found records from record [from] on, once the search is done. */
    internal fun foundFrom(from: Int): IntArray {
        val index = found.indexOf(from)
        val start = if (index < 0) found.size else index
        return found.array().copyOfRange(start, found.size)
    }

    /** Keeps the found records but marks that a search over them runs, which replaces them when done. */
    internal fun markSearching() {
        isSearching = true
    }

    /** The found records as a limit for a stricter search, trusted below record [upTo]. */
    internal fun narrowing(upTo: Int) = Narrowing(found.array(), found.size, upTo)

    /** Replaces the found records from record [from] on with [records], which are sorted and all `>= from`. */
    internal fun replaceFoundFrom(from: Int, records: IntArray) {
        found.replaceFrom(from, records)
    }

    /** Forgets the found records, e.g. when the search is cleared. */
    internal fun clearFound() = showFound(IntArray(0))

    /** Whether any record was evaluated for its row highlight. */
    internal val hasRowHighlights: Boolean get() = recordHighlights.size > 0

    /**
     * Shows [part], the row highlights of the records from its first one on, as indexes into [list]; a part from
     * the first record replaces all of them.
     */
    internal fun showRowHighlights(list: List<RowHighlight>, part: HighlightsPart) {
        recordHighlights.replace(part)
        evaluatedHighlights = list
    }

    /**
     * Ends the shown log before its load's files close: from now on it has no rows and its reads return nothing
     * (ADR 0001).
     */
    internal fun end() {
        ended = true
    }

    internal companion object {
        /** How many rows without a time are looked past for one with a time. */
        const val UNTIMED_ROWS = 1000

        private const val CANCEL_CHECK_ROWS = 4096

        /** Bytes read from a file at once when copying records. */
        private const val READ_BYTES = 1 shl 20

        private const val NEWLINE = '\n'.code.toByte()
    }
}

/** What copying from a shown log that already ended gets. */
private object EndedCopy : PreparedCopy {
    override suspend fun run() = CopyResult.FileChanged
    override val progress = 1.0
}

/**
 * How many of the UTF-8 [bytes] hold whole characters: all of them, or fewer if the last character is cut off at
 * the end of the array.
 */
private fun wholeCharacters(bytes: ByteArray): Int {
    // The last character's first byte: the last byte that is not a continuation byte (10xxxxxx).
    var first = bytes.size - 1
    while (first > 0 && bytes.size - first < UTF8_MAX_BYTES && bytes[first].toInt() and 0xC0 == 0x80) first--
    val lead = bytes[first].toInt() and 0xFF
    val length = when {
        lead >= 0xF0 -> 4
        lead >= 0xE0 -> 3
        lead >= 0xC0 -> 2
        else -> 1
    }
    return if (first + length <= bytes.size) bytes.size else first
}

private const val UTF8_MAX_BYTES = 4
