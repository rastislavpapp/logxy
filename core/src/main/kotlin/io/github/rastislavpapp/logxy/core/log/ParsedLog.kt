package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.ReadChannels
import io.github.rastislavpapp.logxy.core.readFully
import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.filter.ParsedSnapshot
import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.parse.ParserCheck
import io.github.rastislavpapp.logxy.core.parse.Json
import io.github.rastislavpapp.logxy.core.parse.LineReader
import io.github.rastislavpapp.logxy.core.parse.MdcMap
import io.github.rastislavpapp.logxy.core.parse.CompiledParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path

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
 * A log parsed into records by a [CompiledParser]. Values live in [Columns] segments, one per range
 * that was parsed in parallel; dictionary ids are shared across segments. Lines that do not start a
 * record continue the previous one (stack traces); such lines before the first record become records
 * of their own, with only a message.
 *
 * [schema] differs from the parser's: a DICT field whose values turn out mostly distinct (e.g. a
 * misdetected layout) is a SPAN, and each key found in an MDC map while indexing gets a field of its
 * own, read from the map; keys that first appear in appended records do not.
 */
class ParsedLog private constructor(
    override val path: Path,
    private val channels: ReadChannels,
    val parser: CompiledParser,
    /** How [Columns] store the fields: the parser's schema, with DICT fields stored as spans where needed. */
    private val storage: Schema,
    schema: Schema,
    private val segments: ArrayList<Columns>,
    private val dictionaries: Array<ByteDictionary?>,
    indexedSize: Long,
    private var tail: Tail,
) : LogData() {

    override var indexedSize = indexedSize
        private set

    /** Changes only when appended records have an optional field none had before, which is then shown. */
    override var schema = schema
        private set

    private var segmentFirst = firstRecords()
    private var lastSegment = 0
    private val levelField = schema.indexOf(Role.LEVEL)
    private val timestampField = schema.indexOf(Role.TIMESTAMP)
    private val levels = ArrayList<Level>()

    override val isRaw get() = false

    override var levelMapping: LevelMapping = LevelMapping.DEFAULT
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

    override fun level(record: Int): Level? {
        if (levelField < 0) return null
        val segment = segmentOf(record)
        val id = segments[segment].bytes[levelField]!![record - segmentFirst[segment]].toInt() and 0xFF
        if (id > Columns.MAX_LEVEL_ID) return null
        val dictionary = dictionaries[levelField]!!
        while (levels.size < dictionary.size) levels += levelMapping.of(dictionary.text(levels.size))
        return levels[id]
    }

    override fun timestampNanos(record: Int): Long {
        if (timestampField < 0) return Columns.ABSENT_TIMESTAMP
        val segment = segmentOf(record)
        return segments[segment].longs[timestampField]!![record - segmentFirst[segment]]
    }

    override fun filterSnapshot(): RecordSnapshot = ParsedSnapshot(
        schema, recordCount, indexedSize, channels,
        segments = segments.toList(),
        first = segmentFirst.copyOf(),
        texts = Array(schema.fields.size) { field -> dictionaries.getOrNull(field)?.let { d -> List(d.size) { d.text(it) } } },
        levels = levelMapping,
        parser = parser,
    )

    override fun rowValues(record: Int, maxBytes: Int): Array<String> {
        val segment = segmentOf(record)
        val columns = segments[segment]
        val local = record - segmentFirst[segment]
        val start = columns.starts[local]
        // A record can be huge, e.g. a whole file of lines that do not match the parser.
        val recordLength = minOf(recordEnd(record) - start, Int.MAX_VALUE / 2L).toInt()

        // One read covers every span shown in the row.
        var needed = 0
        for (field in storage.fields.indices) {
            if (storage.fields[field].type != ColumnType.SPAN) continue
            val length = columns.spanLength(field, local)
            if (length == Columns.ABSENT_LENGTH) continue
            val relative = columns.ints[field]!![local]
            val spanLength = if (length == Columns.TO_RECORD_END) recordLength - relative else length
            // Values of map keys may be anywhere in the map.
            needed = maxOf(needed, relative + if (storage.fields[field].isMap) spanLength else minOf(spanLength, maxBytes + 2))
        }
        val bytes = if (needed > 0) read(start, minOf(needed, recordLength)) else EMPTY

        return Array(schema.fields.size) { field ->
            val key = schema.fields[field].mapKey
            if (key != null) return@Array mapValue(columns, local, key, bytes, maxBytes)
            columns.valueText(field, local, parser, dictionaries[field]) ?: run {
                val length = columns.spanLength(field, local)
                if (length == Columns.ABSENT_LENGTH) "" else {
                    val relative = columns.ints[field]!![local]
                    val end = if (length == Columns.TO_RECORD_END) bytes.size else minOf(relative + length, bytes.size)
                    // Lines that are no JSON run to the record end and are shown as written.
                    if (schema.fields[field].json && length != Columns.TO_RECORD_END) {
                        val decoded = Json.decode(bytes, minOf(relative, end), end)
                        firstLine(decoded, 0, decoded.size, maxBytes)
                    } else {
                        firstLine(bytes, minOf(relative, end), end, maxBytes)
                    }
                }
            }
        }
    }

    override fun fieldText(record: Int, field: Int): String {
        val segment = segmentOf(record)
        val local = record - segmentFirst[segment]
        return fieldText(schema, segments[segment], local, field, parser, { dictionaries[field]!!.text(it) }) { start ->
            read(start, minOf(recordEnd(record) - start, Int.MAX_VALUE / 2L).toInt())
        }
    }

    override fun recordText(record: Int, maxBytes: Int): String {
        val start = recordStart(record)
        val length = recordEnd(record) - start
        val bytes = read(start, minOf(length, maxBytes + 2L).toInt())
        val complete = bytes.size.toLong() == length
        val end = if (complete) Bytes.trimLineEnd(bytes, 0, bytes.size) else bytes.size
        if (complete) parser.detailText(bytes, 0, end)?.let { return it }
        return Bytes.utf8Text(bytes, 0, end, maxBytes)
    }

    override fun sourceText(record: Int): String {
        val start = recordStart(record)
        return String(read(start, (recordEnd(record) - start).toInt()), Charsets.UTF_8)
    }

    override fun readAppended(size: Long): AppendedData {
        val removeLast = !tail.lastLineComplete && tail.lastLineStartedRecord
        val from = if (tail.lastLineComplete) indexedSize else tail.lastLineStart
        val columns = Columns(storage)
        if (size <= indexedSize) return AppendedRecords(from, from, columns, false, tail)
        val hasPrevious = recordCount - (if (removeLast) 1 else 0) > 0
        val result = parseRange(channels.main, from, size, parser, columns, hasPrevious, {}, null)
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
            segments += Columns(storage)
            segmentFirst = firstRecords()
        }
        if (source.size > 0) {
            val target = segments.last()
            val remaps = Array(storage.fields.size) { field ->
                val local = source.dictionaries[field] ?: return@Array null
                local.idsIn(dictionaries[field]!!)
            }
            for (i in 0..<source.size) copyRecord(source, i, target, remaps)
            showPresent(source)
        }
        indexedSize = appended.to
        tail = appended.tail
    }

    /** Shows the optional fields hidden as absent that [source] has values of, e.g. a JSON log's first exception. */
    private fun showPresent(source: Columns) {
        val fields = schema.fields
        // A map split into its keys stays hidden: it is shown through them.
        val split = fields.mapNotNull { it.mapKey?.field }.toSet()
        val present = fields.indices.filter { field ->
            fields[field].hidden && fields[field].optional && field !in split && field < source.present.size && source.present[field]
        }
        if (present.isNotEmpty()) schema = Schema(fields.mapIndexed { i, field -> if (i in present) field.copy(hidden = false) else field })
    }

    private fun copyRecord(source: Columns, i: Int, target: Columns, remaps: Array<IntArray?>) {
        target.beginRecord(source.starts[i])
        for ((field, definition) in storage.fields.withIndex()) {
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
                ColumnType.SPAN -> target.setSpan(field, source.ints[field]!![i], source.spanLength(field, i))
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
        val bytes = ByteArray(length)
        val read = channels.main.readFully(ByteBuffer.wrap(bytes), position)
        return if (read == length) bytes else bytes.copyOf(read)
    }

    override fun close() {
        channels.close()
    }

    private class RangeResult(val end: Long, val truncated: Boolean, val lines: Int, val tail: Tail)

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val RESYNC_BUFFER_SIZE = 64 * 1024
        private val EMPTY = ByteArray(0)

        /**
         * Parses [path] on [Dispatchers.IO]; cancelling the calling coroutine stops it. Throws
         * [io.github.rastislavpapp.logxy.core.parse.ParserMismatchException] if the file does not fit [parser].
         */
        suspend fun build(
            path: Path,
            parser: CompiledParser,
            progress: IndexProgress = IndexProgress(),
            options: IndexOptions = IndexOptions(),
        ): ParsedLog = withContext(Dispatchers.IO) {
            val channels = ReadChannels.open(path, options.readChannels)
            try {
                val channel = channels.main
                val size = channel.size()
                progress.totalBytes = size
                ParserCheck.check(channel, size, parser.newMatcher())
                val count = options.rangeCount(size)

                // Ranges must start where records start, so each can be parsed on its own.
                val bounds = LongArray(count + 1)
                bounds[count] = size
                (1..<count).map { i ->
                    async { bounds[i] = resync(channels.forTask(i), size / count * i, size, parser) }
                }.awaitAll()
                for (i in 1..count) bounds[i] = bounds[i].coerceIn(bounds[i - 1], size)

                val (storage, parsed) = parseAll(channels, bounds, parser, progress)
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
                val dictionaries = mergeDictionaries(storage, segments)
                val schema = withoutAbsent(withMapKeys(storage, mapKeys(storage, segments)), segments)
                ParsedLog(path, channels, parser, storage, schema, segments, dictionaries, indexedSize, tail)
            } catch (e: Throwable) {
                channels.close()
                throw e
            }
        }

        /** Thrown by a range whose [Columns.overflowed]; ends the other ranges too. */
        private class DictionaryOverflow(val field: Int) : Exception(null, null, false, false)

        /** The ranges between [bounds], and the schema they were parsed with. */
        private suspend fun parseAll(
            channels: ReadChannels,
            bounds: LongArray,
            parser: CompiledParser,
            progress: IndexProgress,
        ): Pair<Schema, List<Pair<Columns, RangeResult>>> {
            var schema = parser.schema
            while (true) {
                try {
                    return schema to parseRanges(channels, bounds, parser, schema, progress)
                } catch (e: DictionaryOverflow) {
                    // Noticed early on, so starting over costs little.
                    schema = schema.asSpan(e.field)
                    progress.reset()
                }
            }
        }

        private suspend fun parseRanges(
            channels: ReadChannels,
            bounds: LongArray,
            parser: CompiledParser,
            schema: Schema,
            progress: IndexProgress,
        ): List<Pair<Columns, RangeResult>> = coroutineScope {
            (0..<bounds.size - 1).map { i ->
                async {
                    val job = coroutineContext.job
                    val columns = Columns(schema)
                    val check = {
                        job.ensureActive()
                        if (columns.overflowed >= 0) throw DictionaryOverflow(columns.overflowed)
                    }
                    val result = parseRange(
                        channels.forTask(i), bounds[i], bounds[i + 1], parser, columns,
                        hasPrevious = false, check = check, progress = progress,
                    )
                    columns to result
                }
            }.awaitAll()
        }

        /** Per field, the keys found in it if it is a map that can be split, sorted. */
        private fun mapKeys(storage: Schema, segments: List<Columns>): Array<List<String>?> =
            Array(storage.fields.size) { field ->
                if (!storage.fields[field].isMap) return@Array null
                val keys = sortedSetOf<String>()
                for (segment in segments) {
                    val found = segment.mapKeys[field] ?: return@Array null
                    repeat(found.size) { keys += found.text(it) }
                    segment.mapKeys[field] = null
                }
                if (keys.size > storage.fields[field].map!!.maxKeys) null else keys.toList()
            }

        /** [storage] with a field for each of [keys]; a map split into its keys is hidden. */
        private fun withMapKeys(storage: Schema, keys: Array<List<String>?>): Schema {
            if (keys.all { it == null }) return storage
            val fields = ArrayList(storage.fields)
            for ((field, mapKeys) in keys.withIndex()) {
                if (mapKeys == null) continue
                val map = storage.fields[field]
                val syntax = map.map!!
                fields[field] = map.copy(hidden = true)
                for (key in mapKeys.filter(syntax::splits)) {
                    val base = syntax.fieldName(map.name, key)
                    val name = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base $it" })
                        .first { candidate -> fields.none { it.name.equals(candidate, ignoreCase = true) } }
                    fields += Field(name, ColumnType.SPAN, mapKey = MapKey(field, key), json = syntax.json)
                }
            }
            return Schema(fields)
        }

        /** [schema] with optional fields that no record has hidden. */
        private fun withoutAbsent(schema: Schema, segments: List<Columns>): Schema {
            val absent = schema.fields.indices.filter { field ->
                schema.fields[field].optional && segments.none { field < it.present.size && it.present[field] }
            }
            if (absent.isEmpty()) return schema
            return Schema(schema.fields.mapIndexed { i, field -> if (i in absent) field.copy(hidden = true) else field })
        }

        private fun Schema.asSpan(field: Int) = Schema(
            fields.mapIndexed { i, f -> if (i == field) f.copy(type = ColumnType.SPAN) else f },
        )

        /** The start of the first record at or after [from]; [end] if there is none. */
        private fun resync(channel: FileChannel, from: Long, end: Long, parser: CompiledParser): Long {
            val matcher = parser.newMatcher()
            // Start one byte early: the first line read is then only the rest of a line, and skipped.
            val reader = LineReader(channel, from - 1, end, RESYNC_BUFFER_SIZE)
            if (!reader.next()) return end
            while (reader.next()) {
                val starts = if (parser.multiLine) {
                    matcher.match(reader.buffer, reader.lineFrom, reader.lineTo)
                } else {
                    !isBlank(reader.buffer, reader.lineFrom, reader.lineTo)
                }
                if (starts) return reader.lineStart
            }
            return end
        }

        private fun parseRange(
            channel: FileChannel,
            from: Long,
            to: Long,
            parser: CompiledParser,
            out: Columns,
            hasPrevious: Boolean,
            check: () -> Unit,
            progress: IndexProgress?,
        ): RangeResult {
            val reader = LineReader(channel, from, to, BUFFER_SIZE) { progress?.add(it.toLong()) }
            val matcher = parser.newMatcher()
            val multiLine = parser.multiLine
            val messageField = out.schema.indexOf(Role.MESSAGE)
            var inRecord = hasPrevious
            var tail = Tail(from, lastLineComplete = true, lastLineStartedRecord = false)
            var lines = 0
            while (reader.next()) {
                if (++lines and 1023 == 0) check()
                val matched = matcher.match(reader.buffer, reader.lineFrom, reader.lineTo)
                // Without multi-line records, only blank lines continue one.
                val startsRecord = matched || !inRecord || (!multiLine && !isBlank(reader.buffer, reader.lineFrom, reader.lineTo))
                if (matched) {
                    out.beginRecord(reader.lineStart)
                    matcher.write(out)
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
                        local.idsIn(global[field]!!)
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

        private fun isBlank(bytes: ByteArray, from: Int, to: Int): Boolean {
            for (i in from..<to) if (!Json.isSpace(bytes[i])) return false
            return true
        }

        /**
         * The whole text of [field] of the record at [local] in [columns]: all its lines, not cut. Dictionary
         * ids are looked up with [dictionaryText]; [read] reads the record's bytes from where it starts.
         */
        internal fun fieldText(
            schema: Schema,
            columns: Columns,
            local: Int,
            field: Int,
            parser: CompiledParser,
            dictionaryText: (Int) -> String,
            read: (start: Long) -> ByteArray,
        ): String {
            val key = schema.fields[field].mapKey
            if (key == null) columns.valueText(field, local, parser, dictionaryText)?.let { return it }
            val bytes = read(columns.starts[local])
            if (key != null) return mapValue(columns, local, key, bytes, Int.MAX_VALUE - 2)
            val length = columns.spanLength(field, local)
            if (length == Columns.ABSENT_LENGTH) return ""
            val relative = minOf(columns.ints[field]!![local], bytes.size)
            if (length == Columns.TO_RECORD_END) return String(bytes, relative, Bytes.trimLineEnd(bytes, relative, bytes.size) - relative, Charsets.UTF_8)
            val end = minOf(relative + length, bytes.size)
            if (schema.fields[field].json) return String(Json.decode(bytes, relative, end), Charsets.UTF_8)
            return String(bytes, relative, end - relative, Charsets.UTF_8)
        }

        /** The value of [key] in its map, as stored in [columns], cut to its first line and at most [maxBytes]. */
        private fun mapValue(columns: Columns, local: Int, key: MapKey, bytes: ByteArray, maxBytes: Int): String {
            val length = columns.spanLength(key.field, local)
            if (length == Columns.ABSENT_LENGTH) return ""
            val from = columns.ints[key.field]!![local]
            val to = if (length == Columns.TO_RECORD_END) bytes.size else minOf(from + length, bytes.size)
            if (from >= to) return ""
            val map = columns.schema.fields[key.field].map!!
            val entries = map.entries(bytes, from, to)
            if (entries < 0) return ""
            val value = map.value(bytes, MdcMap.from(entries), MdcMap.to(entries), key)
            if (value < 0) return ""
            if (!map.json) return Bytes.utf8Text(bytes, MdcMap.from(value), MdcMap.to(value), maxBytes)
            val decoded = Json.decode(bytes, MdcMap.from(value), MdcMap.to(value))
            return firstLine(decoded, 0, decoded.size, maxBytes)
        }

        /** The first line of `bytes[from, to)`, cut at [maxBytes]. */
        private fun firstLine(bytes: ByteArray, from: Int, to: Int, maxBytes: Int): String {
            var end = from
            while (end < to && bytes[end] != Bytes.LF) end++
            return Bytes.utf8Text(bytes, from, Bytes.trimLineEnd(bytes, from, end), maxBytes)
        }
    }
}
