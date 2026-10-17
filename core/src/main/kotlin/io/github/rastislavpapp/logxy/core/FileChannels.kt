package io.github.rastislavpapp.logxy.core

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Reads the file from [position] into the remaining space of [buffer], until the buffer is full or
 * the file ends; returns the number of bytes read. Positional, so it does not move the channel.
 */
internal fun FileChannel.readFully(buffer: ByteBuffer, position: Long): Int {
    val start = buffer.position()
    while (buffer.hasRemaining()) {
        if (read(buffer, position + (buffer.position() - start)) < 0) break
    }
    return buffer.position() - start
}

/**
 * Channels of one file for reading it in parallel. On Windows the JDK runs positional reads on one
 * channel one at a time (they move the shared file pointer), so there parallel readers get a channel
 * each, and [main] is kept apart so the UI does not wait behind them; all are opened together so they
 * see the same file, even if it is rotated later.
 */
internal class ReadChannels private constructor(private val channels: List<FileChannel>) : Closeable {

    /** For reads outside parallel work, e.g. one record's text. */
    val main: FileChannel get() = channels[0]

    /** The channel for parallel task number [task]. */
    fun forTask(task: Int): FileChannel =
        if (channels.size == 1) channels[0] else channels[1 + Math.floorMod(task, channels.size - 1)]

    override fun close() {
        var error: Throwable? = null
        for (channel in channels) {
            try {
                channel.close()
            } catch (e: Throwable) {
                error?.addSuppressed(e) ?: run { error = e }
            }
        }
        error?.let { throw it }
    }

    companion object {
        private val serialReads = PlatformDetector.isWindows()

        /** Opens [count] channels for tasks plus [main] where reads are serial, else one for all. */
        fun open(path: Path, count: Int): ReadChannels {
            val opened = if (serialReads) count + 1 else 1
            val channels = ArrayList<FileChannel>(opened)
            try {
                repeat(opened) { channels += FileChannel.open(path, StandardOpenOption.READ) }
            } catch (e: Throwable) {
                channels.forEach { runCatching { it.close() } }
                throw e
            }
            return ReadChannels(channels)
        }
    }
}
