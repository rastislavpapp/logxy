package io.github.rastislavpapp.logxy.core.trace

import io.github.rastislavpapp.logxy.core.filter.RecordCursor
import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.filter.SpanReader
import io.github.rastislavpapp.logxy.core.filter.mapValue
import io.github.rastislavpapp.logxy.core.index.IntChunkList
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
import io.github.rastislavpapp.logxy.core.template.TemplateIndex
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
 * [templates] are the files' message template indexes, which name the steps; null for records without a message,
 * which have no steps.
 */
internal class TraceSource(
    val snapshots: List<RecordSnapshot>,
    val count: Int,
    val origin: RowOrigin?,
    val fields: IntArray,
    val shifts: LongArray,
    val templates: List<TemplateIndex>?,
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

    /** The template indexes a running [update] brings up to date first, before it reads the records. */
    @Volatile
    private var updatingTemplates: List<TemplateIndex>? = null

    /** How far a running [update] got, from 0 to 1. */
    val progress: Double
        get() {
            val templates = updatingTemplates
            if (templates != null) return templates.sumOf { it.progress } / templates.size
            val total = total
            return if (total == 0) 1.0 else done.get().toDouble() / total
        }

    /** What the index holds; replaced when the level mapping changes, as the worst levels are then wrong. */
    private inner class State(val levels: List<LevelMapping>? = null) {
        val keys = TraceKeys()

        /** Per record read, its trace, -1 for none and for the pending records, which the index has not counted. */
        val traceOf = IntChunkList()
        val traces = Aggregates(sourceNames.size, traceOf)

        /** How many of the session's records were read. */
        var covered = 0

        /** The records read that were the last of their file then, which count only in the traces given. */
        var pending = IntArray(0)

        /** Per file, the trace of each dictionary id of its trace id field; -1 where not known yet. */
        val dictionaryTraces = Array(sourceNames.size.coerceAtLeast(1)) { IntArray(0) }

        /** Per file, the session-wide template id of each of the file's own template ids; see [templateTexts]. */
        val templateIds = Array(sourceNames.size.coerceAtLeast(1)) { IntArray(0) }

        /** The message templates by session-wide id, as the files number theirs each their own way. */
        val templateTexts = ArrayList<String>()
        val templateNumbers = HashMap<String, Int>()
    }

    /** Brings [templates], the files' template indexes, up to date, and gives their new templates session-wide ids. */
    private suspend fun updateTemplates(templates: List<TemplateIndex>, snapshots: List<RecordSnapshot>) {
        updatingTemplates = templates
        try {
            for ((part, index) in templates.withIndex()) {
                index.update(snapshots[part])
                val known = state.templateIds[part]
                val texts = index.texts(from = known.size)
                val ids = known.copyOf(known.size + texts.size)
                for ((i, text) in texts.withIndex()) {
                    ids[known.size + i] = state.templateNumbers.getOrPut(text) {
                        state.templateTexts += text
                        state.templateTexts.size - 1
                    }
                }
                state.templateIds[part] = ids
            }
        } finally {
            updatingTemplates = null
        }
    }

    /**
     * Reads the records of [source] not read yet, and gives the traces of all of them. Cancelling it keeps what it
     * read.
     */
    suspend fun update(source: TraceSource): Traces = mutex.withLock {
        withContext(Dispatchers.Default) {
            val levels = source.snapshots.map { it.levels }
            if (state.levels != levels) state = State(levels)
            source.templates?.let { updateTemplates(it, source.snapshots) }
            val readers = source.snapshots.indices.map { PartReader(source, it, state) }
            fun isLast(position: Int) = source.record(position) == source.snapshots[source.part(position)].count - 1
            val readerAt = { position: Int -> readers.at(source, position) }
            fun commit(position: Int) {
                val reader = readerAt(position)
                val trace = reader.trace(create = true)
                if (trace >= 0) state.traces.add(trace, position, reader, readerAt)
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
                        state.traceOf.add(-1)
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
        val readerAt = { position: Int -> readers.at(source, position) }
        for (position in state.pending) {
            val reader = readerAt(position)
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
            if (trace >= 0) traces.add(trace, position, reader, readerAt)
        }
        val templates = if (source.templates == null) null else state.templateTexts.toList()
        return traces.toTraces(column, keys, newKeys, sourceNames, templates)
    }

    /** The reader of the file of the session's record at [position] of [source], moved to that record. */
    private fun List<PartReader>.at(source: TraceSource, position: Int): PartReader {
        val reader = this[source.part(position)]
        reader.moveTo(source.record(position))
        return reader
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

        private val templates = source.templates?.get(part)

        val sourcePart get() = part

        fun moveTo(record: Int) = cursor.moveTo(record)

        /**
         * The record's message template as a session-wide id, -1 past the templates its file keeps; [NO_TEMPLATES]
         * if the records have no message, and so no steps.
         */
        fun template(): Int {
            val index = templates ?: return NO_TEMPLATES
            val id = index.id(cursor.record)
            // The filter may have updated the index since, giving a file's growing last record a template not mapped
            // yet: it counts with the records past those kept until the next update maps it.
            return if (id < 0) -1 else state.templateIds[part].getOrElse(id) { -1 }
        }

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

    /**
     * Per trace, what its records come to so far, in arrays grown as traces are added, and its steps. [traceOf] is
     * the index's own list of each record's trace. The index's aggregates fill it in; a [copy], which counts the
     * pending records in for the traces given, keeps their traces to itself, as the traces given before read the
     * list on other threads.
     */
    private class Aggregates(
        private val parts: Int,
        private val traceOf: IntChunkList,
        private val isCopy: Boolean = false,
    ) {
        private val words = if (parts == 0) 0 else (parts + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        var size = 0
            private set
        private var starts = LongArray(INITIAL_TRACES)
        private var ends = LongArray(INITIAL_TRACES)
        private var counts = IntArray(INITIAL_TRACES)
        private var levels = ByteArray(INITIAL_TRACES)
        private var firsts = IntArray(INITIAL_TRACES)
        private var lasts = IntArray(INITIAL_TRACES)
        private var sources = LongArray(INITIAL_TRACES * words)

        // Per trace, the record with a time that is last among those counted, its time and its template: the next
        // step starts there.
        private var stepPositions = IntArray(INITIAL_TRACES)
        private var stepTimes = LongArray(INITIAL_TRACES)
        private var stepTemplates = IntArray(INITIAL_TRACES)
        private var steps = StepCounter()

        /** In a copy, the pending records it counted, each as its position followed by its trace. */
        private var pendingTraces = IntArray(0)

        /** The trace of the record at [position], -1 if it has none or is not counted here. */
        private fun traceAt(position: Int): Int {
            for (i in pendingTraces.indices step 2) if (pendingTraces[i] == position) return pendingTraces[i + 1]
            return traceOf[position]
        }

        /**
         * Counts the record at [position] in [trace], the next new one or an existing one; [reader] is at it.
         * [readerAt] moves a reader to a record counted before, for a record that comes before others of its trace.
         */
        fun add(trace: Int, position: Int, reader: PartReader, readerAt: (Int) -> PartReader) {
            if (trace == size) open()
            if (isCopy) pendingTraces += intArrayOf(position, trace) else traceOf[position] = trace
            val time = reader.time()
            val template = reader.template()
            counts[trace]++
            levels[trace] = maxOf(levels[trace], reader.severity())
            firsts[trace] = minOf(firsts[trace], position)
            lasts[trace] = maxOf(lasts[trace], position)
            if (words > 0) {
                val part = reader.sourcePart
                sources[trace * words + part / Long.SIZE_BITS] =
                    sources[trace * words + part / Long.SIZE_BITS] or (1L shl part)
            }
            if (time == Records.NO_TIMESTAMP) return
            if (starts[trace] == Records.NO_TIMESTAMP || time < starts[trace]) starts[trace] = time
            if (ends[trace] == Records.NO_TIMESTAMP || time > ends[trace]) ends[trace] = time
            if (template == NO_TEMPLATES) return
            when {
                stepPositions[trace] < 0 -> {}
                stepPositions[trace] < position ->
                    steps.add(stepTemplates[trace], template, time - stepTimes[trace], trace)
                else -> {
                    insertStep(trace, position, time, template, readerAt)
                    return
                }
            }
            stepPositions[trace] = position
            stepTimes[trace] = time
            stepTemplates[trace] = template
        }

        /**
         * Splits the step of [trace] that spans [position], a record counted after later ones of its trace, such as
         * a merged file's last record, into the step to it and the step from it.
         */
        private fun insertStep(trace: Int, position: Int, time: Long, template: Int, readerAt: (Int) -> PartReader) {
            fun hasStep(record: Int) = traceAt(record) == trace && readerAt(record).time() != Records.NO_TIMESTAMP
            var after = position + 1
            while (!hasStep(after)) after++
            val afterReader = readerAt(after)
            val afterTime = afterReader.time()
            val afterTemplate = afterReader.template()
            var before = position - 1
            while (before >= firsts[trace] && !hasStep(before)) before--
            if (before >= firsts[trace]) {
                val beforeReader = readerAt(before)
                val beforeTime = beforeReader.time()
                val beforeTemplate = beforeReader.template()
                steps.remove(beforeTemplate, afterTemplate, afterTime - beforeTime, trace)
                steps.add(beforeTemplate, template, time - beforeTime, trace)
            }
            steps.add(template, afterTemplate, afterTime - time, trace)
        }

        private fun open() {
            if (size == counts.size) {
                val capacity = size * 2
                starts = starts.copyOf(capacity)
                ends = ends.copyOf(capacity)
                counts = counts.copyOf(capacity)
                levels = levels.copyOf(capacity)
                firsts = firsts.copyOf(capacity)
                lasts = lasts.copyOf(capacity)
                sources = sources.copyOf(capacity * words)
                stepPositions = stepPositions.copyOf(capacity)
                stepTimes = stepTimes.copyOf(capacity)
                stepTemplates = stepTemplates.copyOf(capacity)
            }
            starts[size] = Records.NO_TIMESTAMP
            ends[size] = Records.NO_TIMESTAMP
            counts[size] = 0
            levels[size] = Traces.NO_LEVEL
            firsts[size] = Int.MAX_VALUE
            lasts[size] = -1
            stepPositions[size] = -1
            size++
        }

        fun copy(): Aggregates = Aggregates(parts, traceOf, isCopy = true).also {
            it.size = size
            it.starts = starts.copyOf(maxOf(INITIAL_TRACES, size))
            it.ends = ends.copyOf(it.starts.size)
            it.counts = counts.copyOf(it.starts.size)
            it.levels = levels.copyOf(it.starts.size)
            it.firsts = firsts.copyOf(it.starts.size)
            it.lasts = lasts.copyOf(it.starts.size)
            it.sources = sources.copyOf(it.starts.size * words)
            it.stepPositions = stepPositions.copyOf(it.starts.size)
            it.stepTimes = stepTimes.copyOf(it.starts.size)
            it.stepTemplates = stepTemplates.copyOf(it.starts.size)
            it.steps = steps.copy()
        }

        /** [templates] are the texts of the session-wide template ids, null if the records have no steps. */
        fun toTraces(
            column: String,
            keys: KeyView,
            newKeys: List<ByteArray>,
            sourceNames: List<String>,
            templates: List<String>?,
        ) = Traces(
            column,
            keys,
            newKeys,
            starts.copyOf(size),
            ends.copyOf(size),
            counts.copyOf(size),
            levels.copyOf(size),
            firsts.copyOf(size),
            lasts.copyOf(size),
            traceOf,
            pendingTraces.copyOf(),
            sources.copyOf(size * words),
            sourceNames,
            templates?.let(steps::toSteps),
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

        /** What [PartReader.template] gives for records without a message. */
        const val NO_TEMPLATES = -2
    }
}
