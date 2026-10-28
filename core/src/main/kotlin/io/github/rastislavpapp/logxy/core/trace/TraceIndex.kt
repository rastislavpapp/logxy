package io.github.rastislavpapp.logxy.core.trace

import io.github.rastislavpapp.logxy.core.filter.RecordCursor
import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.filter.SpanReader
import io.github.rastislavpapp.logxy.core.filter.mapValue
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Encoding
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.parse.Csv
import io.github.rastislavpapp.logxy.core.parse.Json
import io.github.rastislavpapp.logxy.core.parse.MdcMap
import io.github.rastislavpapp.logxy.core.template.RowOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The records of a session as [update] reads them, taken on the thread that owns them: the first [count] records,
 * whose files' snapshots are [snapshots]. [origin] gives the file and record of each, null for a single file. Per
 * file, [fields] is its field holding the trace ids, -1 if it has none, and [shifts] its timestamp shift.
 */
internal class TraceSource(
    val snapshots: List<RecordSnapshot>,
    val count: Int,
    val origin: RowOrigin?,
    val fields: IntArray,
    val shifts: LongArray,
) {
    /** The file, by its index among [snapshots], that the record at [position] of the session comes from. */
    fun part(position: Int): Int = origin?.part(position) ?: 0

    /** The record number, within its file, of the record at [position] of the session. */
    fun record(position: Int): Int = origin?.record(position) ?: position
}

/**
 * The traces of one load's records by the trace ids in [column], kept and extended as records are appended: each
 * [update] reads only the records it has not read. [sourceNames] are a merge's file names, empty for a single file.
 *
 * The last record of a file may still change, its first line being unfinished, so it counts only in the [Traces]
 * an update gives, until a record follows it. [update] may run on any thread, one at a time.
 */
internal class TraceIndex(private val column: String, private val sourceNames: List<String>) {
    private val mutex = Mutex()
    private var state = State()

    private val done = AtomicInteger()

    @Volatile
    private var total = 0

    /** How far a running [update] got, from 0 to 1. */
    val progress: Double get() = total.let { if (it == 0) 1.0 else done.get().toDouble() / it }

    /** What the index holds; replaced when the level mapping changes, as the worst levels are then wrong. */
    private inner class State(val levels: List<LevelMapping>? = null) {
        val keys = TraceKeys()
        val traces = Aggregates(sourceNames.size)

        /** How many of the session's records were read. */
        var covered = 0

        /** The records read that were the last of their file then, which count only in the traces given. */
        var pending = IntArray(0)

        /** Per file, the trace of each dictionary id of its trace id field; -1 where not known yet. */
        val dictionaryTraces = Array(sourceNames.size.coerceAtLeast(1)) { IntArray(0) }
    }

    /**
     * Reads the records of [source] not read yet, and gives the traces of all of them. Cancelling it keeps what it
     * read.
     */
    suspend fun update(source: TraceSource): Traces = mutex.withLock {
        withContext(Dispatchers.Default) {
            val levels = source.snapshots.map { it.levels }
            if (state.levels != levels) state = State(levels)
            val readers = source.snapshots.indices.map { PartReader(source, it, state) }
            fun isLast(position: Int) = source.record(position) == source.snapshots[source.part(position)].count - 1
            fun commit(position: Int) {
                val reader = readers[source.part(position)]
                reader.moveTo(source.record(position))
                val trace = reader.trace(create = true)
                if (trace >= 0) state.traces.add(trace, position, reader)
            }
            val stillPending = state.pending.filter(::isLast)
            state.pending.filterNot(::isLast).forEach(::commit)
            state.pending = stillPending.toIntArray()
            done.set(0)
            total = source.count - state.covered
            try {
                while (state.covered < source.count) {
                    ensureActive()
                    val to = minOf(source.count, state.covered + BATCH)
                    val pending = ArrayList<Int>()
                    for (position in state.covered..<to) {
                        if (isLast(position)) pending += position else commit(position)
                    }
                    state.pending += pending
                    done.addAndGet(to - state.covered)
                    state.covered = to
                }
            } finally {
                total = 0
            }
            traces(state, readers, source)
        }
    }

    /** The traces of [state] with its pending records counted in, without changing it. */
    private fun traces(state: State, readers: List<PartReader>, source: TraceSource): Traces {
        val keys = state.keys.view()
        val traces = state.traces.copy()
        val newKeys = ArrayList<ByteArray>()
        for (position in state.pending) {
            val reader = readers[source.part(position)]
            reader.moveTo(source.record(position))
            var trace = reader.trace(create = false)
            if (trace == NOT_FOUND) {
                val key = reader.key()
                var index = newKeys.indexOfFirst { it.contentEquals(key) }
                if (index < 0) {
                    index = newKeys.size
                    newKeys += key
                }
                trace = keys.count + index
            }
            if (trace >= 0) traces.add(trace, position, reader)
        }
        return traces.toTraces(column, keys, newKeys, sourceNames)
    }

