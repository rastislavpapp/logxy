package io.github.rastislavpapp.logxy.core.archive

import io.github.rastislavpapp.logxy.core.LogFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException
import java.util.zip.ZipFile

private val log = LoggerFactory.getLogger(ZipSpool::class.java)

/**
 * Extracts zip files into a directory, so their entries open like any other file: indexing needs
 * positional reads, which compressed data does not allow. Zips inside a zip are extracted too, into
 * a folder named like them. Extracting an unchanged zip again reuses its files. Thread-safe.
 */
class ZipSpool(val root: Path) {

    /** Where an extracted file came from: [entry] is its path inside [zip], `/`-separated; nested zips are folders. */
    data class Origin(val zip: Path, override val entry: String) : ArchiveOrigin {
        override val container: Path get() = zip
        override fun toString() = "$zip$SEPARATOR$entry"
    }

    /** A file of a zip that extracting left out, as it cannot be a log; [entry] as in [Origin.entry]. */
    data class LeftOut(val entry: String, val reason: String)

    private val lock = Mutex()
    private val extracted = ConcurrentHashMap<String, List<Path>>()
    private val origins = ConcurrentHashMap<Path, Origin>()
    private val leftOut = ConcurrentHashMap<Path, List<LeftOut>>()

    /** The files that extracting [zip] the last time left out. */
    fun leftOutOf(zip: Path): List<LeftOut> = leftOut[zip.toAbsolutePath().normalize()].orEmpty()

    /** The zip and entry [path] was extracted from, or null if it was not. */
    fun originOf(path: Path): Origin? = origins[path.toAbsolutePath().normalize()]

    /** The file [origin] was extracted to, extracting its zip first if needed; null if the zip has no such entry. */
    suspend fun fileOf(origin: Origin): Path? {
        extract(origin.zip)
        return extractedFile(origin)
    }

    /** The file [origin] was extracted to, if its zip was extracted and has that entry. */
    fun extractedFile(origin: Origin): Path? = origins.entries.firstOrNull { it.value == origin }?.key

    /**
     * Extracts the files of [zip], in the zip's order, telling [progress] the fraction done (on the
     * extracting thread). Cancelling the calling coroutine stops it and removes what was extracted.
     */
    suspend fun extract(zip: Path, progress: (Double) -> Unit = {}): List<Path> = lock.withLock {
        withContext(Dispatchers.IO) {
            val absolute = zip.toAbsolutePath().normalize()
            val attributes = Files.readAttributes(absolute, BasicFileAttributes::class.java)
            val key = "$absolute|${attributes.size()}|${attributes.lastModifiedTime().toMillis()}"
            extracted[key]?.let { return@withContext it }
            // A folder per zip version, holding a folder named like the zip, so merged views name files
            // "app.zip/app.log".
            val base = root.resolve(SpoolCache.hash(key))
            val dir = base.resolve(absolute.fileName.toString())
            SpoolCache.deleteRecursively(base)
            try {
                val left = ArrayList<LeftOut>()
                val files = unzip(absolute, dir, progress, depth = 0, dir, left)
                if (left.isNotEmpty()) {
                    val reasons = left.joinToString { "${it.entry} (${it.reason})" }
                    log.info("Left out of {}: {}", absolute, reasons)
                }
                for (file in files) origins[file] = Origin(absolute, dir.relativize(file).joinToString("/"))
                extracted[key] = files
                leftOut[absolute] = left
                files
            } catch (e: Throwable) {
                runCatching { SpoolCache.deleteRecursively(base) }
                throw e
            }
        }
    }

