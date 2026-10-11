package io.github.rastislavpapp.logxy.core.archive

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** What [ZipSpool] and [CompressedSpool] share: a folder per process, and one per version of a source in it. */
object SpoolCache {

    /** Short hash for a cache key (e.g. path + size + mtime). */
    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /**
     * The folder in [cache] for this process's spool, deleted when the JVM exits; deletes the folders of
     * processes no longer running, e.g. after a crash.
     */
    fun createProcessSpool(cache: Path, directoryPrefix: String): Path {
        runCatching {
            if (Files.isDirectory(cache)) {
                Files.list(cache).use { dirs ->
                    dirs.filter { it.fileName.toString().startsWith(directoryPrefix) }.toList().forEach { dir ->
                        val pid = dir.fileName.toString().removePrefix(directoryPrefix).toLongOrNull()
                        if (pid == null || ProcessHandle.of(pid).isEmpty) runCatching { deleteRecursively(dir) }
                    }
                }
            }
        }
        val root = cache.resolve(directoryPrefix + ProcessHandle.current().pid())
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { deleteRecursively(root) } })
        return root
    }
}
