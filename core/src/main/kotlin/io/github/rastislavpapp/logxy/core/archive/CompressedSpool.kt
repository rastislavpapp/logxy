package io.github.rastislavpapp.logxy.core.archive

import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Decompresses .gz and .zst files into a directory, so they open like any other file: indexing needs
 * positional reads, which compressed data does not allow. Decompressing an unchanged file again reuses
 * its output. Thread-safe.
 */
class CompressedSpool(val root: Path) {

    /** Where a decompressed file came from; written as just the [source] path, which opens decompressed again. */
    data class Origin(val source: Path, val format: Format) : ArchiveOrigin {
        override val container: Path get() = source
        override val entry: String get() = ""
        override fun toString() = source.toString()
    }

    enum class Format(val extension: String) {
        GZIP(".gz"),
        ZSTD(".zst"),
    }

    private val lock = Mutex()
    private val decompressed = ConcurrentHashMap<String, Path>()
    private val origins = ConcurrentHashMap<Path, Origin>()

    /** The source and format [path] was decompressed from, or null if it was not. */
    fun originOf(path: Path): Origin? = origins[path.toAbsolutePath().normalize()]

    /**
     * The decompressed content of [source], decompressing it first if needed and telling [progress] the
     * fraction done (on the decompressing thread). Cancelling the calling coroutine stops it and removes
     * what was written.
     */
    suspend fun decompress(source: Path, format: Format, progress: (Double) -> Unit = {}): Path = lock.withLock {
        withContext(Dispatchers.IO) {
            val absolute = source.toAbsolutePath().normalize()
            val attributes = Files.readAttributes(absolute, BasicFileAttributes::class.java)
            val key = "$absolute|${attributes.size()}|${attributes.lastModifiedTime().toMillis()}"
            decompressed[key]?.let { return@withContext it }
            val base = root.resolve(SpoolCache.hash(key))
            val target = base.resolve(absolute.fileName.toString().removeSuffix(format.extension))
            try {
                write(absolute, target, format, attributes.size(), progress)
                // The source's time, else a file written just now would count as live and be followed.
                Files.setLastModifiedTime(target, attributes.lastModifiedTime())
                origins[target] = Origin(absolute, format)
                decompressed[key] = target
                target
            } catch (e: Throwable) {
                runCatching { SpoolCache.deleteRecursively(base) }
                throw e
            }
        }
    }

    private suspend fun write(source: Path, target: Path, format: Format, size: Long, progress: (Double) -> Unit) {
        Files.createDirectories(target.parent)
        val buffer = ByteArray(BUFFER_SIZE)
        FileChannel.open(source).use { channel ->
            val input = Channels.newInputStream(channel)
            when (format) {
                Format.GZIP -> GZIPInputStream(input, BUFFER_SIZE)
                Format.ZSTD -> ZstdInputStream(input)
            }.use { decompressing ->
                Files.newOutputStream(target).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = decompressing.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        if (size > 0) progress(minOf(1.0, channel.position().toDouble() / size))
                    }
                }
            }
        }
    }

    companion object {
        /** Bytes of a file's start [formatOf] needs. */
        const val HEADER_BYTES = 4

        private const val BUFFER_SIZE = 1024 * 1024
        private const val DIRECTORY_PREFIX = "decompressed-"

        /** The format of a file starting with [header], judged by its magic bytes; null if it is not compressed. */
        fun formatOf(header: ByteArray): Format? = when {
            header.size >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() -> Format.GZIP
            // 0xFD2FB528, little-endian.
            header.size >= 4 && header[0] == 0x28.toByte() && header[1] == 0xB5.toByte() &&
                header[2] == 0x2F.toByte() && header[3] == 0xFD.toByte() -> Format.ZSTD
            else -> null
        }

        /** The format of [path], judged by its first bytes rather than its name; null if it is not compressed. */
        fun formatOf(path: Path): Format? = try {
            Files.newInputStream(path).use { formatOf(it.readNBytes(HEADER_BYTES)) }
        } catch (_: IOException) {
            null
        }

        /**
         * A spool of this process in [cache]; deleted when the JVM exits. Spools of processes no longer
         * running, e.g. after a crash, are deleted now.
         */
        fun forThisProcess(cache: Path) = CompressedSpool(SpoolCache.createProcessSpool(cache, DIRECTORY_PREFIX))
    }
}
