package io.github.rastislavpapp.logxy.core

import io.github.rastislavpapp.logxy.core.archive.CompressedSpool
import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Finds the files of a folder that can be opened as logs. */
object LogFolders {

    private const val SNIFFED_BYTES = 4096

    /**
     * The files in [folder] and, if [recursive], its subfolders, sorted by path. Leaves out hidden files
     * and folders (such as `.git`), empty files and binary files, except zips and .gz or .zst files.
     * Unreadable folders are skipped.
     */
    fun files(folder: Path, recursive: Boolean = true): List<Path> {
        val found = ArrayList<Path>()
        // Following links, as logs are often links, e.g. Kubernetes' /var/log/containers; loops end in visitFileFailed.
        val options = setOf(FileVisitOption.FOLLOW_LINKS)
        Files.walkFileTree(folder, options, if (recursive) Int.MAX_VALUE else 1, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                if (dir != folder && isHidden(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && attrs.size() > 0 && !isHidden(file) && isOpenable(file)) found.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return found.sorted()
    }

    private fun isHidden(path: Path) = path.fileName.toString().startsWith(".") || runCatching { Files.isHidden(path) }.getOrDefault(false)

    /** A zip or compressed file, or text. */
    private fun isOpenable(file: Path): Boolean {
        val start = start(file) ?: return false
        return ZipSpool.isZip(start) || CompressedSpool.formatOf(start) != null || isText(start)
    }

    /** Whether [file] has text in it, e.g. not an image or a class file. */
    fun isText(file: Path): Boolean = start(file)?.let { it.isNotEmpty() && isText(it) } ?: false

    // Text logs have no NUL bytes, while images and the like do near their start.
    private fun isText(start: ByteArray) = start.none { it.toInt() == 0 }

    private fun start(file: Path): ByteArray? = try {
        Files.newInputStream(file).use { it.readNBytes(SNIFFED_BYTES) }
    } catch (_: IOException) {
        null
    }
}
