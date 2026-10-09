package io.github.rastislavpapp.logxy.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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
