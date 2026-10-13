package io.github.rastislavpapp.logxy.core.index

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.ReadChannels
import io.github.rastislavpapp.logxy.core.filter.LineSnapshot
import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.log.AppendedData
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.readFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path

/**
 * How a file is read when indexed or parsed; tests shrink the ranges to exercise them, a merge uses fewer read
 * channels per file.
 */
data class IndexOptions(
    /** The most ranges a file is split into, scanned in parallel. */
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    /** Files are only split into ranges of at least this size, so small files use one thread. */
    val minRangeSize: Long = 64L * 1024 * 1024,
    /** Bytes read per call into each range's buffer when indexing raw lines; parsing sizes its own. */
    val bufferSize: Int = 1024 * 1024,
    /** Channels per file where positional reads are serial (Windows); fewer when many files are open together. */
    val readChannels: Int = parallelism,
) {
    init {
        require(parallelism >= 1) { "parallelism must be at least 1" }
        require(minRangeSize >= 1) { "minRangeSize must be at least 1" }
        require(bufferSize >= 8) { "bufferSize must be at least 8" }
        require(readChannels >= 1) { "readChannels must be at least 1" }
    }

    /** How many ranges a file of [size] bytes is split into for scanning in parallel. */
    fun rangeCount(size: Long): Int = (size / minRangeSize).coerceIn(1, parallelism.toLong()).toInt()
}

/** Bytes `[from, to)` appended to a raw file, read off the UI thread, and the starts of the lines found in them. */
class AppendedLines internal constructor(
    internal val from: Long,
    internal val to: Long,
    internal val starts: LongChunkList,
) : AppendedData

/**
 * Start offsets of all lines in a file: the raw-text view of a log, one record per line. Lines end
 * with `\n` or `\r\n`; a trailing line without terminator counts as a line, a terminator at the
 * very end does not start an empty one.
 *
 * Holds the file open for reading line text until [close]d.
 */