    /** Reads the trace id, time and level of one file's records. */
    private inner class PartReader(source: TraceSource, private val part: Int, private val state: State) {
        private val snapshot = source.snapshots[part]
        private val cursor: RecordCursor = snapshot.cursor()
        private val field = source.fields[part]
        private val shift = source.shifts[part]
        private val schemaField = snapshot.schema.fields.getOrNull(field)
        private val type = schemaField?.type
        private val mapKey = schemaField?.mapKey
        private val map = mapKey?.let { snapshot.schema.fields[it.field].map }
        private val spans = if (type == ColumnType.SPAN) spanReader() else null
        private val dictionary = if (type == ColumnType.DICT) snapshot.dictionaryTexts(field) else emptyList()

        private val timeField = fieldOf(Role.TIMESTAMP, ColumnType.TIMESTAMP)
        private val levelField = fieldOf(Role.LEVEL, ColumnType.LEVEL)
        private val severities = if (levelField < 0) {
            ByteArray(0)
        } else {
            val texts = snapshot.dictionaryTexts(levelField)
            ByteArray(texts.size) { Traces.severity(snapshot.levels.of(texts[it])) }
        }

        // The trace id read by readKey: `bytes[from, to)`.
        private var bytes = ByteArray(0)
        private var from = 0
        private var to = 0

        val sourcePart get() = part

        fun moveTo(record: Int) = cursor.moveTo(record)

        /** The record's time with the file's shift added, or [Records.NO_TIMESTAMP]. */
        fun time(): Long {
            if (timeField < 0) return Records.NO_TIMESTAMP
            val time = cursor.timestamp(timeField)
            return if (time == Columns.ABSENT_TIMESTAMP) Records.NO_TIMESTAMP else time + shift
        }

        /** The record's level as by [Traces.severity], or [Traces.NO_LEVEL]. */
        fun severity(): Byte {
            if (levelField < 0) return Traces.NO_LEVEL
            return severities.getOrElse(cursor.levelId(levelField)) { Traces.NO_LEVEL }
        }

        /**
         * The record's trace: [NONE] if it has no trace id, or, unless [create] adds it, [NOT_FOUND] if its trace id
         * is new.
         */
        fun trace(create: Boolean): Int {
            if (type == ColumnType.DICT && mapKey == null) {
                val id = cursor.dictId(field)
                if (id < 0) return NONE
                var traces = state.dictionaryTraces[part]
                if (id >= traces.size) {
                    traces = traces.copyOf(maxOf(dictionary.size, id + 1)).also { it.fill(-1, traces.size) }
                    state.dictionaryTraces[part] = traces
                }
                if (traces[id] >= 0) return traces[id]
                val key = dictionary[id].toByteArray()
                if (key.isEmpty()) return NONE
                if (!create) return found(state.keys.find(key, 0, key.size))
                return state.keys.numberOf(key, 0, key.size).also { traces[id] = it }
            }
            if (!readKey()) return NONE
            if (!create) return found(state.keys.find(bytes, from, to))
            return state.keys.numberOf(bytes, from, to)
        }

        /** [number] as found by [TraceKeys.find], or [NOT_FOUND] if it found none. */
        private fun found(number: Int) = if (number >= 0) number else NOT_FOUND

        /** The first field with [role], if it is stored as [type]; -1 if there is none. */
        private fun fieldOf(role: Role, type: ColumnType): Int {
            val field = snapshot.schema.indexOf(role)
            return if (field >= 0 && snapshot.schema.fields[field].type == type) field else -1
        }

        /** The trace id of the record [trace] found new. */
        fun key(): ByteArray {
            if (type == ColumnType.DICT && mapKey == null) return dictionary[cursor.dictId(field)].toByteArray()
            readKey()
            return bytes.copyOfRange(from, to)
        }

        /** Reads the record's trace id, as the table shows it, into [bytes]; false if it has none or it is empty. */
        private fun readKey(): Boolean {
            if (mapKey != null) {
                val reader = mapReader()
                val value = mapValue(cursor, reader, mapKey, map!!)
                if (value < 0) return false
                val encoding = if (map.json) Encoding.JSON else null
                return decoded(reader.buffer, MdcMap.from(value), MdcMap.to(value), encoding)
            }
            if (spans == null) return false
            val length = cursor.spanLength(field)
            if (length == Columns.ABSENT_LENGTH) return false
            val start = cursor.start + cursor.spanStart(field)
            val end = if (length == Columns.TO_RECORD_END) cursor.end else start + length
            val offset = spans.load(start, end)
            val loadedEnd = minOf(offset + (end - start).toInt(), spans.loaded)
            return decoded(spans.buffer, offset, loadedEnd, schemaField!!.encoding)
        }

        private var mapSpans: SpanReader? = null

        private fun mapReader(): SpanReader = mapSpans ?: spanReader().also { mapSpans = it }

        private fun spanReader() = SpanReader(snapshot.channels.forTask(part), snapshot.end)

        /**
         * Sets the trace id to `source[start, end)` as shown, decoding it as [encoding] says; a quoted value without
         * escapes is read in place. False if it is empty.
         */
        private fun decoded(source: ByteArray, start: Int, end: Int, encoding: Encoding?): Boolean {
            bytes = source
            from = start
            to = end
            when (encoding) {
                Encoding.JSON -> if (Json.isString(source, start, end) && Json.isPlain(source, start + 1, end - 1)) {
                    from = start + 1
                    to = end - 1
                } else {
                    whole(Json.decode(source, start, end))
                }
                Encoding.CSV -> if (Csv.isQuoted(source, start, end) && Csv.isPlain(source, start + 1, end - 1)) {
                    from = start + 1
                    to = end - 1
                } else {
                    whole(Csv.decode(source, start, end))
                }
                Encoding.PLAIN, null -> {}
            }
            return to > from
        }

        private fun whole(decoded: ByteArray) {
            bytes = decoded
            from = 0
            to = decoded.size
        }
    }

