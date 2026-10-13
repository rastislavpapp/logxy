package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.LogFolders
import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.fileKey
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import java.nio.file.Files
import java.nio.file.Path

/** A file to open; [inSubfolder] if it was found in a subfolder of an opened folder. */
data class FoundFile(val path: Path, val inSubfolder: Boolean = false)

/** What dropped paths hold: the [files] to open, [empty] folders, and the paths that cannot be opened, with why. */
data class Found(val files: List<FoundFile>, val empty: List<Path>, val problems: List<Pair<Path, String>>)

/** How to open found files. */
sealed interface Decision {
    /** The current session shows them all already. */
    data object Nothing : Decision

    /** A single file, with nothing open: it has only one way to open. */
    data class OpenOne(val path: Path) : Decision

    /** As the settings say, without asking. */
    data class Open(val mode: OpenMode, val files: List<Path>) : Decision

    /** The user decides; files [inSubfolders] of an opened folder can be left out. */
    data class Ask(val files: List<Path>, val inSubfolders: Set<Path>) : Decision
}

/** Decides which files open in which sessions, from the paths the user opens. */
object OpenPlan {

    /**
     * The files of [paths]: folders as the log files in them. Of the paths to one file, through links,
     * keeps only the first, as a merge of a file with itself would show each record twice. Tells [onFound]
     * how many files it found so far in the folder it walks; stops soon once cancelled.
     */
    suspend fun find(paths: List<Path>, onFound: (folder: Path, count: Int) -> Unit = { _, _ -> }): Found {
        val empty = ArrayList<Path>()
        val problems = ArrayList<Pair<Path, String>>()
        val files = paths.map { it.toAbsolutePath().normalize() }.distinct().flatMap { path ->
            problem(path)?.let {
                problems += path to it
                return@flatMap emptyList()
            }
            if (!Files.isDirectory(path)) return@flatMap listOf(FoundFile(path))
            LogFolders.files(path) { onFound(path, it) }.map { FoundFile(it, it.parent != path) }.also { if (it.isEmpty()) empty.add(path) }
        }
        return Found(files.distinctBy { fileKey(it.path) }, empty, problems)
    }

    /**
     * How to open [files] next to the [current] session's files (null if none is open): with the remembered
     * [openMode] (null to ask) and [includeSubfolders] if they fit, else by asking.
     */
    fun decide(files: List<FoundFile>, current: List<Path>?, openMode: OpenMode?, includeSubfolders: Boolean): Decision {
        // Files the current session already shows have nothing to add.
        val shown = current.orEmpty().mapTo(HashSet(), ::fileKey)
        val candidates = files.distinctBy { it.path }.filter { fileKey(it.path) !in shown }
        if (candidates.isEmpty()) return Decision.Nothing
        val all = candidates.map { it.path }
        val inSubfolders = candidates.filter { it.inSubfolder }.mapTo(LinkedHashSet()) { it.path }
        if (current == null && all.size == 1 && inSubfolders.isEmpty()) return Decision.OpenOne(all.single())
        if (openMode != null) {
            val included = if (includeSubfolders) all else all - inSubfolders
            if (included.isEmpty()) return Decision.Nothing
            rememberedMode(openMode, included.size, current?.size)?.let { return Decision.Open(it, included) }
        }
        return Decision.Ask(all, inSubfolders)
    }

    /** What [count] files can do: merge with a current session of [currentCount] files, merge on their own, or open one session each. */
    fun modesFor(count: Int, currentCount: Int?): List<OpenMode> = buildList {
        if (count == 0) return@buildList
        if (currentCount != null && currentCount + count <= MergedLog.MAX_PARTS) {
            add(OpenMode.MERGE_INTO_CURRENT)
            add(OpenMode.MERGE_WITH_CURRENT_IN_NEW)
        }
        if (count in 2..MergedLog.MAX_PARTS) add(OpenMode.MERGE)
        add(OpenMode.TABS)
    }

    /**
     * The closest of the modes [count] files can be opened in to the [remembered] one, with a current session of
     * [currentCount] files; null if merging is not possible as remembered (too many files), so the user decides.
     */
    private fun rememberedMode(remembered: OpenMode, count: Int, currentCount: Int?): OpenMode? {
        val possible = modesFor(count, currentCount)
        return when {
            remembered in possible -> remembered
            remembered != OpenMode.TABS && OpenMode.MERGE in possible -> OpenMode.MERGE
            count == 1 -> OpenMode.TABS
            else -> null
        }
    }

    /** Why [path] cannot be opened; null if it can. */
    fun problem(path: Path): String? = when {
        !Files.exists(path) -> "File not found"
        !Files.isRegularFile(path) && !Files.isDirectory(path) -> "Not a regular file"
        !Files.isReadable(path) -> "No permission to read the file"
        else -> null
    }
}