class LineIndex private constructor(
    override val path: Path,
    private val channels: ReadChannels,
    private val starts: LongChunkList,
    indexedSize: Long,
    // Whether bytes appended next start a new line, i.e. the file is empty or ends with a newline.
    private var lastLineComplete: Boolean,
) : LogData() {

    override var indexedSize = indexedSize
        private set

    override val schema get() = Schema.RAW

    override val isRaw get() = true

    // Raw lines have no levels; kept only to satisfy LogData.
    override var levelMapping = LevelMapping.DEFAULT

    override fun level(record: Int): Level? = null

    override fun timestampNanos(record: Int): Long = Columns.ABSENT_TIMESTAMP

    val lineCount: Int get() = starts.size

    override val recordCount get() = starts.size

    override fun rowValues(record: Int, maxBytes: Int) = arrayOf(lineText(record, maxBytes))

    override fun filterSnapshot(): RecordSnapshot = LineSnapshot(lineCount, indexedSize, channels, starts)

    override fun recordText(record: Int, maxBytes: Int) = lineText(record, maxBytes)

    override fun fieldText(record: Int, field: Int) = lineText(record)

    override fun sourceText(record: Int): String {
        val bytes = ByteArray((lineLimit(record) - lineStart(record)).toInt())
        val length = channels.main.readFully(ByteBuffer.wrap(bytes), lineStart(record))
        return String(bytes, 0, length, Charsets.UTF_8)
    }

    fun lineStart(line: Int): Long = starts[line]

    /** Offset just past the line, including its terminator. */
    private fun lineLimit(line: Int): Long = if (line + 1 < starts.size) starts[line + 1] else indexedSize

    /**
     * Text of [line] without its terminator, decoded as UTF-8. Lines longer than [maxBytes] are cut
     * at a character boundary and end with `…`.
     */
    fun lineText(line: Int, maxBytes: Int = Int.MAX_VALUE - 2): String {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val start = lineStart(line)
        val fullLength = lineLimit(line) - start
        // Read two bytes past the limit to see whether only the terminator is beyond it.
        val bytes = ByteArray(minOf(fullLength, maxBytes + 2L).toInt())
        var length = channels.main.readFully(ByteBuffer.wrap(bytes), start)
        if (length.toLong() == fullLength) length = Bytes.trimLineEnd(bytes, 0, length)
        return Bytes.utf8Text(bytes, 0, length, maxBytes)
    }

    override fun readAppended(size: Long): AppendedLines {
        val from = indexedSize
        if (size <= from) return AppendedLines(from, from, LongChunkList())
        val scan = scanRange(channels.main, from..<size, APPEND_BUFFER_SIZE, checkCancelled = {}, progress = null)
        return AppendedLines(from, scan.end, scan.starts)
    }

    override fun append(data: AppendedData) {
        val appended = data as AppendedLines
        require(appended.from == indexedSize) { "Appended bytes start at ${appended.from}, index ends at $indexedSize" }
        if (appended.to == appended.from) return
        if (lastLineComplete) starts.add(appended.from)
        starts.addAll(appended.starts)
        lastLineComplete = starts.size > 0 && starts.last() == appended.to
        if (lastLineComplete) starts.removeLast()
        indexedSize = appended.to
    }

    override fun close() {
        channels.close()
    }

    companion object {

        /** Scans [path] on [Dispatchers.IO]; cancelling the calling coroutine stops the scan. */
        suspend fun build(
            path: Path,
            progress: IndexProgress = IndexProgress(),
            options: IndexOptions = IndexOptions(),
        ): LineIndex = withContext(Dispatchers.IO) {
            val channels = ReadChannels.open(path, options.readChannels)
            try {
                val size = channels.main.size()
                progress.totalBytes = size
                val ranges = splitIntoRanges(size, options)
                val scans = ranges.mapIndexed { i, range ->
                    async {
                        val job = coroutineContext.job
                        scanRange(channels.forTask(i), range, options.bufferSize, { job.ensureActive() }, progress)
                    }
                }.awaitAll()
                ensureActive()
                merge(path, channels, size, scans)
            } catch (e: Throwable) {
                channels.close()
                throw e
            }
        }

        private fun splitIntoRanges(size: Long, options: IndexOptions): List<LongRange> {
            val count = options.rangeCount(size)
            val rangeSize = size / count
            return List(count) { i ->
                val start = i * rangeSize
                val end = if (i == count - 1) size else start + rangeSize
                start..<end
            }
        }

        private class RangeScan(val starts: LongChunkList, val end: Long, val complete: Boolean)

        private fun scanRange(
            channel: FileChannel,
            range: LongRange,
            bufferSize: Int,
            checkCancelled: () -> Unit,
            progress: IndexProgress?,
        ): RangeScan {
            val starts = LongChunkList()
            val end = range.last + 1
            var position = range.first
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(bufferSize.toLong(), 8).asByteBuffer()
                while (position < end) {
                    checkCancelled()
                    buffer.clear().limit(minOf(bufferSize.toLong(), end - position).toInt())
                    val read = channel.readFully(buffer, position)
                    if (read == 0) return RangeScan(starts, position, complete = false)
                    NewlineScanner.scan(buffer, 0, read, position, starts)
                    position += read
                    progress?.add(read.toLong())
                }
            }
            return RangeScan(starts, end, complete = true)
        }

        private fun merge(path: Path, channels: ReadChannels, size: Long, scans: List<RangeScan>): LineIndex {
            val starts = LongChunkList()
            starts.add(0)
            var indexedSize = size
            for (scan in scans) {
                starts.addAll(scan.starts)
                // The file was truncated while scanning; later ranges are unreliable.
                if (!scan.complete) {
                    indexedSize = scan.end
                    break
                }
            }
            while (starts.size > 0 && starts.last() > indexedSize) starts.removeLast()
            val lastLineComplete = starts.size > 0 && starts.last() == indexedSize
            if (lastLineComplete) starts.removeLast()
            return LineIndex(path, channels, starts, indexedSize, lastLineComplete)
        }

        private const val APPEND_BUFFER_SIZE = 256 * 1024
    }
}