    /** Extracts [zip] into [dir]; adds the files left out to [left], named from [top], the outermost zip's folder. */
    private suspend fun unzip(
        zip: Path, dir: Path, progress: (Double) -> Unit, depth: Int, top: Path, left: MutableList<LeftOut>,
    ): List<Path> {
        val files = ArrayList<Path>()
        open(zip).use { file ->
            val entries = file.entries().toList().filter { !it.isDirectory && isWanted(it.name) }
            val total = maxOf(1L, entries.sumOf { maxOf(0L, it.compressedSize) })
            var done = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            for (entry in entries) {
                val target = dir.resolve(entry.name).normalize()
                // Entry names such as "../../x" must not write outside the folder.
                if (!target.startsWith(dir) || target == dir) continue
                Files.createDirectories(target.parent)
                val compressed = maxOf(0L, entry.compressedSize)
                val size = entry.size
                file.getInputStream(entry).use { input ->
                    Files.newOutputStream(target).use { output ->
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (size > 0) progress((done + compressed * minOf(1.0, written.toDouble() / size)) / total)
                        }
                    }
                }
                // The time from the zip, else a file written just now would count as live and be followed.
                entry.lastModifiedTime?.let { Files.setLastModifiedTime(target, it) }
                done += compressed
                progress(done.toDouble() / total)
                fun leaveOut(reason: String) = left.add(LeftOut(top.relativize(target).joinToString("/"), reason))
                val zipped = isZip(target)
                if (zipped && depth < MAX_DEPTH) {
                    val inner = target.resolveSibling(target.fileName.toString() + ".extracting")
                    Files.move(target, inner, StandardCopyOption.REPLACE_EXISTING)
                    // A broken inner zip leaves out only its own files, not those of the zip around it.
                    try {
                        files.addAll(unzip(inner, target, {}, depth + 1, top, left))
                    } catch (e: IOException) {
                        SpoolCache.deleteRecursively(target)
                        leaveOut("cannot be unzipped: ${e.message ?: e.javaClass.simpleName}")
                    }
                    Files.delete(inner)
                } else if (LogFolders.isText(target)) {
                    files.add(target)
                } else {
                    leaveOut(
                        when {
                            zipped -> "a zip nested too deep"
                            Files.size(target) == 0L -> "empty"
                            else -> "not text"
                        },
                    )
                    Files.delete(target)
                }
            }
        }
        return files
    }

    companion object {
        /** Between a zip and an entry in [Origin.toString], as in `logs.zip!/app/app.log`. */
        const val SEPARATOR = "!/"

        /** Bytes of a file's start [isZip] needs. */
        const val HEADER_BYTES = 4

        private const val BUFFER_SIZE = 1024 * 1024
        private const val MAX_DEPTH = 4
        private const val DIRECTORY_PREFIX = "unzipped-"

        /** Whether [path] is a zip file, judged by its first bytes rather than its name. */
        fun isZip(path: Path): Boolean = try {
            Files.newInputStream(path).use { isZip(it.readNBytes(HEADER_BYTES)) }
        } catch (_: IOException) {
            false
        }

        /** Whether a file starting with [header] is a zip. */
        fun isZip(header: ByteArray): Boolean {
            if (header.size < 4 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) return false
            val third = header[2].toInt()
            val fourth = header[3].toInt()
            return (third == 3 && fourth == 4) || (third == 5 && fourth == 6)
        }

        /** The origin [text] names, as [Origin.toString] writes it, if its zip exists. */
        fun parseOrigin(text: String): Origin? {
            val at = text.indexOf(SEPARATOR)
            if (at <= 0) return null
            val zip = Path.of(text.substring(0, at))
            if (!Files.isRegularFile(zip)) return null
            return Origin(zip.toAbsolutePath().normalize(), text.substring(at + SEPARATOR.length))
        }

        /**
         * A spool of this process in [cache] (not the temp folder, which may be in memory); deleted when the
         * JVM exits. Spools of processes no longer running, e.g. after a crash, are deleted now.
         */
        fun forThisProcess(cache: Path) = ZipSpool(SpoolCache.createProcessSpool(cache, DIRECTORY_PREFIX))

        /** Like [ZipFile], also for zips whose entry names are not UTF-8 (e.g. made by older Windows tools). */
        private fun open(zip: Path): ZipFile = try {
            ZipFile(zip.toFile(), Charsets.UTF_8)
        } catch (_: ZipException) {
            ZipFile(zip.toFile(), Charset.forName("IBM437"))
        }

        /** Not macOS resource forks or hidden files such as `.DS_Store`. */
        private fun isWanted(name: String): Boolean {
            val parts = name.split('/').filter { it.isNotEmpty() }
            return parts.isNotEmpty() && parts.first() != "__MACOSX" && !parts.last().startsWith(".")
        }
    }
}
