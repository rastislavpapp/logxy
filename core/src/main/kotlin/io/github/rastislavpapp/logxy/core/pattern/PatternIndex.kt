package io.github.rastislavpapp.logxy.core.pattern

import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.index.IntChunkList
import io.github.rastislavpapp.logxy.core.log.ByteDictionary
import io.github.rastislavpapp.logxy.core.log.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The template of each record's message (see [Templates]) of one log, as an id per record. Built when
 * first needed and extended as the log grows. [update] and the other suspending calls may run on any
 * thread; [id] is readable from any thread for records an update covered.
 */
internal class PatternIndex(private val maxTemplates: Int = MAX_TEMPLATES, private val chunkSize: Int = CHUNK_SIZE) {
    private val mutex = Mutex()
    private val ids = IntChunkList()
    private val templates = ByteDictionary()
    private var coveredEnd = -1L

    private val doneChunks = AtomicInteger()

    @Volatile
    private var totalChunks = 0

    /** How far a running [update] got, from 0 to 1. */
    val progress: Double get() = totalChunks.let { if (it == 0) 1.0 else doneChunks.get().toDouble() / it }

    /** The template id of [record]; [OTHER] beyond [maxTemplates] distinct templates. */
    fun id(record: Int): Int = ids[record]

    /** Gives every record of [snapshot] its template id. */
    suspend fun update(snapshot: RecordSnapshot) = mutex.withLock { updateLocked(snapshot) }

    /** Updates to [snapshot], then tells for each template id whether its template is one of [texts]. */
    suspend fun matching(snapshot: RecordSnapshot, texts: Set<String>): BooleanArray = mutex.withLock {
        updateLocked(snapshot)
        val matching = BooleanArray(templates.size)
        for (text in texts) {
            val bytes = text.toByteArray()
            val id = templates.find(bytes, 0, bytes.size)
            if (id >= 0) matching[id] = true
        }
        matching
    }

    /** Templates by id. */
    suspend fun texts(): List<String> = mutex.withLock { List(templates.size) { templates.text(it) } }

    private suspend fun updateLocked(snapshot: RecordSnapshot) = withContext(Dispatchers.Default) {
        val count = snapshot.count
        if (count == ids.size && snapshot.end == coveredEnd) return@withContext
        // The last record covered may have grown since.
        val from = maxOf(0, ids.size - 1)
        val field = snapshot.schema.indexOf(Role.MESSAGE)
        val starts = (from..<count step chunkSize).toList()
        doneChunks.set(0)
        totalChunks = starts.size
        // Chunks are added in order as soon as they and all before them are scanned, so a cancelled update keeps them.
        val scanned = arrayOfNulls<Pair<ByteDictionary, IntArray>>(starts.size)
        var next = 0
        try {
            coroutineScope {
                for ((task, start) in starts.withIndex()) {
                    launch {
                        ensureActive()
                        val chunk = scan(snapshot, field, task, start, minOf(count, start + chunkSize))
                        doneChunks.incrementAndGet()
                        synchronized(scanned) {
                            scanned[task] = chunk
                            while (next < starts.size) {
                                val (local, localIds) = scanned[next] ?: break
                                scanned[next] = null
                                add(starts[next++], local, localIds)
                            }
                        }
                    }
                }
            }
            coveredEnd = snapshot.end
        } finally {
            totalChunks = 0
        }
    }

    /** Gives the records from [start] on the templates [localIds] refer to in [local]. */
    private fun add(start: Int, local: ByteDictionary, localIds: IntArray) {
        val global = IntArray(local.size) { id ->
            val key = local.key(id)
            val found = templates.find(key, 0, key.size)
            when {
                found >= 0 -> found
                templates.size < maxTemplates -> templates.idOf(key, 0, key.size)
                else -> OTHER
            }
        }
        for (i in localIds.indices) {
            val record = start + i
            if (record < ids.size) ids[record] = global[localIds[i]] else ids.add(global[localIds[i]])
        }
    }

    /** Templates of the records `[from, to)`: a dictionary of them and the id there of each record. */
    private fun scan(
        snapshot: RecordSnapshot,
        field: Int,
        task: Int,
        from: Int,
        to: Int,
    ): Pair<ByteDictionary, IntArray> {
        val dictionary = ByteDictionary()
        val reader = MessageReader(snapshot, field, snapshot.channels.forTask(task))
        val out = ByteArray(Templates.OUT_SIZE)
        val ids = IntArray(to - from) { i -> dictionary.idOf(out, 0, reader.template(from + i, out)) }
        return dictionary to ids
    }

    companion object {
        /**
         * Records over this many distinct templates share [OTHER]; messages without variable parts can make no end
         * of them.
         */
        const val MAX_TEMPLATES = 100_000

        /** The id of records whose template did not fit under [MAX_TEMPLATES]. */
        const val OTHER = -1

        private const val CHUNK_SIZE = 1 shl 16

        /** The template of [record]'s message, read on the calling thread. */
        fun templateOf(snapshot: RecordSnapshot, record: Int): String {
            val out = ByteArray(Templates.OUT_SIZE)
            val reader = MessageReader(snapshot, snapshot.schema.indexOf(Role.MESSAGE), snapshot.channels.main)
            val length = reader.template(record, out)
            return String(out, 0, length, Charsets.UTF_8)
        }
    }
}
