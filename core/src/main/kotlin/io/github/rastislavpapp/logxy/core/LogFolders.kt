package io.github.rastislavpapp.logxy.core

import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import java.io.IOException
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
     * and folders (such as `.git`), empty files and binary files, except zips. Unreadable folders are
     * skipped.
     */
    fun files(folder: Path, recursive: Boolean = true): List<Path> {
        val found = ArrayList<Path>()
        Files.walkFileTree(folder, emptySet(), if (recursive) Int.MAX_VALUE else 1, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                if (dir != folder && isHidden(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && attrs.size() > 0 && !isHidden(file) && (ZipSpool.isZip(file) || !isBinary(file))) found.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return found.sorted()
    }

    private fun isHidden(path: Path) = path.fileName.toString().startsWith(".") || runCatching { Files.isHidden(path) }.getOrDefault(false)

    /** Text logs have no NUL bytes; images, archives and the like do, near their start. */
    private fun isBinary(file: Path): Boolean = try {
        Files.newInputStream(file).use { input -> input.readNBytes(SNIFFED_BYTES).any { it.toInt() == 0 } }
    } catch (_: IOException) {
        true
    }
}
