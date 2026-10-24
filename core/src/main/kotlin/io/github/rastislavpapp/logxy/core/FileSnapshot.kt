package io.github.rastislavpapp.logxy.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/** How a file changed compared to what was indexed. */
enum class FileChange {
    UNCHANGED,
    GROWN,

    /** Smaller than the indexed size: truncated, or rotated by copy-and-truncate. */
    SHRUNK,

    /** A different file now sits at the path, e.g. after rotation by rename. */
    REPLACED,
    MISSING,
}

/** State of a file at one moment, cheap enough to take on every poll. */
class FileSnapshot(
    val size: Long,
    val lastModified: Instant,
    /** The inode on Linux; the creation time on Windows, where NIO has no file key. */
    private val identity: Any,
    /** The first bytes of the file. Catches replacements that keep the identity, see [changeTo]. */
    private val head: ByteArray,
) {

    /**
     * How the file changed from this snapshot to [current], a later snapshot of the same path, or null if the
     * file is gone. [indexedSize] is how many bytes of the file were indexed.
     *
     * Windows may give a file created under a just-renamed name the old creation time ("tunneling"),
     * so the identity alone does not catch rotation there; the first bytes almost always differ.
     */
    fun changeTo(current: FileSnapshot?, indexedSize: Long): FileChange = when {
        current == null -> FileChange.MISSING
        current.size < indexedSize -> FileChange.SHRUNK
        current.identity != identity -> FileChange.REPLACED
        !current.head.startsWith(head) -> FileChange.REPLACED
        current.size > indexedSize -> FileChange.GROWN
        else -> FileChange.UNCHANGED
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    companion object {
        const val HEAD_SIZE = 256

        /** Returns null if the file does not exist. */
        fun take(path: Path): FileSnapshot? = try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            FileSnapshot(
                size = attributes.size(),
                lastModified = attributes.lastModifiedTime().toInstant(),
                identity = attributes.fileKey() ?: attributes.creationTime(),
                head = readHead(path),
            )
        } catch (_: NoSuchFileException) {
            null
        }

        private fun readHead(path: Path): ByteArray = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(HEAD_SIZE)
            buffer.array().copyOf(channel.readFully(buffer, 0))
        }
    }
}
