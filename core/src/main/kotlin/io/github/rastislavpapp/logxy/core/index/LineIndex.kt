package io.github.rastislavpapp.logxy.core.index

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import io.github.rastislavpapp.logxy.core.filter.LineSnapshot
import io.github.rastislavpapp.logxy.core.filter.RecordSnapshot
import io.github.rastislavpapp.logxy.core.log.AppendedData
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Schema
import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

data class IndexOptions(
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    /** Files are only split into ranges of at least this size, so small files use one thread. */
    val minRangeSize: Long = 64L * 1024 * 1024,
    val bufferSize: Int = 1024 * 1024,
) {
    init {
        require(parallelism >= 1) { "parallelism must be at least 1" }
        require(minRangeSize >= 1) { "minRangeSize must be at least 1" }
        require(bufferSize >= 8) { "bufferSize must be at least 8" }
    }
}

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
    private val channel: FileChannel,
    private val starts: LongChunkList,
    indexedSize: Long,
    // Whether bytes appended next start a new line, i.e. the file is empty or ends with a newline.
    private var lastLineComplete: Boolean,
) : LogData {

    override var indexedSize = indexedSize
        private set

    override val schema get() = Schema.RAW

    val lineCount: Int get() = starts.size

    override val recordCount get() = starts.size

    override fun rowValues(record: Int, maxBytes: Int) = arrayOf(lineText(record, maxBytes))

    internal fun filterSnapshot(): RecordSnapshot = LineSnapshot(lineCount, indexedSize, channel, starts)

    override fun recordText(record: Int, maxBytes: Int) = lineText(record, maxBytes)

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
        var length = readFully(bytes, start)
        if (length.toLong() == fullLength) {
            if (length > 0 && bytes[length - 1] == LF) length--
            if (length > 0 && bytes[length - 1] == CR) length--
        }
        if (length <= maxBytes) return String(bytes, 0, length, Charsets.UTF_8)
        return String(bytes, 0, utf8Boundary(bytes, maxBytes), Charsets.UTF_8) + "…"
    }

    override fun readAppended(size: Long): AppendedLines {
        val from = indexedSize
        if (size <= from) return AppendedLines(from, from, LongChunkList())
        val scan = scanRange(channel, from..<size, APPEND_BUFFER_SIZE, checkCancelled = {}, progress = null)
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

    private fun readFully(target: ByteArray, position: Long): Int {
        val buffer = ByteBuffer.wrap(target)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) break
        }
        return buffer.position()
    }

    override fun close() {
        channel.close()
    }

    companion object {

        /** Scans [path] on [Dispatchers.IO]; cancelling the calling coroutine stops the scan. */
        suspend fun build(
            path: Path,
            progress: IndexProgress = IndexProgress(),
            options: IndexOptions = IndexOptions(),
        ): LineIndex = withContext(Dispatchers.IO) {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val size = channel.size()
                progress.totalBytes = size
                val ranges = splitIntoRanges(size, options)
                val scans = ranges.map { range ->
                    async {
                        val job = coroutineContext.job
                        scanRange(channel, range, options.bufferSize, { job.ensureActive() }, progress)
                    }
                }.awaitAll()
                ensureActive()
                merge(path, channel, size, scans)
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }

        private fun splitIntoRanges(size: Long, options: IndexOptions): List<LongRange> {
            val count = (size / options.minRangeSize).coerceIn(1, options.parallelism.toLong()).toInt()
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
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer, position + buffer.position()) < 0) break
                    }
                    val read = buffer.position()
                    if (read == 0) return RangeScan(starts, position, complete = false)
                    NewlineScanner.scan(buffer, 0, read, position, starts)
                    position += read
                    progress?.add(read.toLong())
                }
            }
            return RangeScan(starts, end, complete = true)
        }

        private fun merge(path: Path, channel: FileChannel, size: Long, scans: List<RangeScan>): LineIndex {
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
            return LineIndex(path, channel, starts, indexedSize, lastLineComplete)
        }

        private const val APPEND_BUFFER_SIZE = 256 * 1024
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()

        /** Largest cut point `<= limit` that does not split a UTF-8 sequence; `bytes` must extend past `limit`. */
        private fun utf8Boundary(bytes: ByteArray, limit: Int): Int {
            var cut = limit
            while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            return cut
        }
    }
}
