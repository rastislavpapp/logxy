package io.github.rastislavpapp.logxy.core.merge

import io.github.rastislavpapp.logxy.core.archive.ArchiveOrigin
import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.index.ByteChunkList
import io.github.rastislavpapp.logxy.core.index.IntChunkList
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Several logs as one, their records interleaved by timestamp. Each log keeps its own order: the merge
 * takes the earliest next record of any log, so a log's slightly out-of-order lines stay as written.
 * A record without timestamp counts as the time of the record before it. Records appended later are
 * merged among themselves and added at the end, so shown records never move.
 *
 * Every part must have a timestamp field. Not thread-safe, like [LogData]; filters read it through
 * [MergedFilter].
 */
class MergedLog private constructor(
    val parts: List<LogData>,
    /** Shown in the source field: file names, with as much of the path as needed to tell them apart. */
    val names: List<String>,
) : Records {

    private var partSchemas = parts.map { it.schema }
    private var merged = MergedSchema(partSchemas)
    private val timestampFields = IntArray(parts.size) { parts[it].schema.indexOf(Role.TIMESTAMP) }

    // Per merged record: its part and its record there. Per part: where each of its records is.
    private val partOf = ByteChunkList()
    private val recordOf = IntChunkList()
    private val positions = List(parts.size) { IntChunkList() }

    /** Per part, the time of its last merged record, for records without one. */
    private val lastTimes = LongArray(parts.size) { Long.MIN_VALUE }

    init {
        require(parts.size in 2..MAX_PARTS) { "Can merge 2 to $MAX_PARTS logs, not ${parts.size}" }
        require(timestampFields.all { it >= 0 }) { "Every merged log needs a timestamp" }
    }

    override val schema: Schema get() = merged.schema
    override val isRaw get() = false
    override val recordCount get() = partOf.size

    /** Bytes of all parts covered. */
    val indexedSize: Long get() = parts.sumOf { it.indexedSize }

    /** The part (index into the merged files) whose record is shown at [position]. */
    fun part(position: Int): Int = partOf[position].toInt() and 0xFF

    /** The record number, within its [part], of the record shown at [position]. */
    fun record(position: Int): Int = recordOf[position]

    /** Where [record] of [part] is shown; it must have been merged. */
    fun position(part: Int, record: Int): Int = positions[part][record]

    /** How many records of [part] are merged so far. */
    fun mergedCount(part: Int): Int = positions[part].size

    /** The first record of [part] shown at [position] or later; [mergedCount] if none. */
    fun firstRecordFrom(part: Int, position: Int): Int {
        val list = positions[part]
        var low = 0
        var high = list.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (list[middle] < position) low = middle + 1 else high = middle
        }
        return low
    }

    /** The field of [part] shown in merged [field], or -1 if the part has none there. */
    internal fun partField(part: Int, field: Int) = merged.partField(part, field)

    override fun level(record: Int): Level? = parts[part(record)].level(record(record))

    override fun timestampNanos(record: Int): Long = parts[part(record)].timestampNanos(record(record))

    override fun rowValues(record: Int, maxBytes: Int): Array<String> {
        val part = part(record)
        val values = parts[part].rowValues(record(record), maxBytes)
        return Array(schema.fields.size) { field ->
            if (field == MergedSchema.SOURCE) return@Array names[part]
            val partField = merged.partField(part, field)
            if (partField < 0) "" else values[partField]
        }
    }

    override fun recordText(record: Int, maxBytes: Int): String =
        parts[part(record)].recordText(record(record), maxBytes)

    override fun fieldText(record: Int, field: Int): String {
        val part = part(record)
        if (field == MergedSchema.SOURCE) return names[part]
        val partField = merged.partField(part, field)
        return if (partField < 0) "" else parts[part].fieldText(record(record), partField)
    }

    override fun sourceText(record: Int): String = parts[part(record)].sourceText(record(record))

    /**
     * Reads [field] of the [count] merged records in [records] (null: the first [count]) by their index there,
     * on any thread. Call on the owner thread, which resolves where each record is.
     */
    internal fun fieldReader(field: Int, records: IntArray?, count: Int): (Int) -> String {
        val recordParts = ByteArray(count) { partOf[records?.get(it) ?: it] }
        val partRecords = IntArray(count) { recordOf[records?.get(it) ?: it] }
        val snapshots = parts.map { it.filterSnapshot() }
        val partFields = IntArray(parts.size) { merged.partField(it, field) }
        val names = names
        return { i ->
            val part = recordParts[i].toInt() and 0xFF
            when {
                field == MergedSchema.SOURCE -> names[part]
                partFields[part] < 0 -> ""
                else -> snapshots[part].fieldText(partRecords[i], partFields[part])
            }
        }
    }

    /**
     * Merges the records the parts got since the last merge and adds them at the end. Returns false if
     * a part now has fewer records than were merged (its last record was parsed differently); then the
     * merge cannot be continued and must be built again. Call on the owner thread after appending; the
     * merge itself runs on [Dispatchers.Default].
     */
    suspend fun appendNew(): Boolean {
        if (parts.indices.any { parts[it].recordCount < mergedCount(it) }) return false
        // A part shows a field now that its appended records are the first to have; fields keep their places.
        if (parts.indices.any { parts[it].schema !== partSchemas[it] }) {
            partSchemas = parts.map { it.schema }
            merged = MergedSchema(partSchemas)
        }
        val snapshots = parts.map { it.filterSnapshot() }
        add(withContext(Dispatchers.Default) { order(snapshots) })
        return true
    }

    /** The part of each record merged next, in merged order, and the parts' last times after them. */
    private class Order(val parts: ByteArray, val lastTimes: LongArray)

    /** Merges the records of [snapshots] beyond those merged so far, without changing this log. */
    private fun order(snapshots: List<RecordSnapshot>): Order {
        val count = parts.size
        val cursors = snapshots.map { it.cursor() }
        val next = IntArray(count) { mergedCount(it) }
        val times = lastTimes.copyOf()
        val heads = LongArray(count)
        val done = BooleanArray(count)
        val order = ByteArray((0..<count).sumOf { snapshots[it].count - next[it] })

        fun loadHead(part: Int) {
            if (next[part] >= snapshots[part].count) {
                done[part] = true
                return
            }
            val cursor = cursors[part]
            cursor.moveTo(next[part])
            val time = cursor.timestamp(timestampFields[part])
            heads[part] = if (time == Columns.ABSENT_TIMESTAMP) times[part] else time
        }

        for (part in 0..<count) loadHead(part)
        for (i in order.indices) {
            // Few parts: a linear scan beats a heap. Ties go to the earlier part.
            var best = -1
            for (part in 0..<count) {
                if (!done[part] && (best < 0 || heads[part] < heads[best])) best = part
            }
            order[i] = best.toByte()
            times[best] = heads[best]
            next[best]++
            loadHead(best)
        }
        return Order(order, times)
    }

    private fun add(order: Order) {
        val next = IntArray(parts.size) { mergedCount(it) }
        for (b in order.parts) {
            val part = b.toInt() and 0xFF
            positions[part].add(partOf.size)
            partOf.add(b)
            recordOf.add(next[part]++)
        }
        order.lastTimes.copyInto(lastTimes)
    }

    companion object {
        /** Parts are stored as unsigned bytes. */
        const val MAX_PARTS = 255

        /**
         * Merges [parts], which must all have a timestamp field. Call on the thread that owns them; the
         * merge itself runs on [Dispatchers.Default].
         */
        suspend fun build(parts: List<LogData>, names: List<String> = sourceNames(parts.map { it.path })): MergedLog {
            val log = MergedLog(parts, names)
            val snapshots = parts.map { it.filterSnapshot() }
            withContext(Dispatchers.Default) { log.add(log.order(snapshots)) }
            return log
        }

        /**
         * File names, prefixed with as many parent folders as needed to make them unique. A file extracted
         * from a zip (see [origin]) is named by the zip and the file, e.g. `logs.zip/app.log`, with folders
         * inside the zip and then outside it added as needed. A decompressed file is named like its .gz or .zst.
         */
        fun sourceNames(paths: List<Path>, origin: (Path) -> ArchiveOrigin? = { null }): List<String> {
            val names = paths.map { path ->
                val from = origin(path)
                when {
                    from == null -> Name(emptyList(), path.map { it.toString() })
                    from.entry.isEmpty() -> Name(emptyList(), from.container.map { it.toString() })
                    else -> Name(from.container.map { it.toString() }, from.entry.split('/'))
                }
            }
            var depth = 1
            while (true) {
                val shown = names.map { it.shown(depth) }
                if (shown.toSet().size == shown.size || depth >= names.maxOf { it.depth }) return shown
                depth++
            }
        }

        /** A file's path: [zip] (empty if none) and the path in it, or the file's path. */
        private class Name(val zip: List<String>, val path: List<String>) {
            val depth get() = zip.size + path.size

            /** The last [depth] parts, the zip's name always among them. */
            fun shown(depth: Int): String {
                if (zip.isEmpty()) return path.takeLast(depth).joinToString("/")
                val inZip = path.takeLast(depth)
                return (zip.takeLast(1 + maxOf(0, depth - path.size)) + inZip).joinToString("/")
            }
        }
    }
}
