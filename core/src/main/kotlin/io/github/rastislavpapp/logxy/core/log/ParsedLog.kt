package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.parse.FormatCheck
import io.github.rastislavpapp.logxy.core.parse.LineReader
import io.github.rastislavpapp.logxy.core.parse.RecordFormat
import io.github.rastislavpapp.logxy.core.parse.RecordParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Where the indexed data ends, so appended bytes can continue it. */
private class Tail(
    val lastLineStart: Long,
    /** False if the data ended without a newline; that line is parsed again when more bytes arrive. */
    val lastLineComplete: Boolean,
    val lastLineStartedRecord: Boolean,
) {
    companion object {
        val EMPTY = Tail(0, lastLineComplete = true, lastLineStartedRecord = false)
    }
}

private class AppendedRecords(
    val from: Long,
    val to: Long,
    val columns: Columns,
    val removeLast: Boolean,
    val tail: Tail,
) : AppendedData

/**
 * A log parsed into records by a [RecordFormat]. Values live in [Columns] segments, one per range
 * that was parsed in parallel; dictionary ids are shared across segments. Lines that do not start a
 * record continue the previous one (stack traces); such lines before the first record become records
 * of their own, with only a message.
 */
class ParsedLog private constructor(
    override val path: Path,
    private val channel: FileChannel,
    val format: RecordFormat,
    private val segments: ArrayList<Columns>,
    private val dictionaries: Array<ByteDictionary?>,
    indexedSize: Long,
    private var tail: Tail,
) : LogData {

    override val schema: Schema = format.schema

    override var indexedSize = indexedSize
        private set

    private var segmentFirst = firstRecords()
    private var lastSegment = 0
    private val levelField = schema.indexOf(Role.LEVEL)
    private val levels = ArrayList<Level>()

    /** How level texts map to [Level]; may change while the log is open. */
    var levelMapping: LevelMapping = LevelMapping.DEFAULT
        set(value) {
            field = value
            levels.clear()
        }

    override val recordCount get() = if (segments.isEmpty()) 0 else segmentFirst.last() + segments.last().size

    /** Whether timestamps never go backwards from one record to the next. */
    val timestampsSorted: Boolean
        get() {
            val field = schema.indexOf(Role.TIMESTAMP)
            if (field < 0) return false
            var previous = Long.MIN_VALUE
            for (segment in segments) {
                if (!segment.timestampsSorted) return false
                val timestamps = segment.longs[field]!!
                for (i in 0..<segment.size) {
                    val value = timestamps[i]
                    if (value == Columns.ABSENT_TIMESTAMP) continue
                    if (value < previous) return false
                    previous = value
                    break
                }
                for (i in segment.size - 1 downTo 0) {
                    val value = timestamps[i]
                    if (value != Columns.ABSENT_TIMESTAMP) {
                        previous = maxOf(previous, value)
                        break
                    }
                }
            }
            return true
        }

    /** Canonical level of [record], or null if the format has no level or the record lacks one. */
    fun level(record: Int): Level? {
        if (levelField < 0) return null
        val segment = segmentOf(record)
        val id = segments[segment].bytes[levelField]!![record - segmentFirst[segment]].toInt() and 0xFF
        if (id > Columns.MAX_LEVEL_ID) return null
        val dictionary = dictionaries[levelField]!!
        while (levels.size < dictionary.size) levels += levelMapping.of(dictionary.text(levels.size))
        return levels[id]
    }

    override fun rowValues(record: Int, maxBytes: Int): Array<String> {
        val segment = segmentOf(record)
        val columns = segments[segment]
        val local = record - segmentFirst[segment]
        val start = columns.starts[local]
        // A record can be huge, e.g. a whole file of lines that do not match the format.
        val recordLength = minOf(recordEnd(record) - start, Int.MAX_VALUE / 2L).toInt()

        // One read covers every span shown in the row.
        var needed = 0
        for (field in schema.fields.indices) {
            val lengths = columns.lengths[field] ?: continue
            val length = lengths[local]
            if (length == Columns.ABSENT_LENGTH) continue
            val relative = columns.ints[field]!![local]
            val spanLength = if (length == Columns.TO_RECORD_END) recordLength - relative else length
            needed = maxOf(needed, relative + minOf(spanLength, maxBytes + 2))
        }
        val bytes = if (needed > 0) read(start, minOf(needed, recordLength)) else EMPTY

        return Array(schema.fields.size) { field ->
            when (schema.fields[field].type) {
                ColumnType.TIMESTAMP -> {
                    val nanos = columns.longs[field]!![local]
                    if (nanos == Columns.ABSENT_TIMESTAMP) "" else format.formatTimestamp(field, nanos)
                }
                ColumnType.LEVEL -> {
                    val id = columns.bytes[field]!![local].toInt() and 0xFF
                    if (id > Columns.MAX_LEVEL_ID) "" else dictionaries[field]!!.text(id)
                }
                ColumnType.DICT -> {
                    val id = columns.ints[field]!![local]
                    if (id < 0) "" else dictionaries[field]!!.text(id)
                }
                ColumnType.SPAN -> {
                    val length = columns.lengths[field]!![local]
                    if (length == Columns.ABSENT_LENGTH) "" else {
                        val relative = columns.ints[field]!![local]
                        val end = if (length == Columns.TO_RECORD_END) bytes.size else minOf(relative + length, bytes.size)
                        firstLine(bytes, minOf(relative, end), end, maxBytes)
                    }
                }
            }
        }
    }

    override fun recordText(record: Int, maxBytes: Int): String {
        val start = recordStart(record)
        val length = recordEnd(record) - start
        val bytes = read(start, minOf(length, maxBytes + 2L).toInt())
        var end = bytes.size
        if (end.toLong() == length) {
            if (end > 0 && bytes[end - 1] == LF) end--
            if (end > 0 && bytes[end - 1] == CR) end--
        }
        return cut(bytes, 0, end, maxBytes)
    }

    override fun readAppended(size: Long): AppendedData {
        val removeLast = !tail.lastLineComplete && tail.lastLineStartedRecord
        val from = if (tail.lastLineComplete) indexedSize else tail.lastLineStart
        val columns = Columns(schema)
        if (size <= indexedSize) return AppendedRecords(from, from, columns, false, tail)
        val hasPrevious = recordCount - (if (removeLast) 1 else 0) > 0
        val result = parseRange(channel, from, size, format.newParser(), columns, hasPrevious, {}, null)
        return AppendedRecords(from, result.end, columns, removeLast, result.tail)
    }

    override fun append(data: AppendedData) {
        val appended = data as AppendedRecords
        val expectedFrom = if (tail.lastLineComplete) indexedSize else tail.lastLineStart
        require(appended.from == expectedFrom) { "Appended data starts at ${appended.from}, expected $expectedFrom" }
        if (appended.to == appended.from) return
        if (appended.removeLast) segments.last().removeLast()
        val source = appended.columns
        if (source.size > 0 && segments.isEmpty()) {
            segments += Columns(schema)
            segmentFirst = firstRecords()
        }
        if (source.size > 0) {
            val target = segments.last()
            val remaps = Array(schema.fields.size) { field ->
                val local = source.dictionaries[field] ?: return@Array null
                val global = dictionaries[field]!!
                IntArray(local.size) { id -> local.key(id).let { global.idOf(it, 0, it.size) } }
            }
            for (i in 0..<source.size) copyRecord(source, i, target, remaps)
        }
        indexedSize = appended.to
        tail = appended.tail
    }

    private fun copyRecord(source: Columns, i: Int, target: Columns, remaps: Array<IntArray?>) {
        target.beginRecord(source.starts[i])
        for ((field, definition) in schema.fields.withIndex()) {
            when (definition.type) {
                ColumnType.TIMESTAMP -> {
                    val nanos = source.longs[field]!![i]
                    if (nanos != Columns.ABSENT_TIMESTAMP) target.setTimestamp(field, nanos)
                }
                ColumnType.LEVEL -> {
                    val id = source.bytes[field]!![i].toInt() and 0xFF
                    if (id <= Columns.MAX_LEVEL_ID) target.setLevelId(field, remaps[field]!![id])
                }
                ColumnType.DICT -> {
                    val id = source.ints[field]!![i]
                    if (id >= 0) target.setDictId(field, remaps[field]!![id])
                }
                ColumnType.SPAN -> target.setSpan(field, source.ints[field]!![i], source.lengths[field]!![i])
            }
        }
    }

    private fun firstRecords(): IntArray {
        val first = IntArray(segments.size)
        for (i in 1..<segments.size) first[i] = first[i - 1] + segments[i - 1].size
        return first
    }

    private fun segmentOf(record: Int): Int {
        if (record < 0 || record >= recordCount) throw IndexOutOfBoundsException("Record $record, count $recordCount")
        val cached = lastSegment
        if (record >= segmentFirst[cached] && record < segmentFirst[cached] + segments[cached].size) return cached
        var low = 0
        var high = segments.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (segmentFirst[middle] <= record) low = middle else high = middle - 1
        }
        // Skip empty segments that share their first record number with the next one.
        while (record >= segmentFirst[low] + segments[low].size) low++
        lastSegment = low
        return low
    }

    private fun recordStart(record: Int): Long {
        val segment = segmentOf(record)
        return segments[segment].starts[record - segmentFirst[segment]]
    }

    private fun recordEnd(record: Int): Long = if (record + 1 < recordCount) recordStart(record + 1) else indexedSize

    private fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) break
        }
        return if (buffer.position() == length) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    override fun close() {
        channel.close()
    }

    private class RangeResult(val end: Long, val truncated: Boolean, val lines: Int, val tail: Tail)

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val RESYNC_BUFFER_SIZE = 64 * 1024
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private val EMPTY = ByteArray(0)

        /**
         * Parses [path] on [Dispatchers.IO]; cancelling the calling coroutine stops it. Throws
         * [io.github.rastislavpapp.logxy.core.parse.FormatMismatchException] if the file does not fit [format].
         */
        suspend fun build(
            path: Path,
            format: RecordFormat,
            progress: IndexProgress = IndexProgress(),
            options: IndexOptions = IndexOptions(),
        ): ParsedLog = withContext(Dispatchers.IO) {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val size = channel.size()
                progress.totalBytes = size
                FormatCheck.check(channel, size, format.newParser())
                val count = (size / options.minRangeSize).coerceIn(1, options.parallelism.toLong()).toInt()

                // Ranges must start where records start, so each can be parsed on its own.
                val bounds = LongArray(count + 1)
                bounds[count] = size
                (1..<count).map { i ->
                    async { bounds[i] = resync(channel, size / count * i, size, format.newParser()) }
                }.awaitAll()
                for (i in 1..count) bounds[i] = bounds[i].coerceIn(bounds[i - 1], size)

                val parsed = (0..<count).map { i ->
                    async {
                        val job = coroutineContext.job
                        val columns = Columns(format.schema)
                        val result = parseRange(
                            channel, bounds[i], bounds[i + 1], format.newParser(), columns,
                            hasPrevious = false, check = { job.ensureActive() }, progress = progress,
                        )
                        columns to result
                    }
                }.awaitAll()
                ensureActive()

                val segments = ArrayList<Columns>()
                var indexedSize = 0L
                var tail = Tail.EMPTY
                for ((columns, result) in parsed) {
                    if (columns.size > 0) segments += columns
                    indexedSize = result.end
                    if (result.lines > 0) tail = result.tail
                    // The file was truncated while parsing; later ranges are unreliable.
                    if (result.truncated) break
                }
                val dictionaries = mergeDictionaries(format.schema, segments)
                ParsedLog(path, channel, format, segments, dictionaries, indexedSize, tail)
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }

        /** The start of the first record at or after [from]; [end] if there is none. */
        private fun resync(channel: FileChannel, from: Long, end: Long, parser: RecordParser): Long {
            // Start one byte early: the first line read is then only the rest of a line, and skipped.
            val reader = LineReader(channel, from - 1, end, RESYNC_BUFFER_SIZE)
            if (!reader.next()) return end
            while (reader.next()) {
                if (parser.match(reader.buffer, reader.lineFrom, reader.lineTo)) return reader.lineStart
            }
            return end
        }

        private fun parseRange(
            channel: FileChannel,
            from: Long,
            to: Long,
            parser: RecordParser,
            out: Columns,
            hasPrevious: Boolean,
            check: () -> Unit,
            progress: IndexProgress?,
        ): RangeResult {
            val reader = LineReader(channel, from, to, BUFFER_SIZE) { progress?.add(it.toLong()) }
            val messageField = out.schema.indexOf(Role.MESSAGE)
            var inRecord = hasPrevious
            var tail = Tail(from, lastLineComplete = true, lastLineStartedRecord = false)
            var lines = 0
            while (reader.next()) {
                if (++lines and 1023 == 0) check()
                val matched = parser.match(reader.buffer, reader.lineFrom, reader.lineTo)
                val startsRecord = matched || !inRecord
                if (matched) {
                    out.beginRecord(reader.lineStart)
                    parser.write(out)
                } else if (startsRecord) {
                    out.beginRecord(reader.lineStart)
                    if (messageField >= 0) out.setSpan(messageField, 0, Columns.TO_RECORD_END)
                }
                inRecord = true
                tail = Tail(reader.lineStart, reader.hasNewline, startsRecord)
            }
            return RangeResult(reader.nextLineStart, reader.truncated, lines, tail)
        }

        private suspend fun mergeDictionaries(schema: Schema, segments: List<Columns>): Array<ByteDictionary?> =
            withContext(Dispatchers.Default) {
                val global = Array(schema.fields.size) { field ->
                    if (schema.fields[field].type == ColumnType.LEVEL || schema.fields[field].type == ColumnType.DICT) {
                        ByteDictionary()
                    } else {
                        null
                    }
                }
                val remaps = segments.map { segment ->
                    Array(schema.fields.size) { field ->
                        val local = segment.dictionaries[field] ?: return@Array null
                        IntArray(local.size) { id -> local.key(id).let { global[field]!!.idOf(it, 0, it.size) } }
                    }
                }
                segments.mapIndexed { s, segment ->
                    async {
                        for (field in schema.fields.indices) {
                            val remap = remaps[s][field] ?: continue
                            segment.bytes[field]?.let { ids ->
                                for (i in 0..<ids.size) {
                                    val id = ids[i].toInt() and 0xFF
                                    if (id <= Columns.MAX_LEVEL_ID) ids[i] = minOf(remap[id], Columns.MAX_LEVEL_ID).toByte()
                                }
                            }
                            if (schema.fields[field].type == ColumnType.DICT) {
                                val ids = segment.ints[field]!!
                                for (i in 0..<ids.size) {
                                    val id = ids[i]
                                    if (id >= 0) ids[i] = remap[id]
                                }
                            }
                            segment.dictionaries[field] = null
                        }
                    }
                }.awaitAll()
                global
            }

        /** The first line of `bytes[from, to)`, cut at [maxBytes]. */
        private fun firstLine(bytes: ByteArray, from: Int, to: Int, maxBytes: Int): String {
            var end = from
            while (end < to && bytes[end] != LF) end++
            if (end > from && bytes[end - 1] == CR) end--
            return cut(bytes, from, end, maxBytes)
        }

        private fun cut(bytes: ByteArray, from: Int, to: Int, maxBytes: Int): String {
            if (to - from <= maxBytes) return String(bytes, from, to - from, Charsets.UTF_8)
            var end = from + maxBytes
            while (end > from && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return String(bytes, from, end - from, Charsets.UTF_8) + "…"
        }
    }
}