    /** Per trace, what its records come to so far, in arrays grown as traces are added. */
    private class Aggregates(private val parts: Int) {
        private val words = if (parts == 0) 0 else (parts + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        var size = 0
            private set
        private var starts = LongArray(INITIAL_TRACES)
        private var ends = LongArray(INITIAL_TRACES)
        private var counts = IntArray(INITIAL_TRACES)
        private var levels = ByteArray(INITIAL_TRACES)
        private var firsts = IntArray(INITIAL_TRACES)
        private var sources = LongArray(INITIAL_TRACES * words)

        /** Counts the record at [position] in [trace], the next new one or an existing one; [reader] is at it. */
        fun add(trace: Int, position: Int, reader: PartReader) {
            if (trace == size) open()
            val time = reader.time()
            if (time != Records.NO_TIMESTAMP) {
                if (starts[trace] == Records.NO_TIMESTAMP || time < starts[trace]) starts[trace] = time
                if (ends[trace] == Records.NO_TIMESTAMP || time > ends[trace]) ends[trace] = time
            }
            counts[trace]++
            levels[trace] = maxOf(levels[trace], reader.severity())
            firsts[trace] = minOf(firsts[trace], position)
            if (words > 0) {
                val part = reader.sourcePart
                sources[trace * words + part / Long.SIZE_BITS] =
                    sources[trace * words + part / Long.SIZE_BITS] or (1L shl part)
            }
        }

        private fun open() {
            if (size == counts.size) {
                val capacity = size * 2
                starts = starts.copyOf(capacity)
                ends = ends.copyOf(capacity)
                counts = counts.copyOf(capacity)
                levels = levels.copyOf(capacity)
                firsts = firsts.copyOf(capacity)
                sources = sources.copyOf(capacity * words)
            }
            starts[size] = Records.NO_TIMESTAMP
            ends[size] = Records.NO_TIMESTAMP
            counts[size] = 0
            levels[size] = Traces.NO_LEVEL
            firsts[size] = Int.MAX_VALUE
            size++
        }

        fun copy(): Aggregates = Aggregates(parts).also {
            it.size = size
            it.starts = starts.copyOf(maxOf(INITIAL_TRACES, size))
            it.ends = ends.copyOf(it.starts.size)
            it.counts = counts.copyOf(it.starts.size)
            it.levels = levels.copyOf(it.starts.size)
            it.firsts = firsts.copyOf(it.starts.size)
            it.sources = sources.copyOf(it.starts.size * words)
        }

        fun toTraces(column: String, keys: KeyView, newKeys: List<ByteArray>, sourceNames: List<String>) = Traces(
            column,
            keys,
            newKeys,
            starts.copyOf(size),
            ends.copyOf(size),
            counts.copyOf(size),
            levels.copyOf(size),
            firsts.copyOf(size),
            sources.copyOf(size * words),
            sourceNames,
        )
    }

    private companion object {
        /** Records read between checks for cancelling, and steps of the progress. */
        const val BATCH = 1 shl 16

        const val INITIAL_TRACES = 64

        /** A record without a trace id. */
        const val NONE = -1

        /** A record whose trace id has no trace yet. */
        const val NOT_FOUND = -2
    }
}
