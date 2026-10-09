package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.index.NewlineScanner
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads the lines of `[from, end)` of a file through a reused buffer. After [next] returns true,
 * the current line's bytes (without terminator) are `buffer[lineFrom, lineTo)`. A line longer than
 * the buffer is cut to the buffer size; [lineStart] and [nextLineStart] are still exact.
 */
internal class LineReader(
    private val channel: FileChannel,
    from: Long,
    private val end: Long,
    bufferSize: Int,
    private val onRead: (Int) -> Unit = {},
) {
    val buffer = ByteArray(bufferSize)
    private val wrapped = ByteBuffer.wrap(buffer)

    /** File offset of `buffer[0]`. */
    private var bufferStart = from
    private var filled = 0
    private var position = 0
    private var scanned = 0

    var lineStart = 0L
        private set
    var lineFrom = 0
        private set
    var lineTo = 0
        private set

    /** Whether the current line ended with `\n`; false only for the last line of the data. */
    var hasNewline = false
        private set

    /** File offset just past the current line and its terminator. */
    var nextLineStart = from
        private set

    /** True once the file turned out shorter than `end` (truncated while reading). */
    var truncated = false
        private set

    fun next(): Boolean {
        if (nextLineStart >= end || truncated && position >= filled) return false
        if (bufferStart + position != nextLineStart) {
            // The previous line was longer than the buffer; continue after it.
            bufferStart = nextLineStart
            filled = 0
            position = 0
            scanned = 0
        }
        var newline = NewlineScanner.indexOf(wrapped, maxOf(position, scanned), filled)
        while (newline < 0) {
            scanned = filled
            if (bufferStart + filled >= end) break
            if (position > 0) compact()
            if (filled == buffer.size) return longLine()
            if (fill() <= 0) {
                truncated = true
                break
            }
            newline = NewlineScanner.indexOf(wrapped, scanned, filled)
        }
        if (position >= filled) return false
        lineStart = bufferStart + position
        lineFrom = position
        if (newline >= 0) {
            lineTo = newline
            hasNewline = true
            position = newline + 1
        } else {
            lineTo = filled
            hasNewline = false
            position = filled
        }
        scanned = position
        nextLineStart = bufferStart + position
        // The line ends before its \n, so only a \r can be left.
        lineTo = Bytes.trimLineEnd(buffer, lineFrom, lineTo)
        return true
    }

    private fun compact() {
        System.arraycopy(buffer, position, buffer, 0, filled - position)
        bufferStart += position
        filled -= position
        scanned -= position
        position = 0
    }

    private fun fill(): Int {
        val want = minOf((buffer.size - filled).toLong(), end - (bufferStart + filled)).toInt()
        wrapped.clear().position(filled).limit(filled + want)
        var total = 0
        while (wrapped.hasRemaining()) {
            val read = channel.read(wrapped, bufferStart + filled + total)
            if (read < 0) break
            total += read
        }
        filled += total
        onRead(total)
        return total
    }

    /** The buffer holds only part of one line: expose that part, and find where the line ends. */
    private fun longLine(): Boolean {
        lineStart = bufferStart
        lineFrom = 0
        lineTo = filled
        val scratch = ByteBuffer.allocate(64 * 1024)
        var offset = bufferStart + filled
        while (offset < end) {
            scratch.clear().limit(minOf(scratch.capacity().toLong(), end - offset).toInt())
            val read = channel.read(scratch, offset)
            if (read <= 0) {
                truncated = true
                break
            }
            onRead(read)
            val newline = NewlineScanner.indexOf(scratch, 0, read)
            if (newline >= 0) {
                hasNewline = true
                nextLineStart = offset + newline + 1
                return true
            }
            offset += read
        }
        hasNewline = false
        nextLineStart = offset
        return true
    }
}
