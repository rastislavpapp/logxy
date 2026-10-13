package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.fileKey
import io.github.rastislavpapp.logxy.core.parse.Parser
import java.nio.file.Path

/** The files of a session open in a tab, or to open in one, with the parser for each ([parsers], null to detect it) and its [filter]. */
data class SessionFiles(val paths: List<Path>, val parsers: List<Parser?>, val filter: SavedFilter? = null)

/** A change to the tabs, applied in order; [index]es are of the tabs as they are when it applies. */
sealed interface TabAction {
    /** Selects the existing tab at [index], e.g. one that already shows the files being opened. */
    data class Select(val index: Int) : TabAction

    /** A new tab at the end, selected. */
    data class Add(val session: SessionFiles) : TabAction

    /** A new tab, selected, in place of the one at [index]. */
    data class Replace(val index: Int, val session: SessionFiles) : TabAction

    /** A new tab, selected, after the one at [index]. */
    data class InsertAfter(val index: Int, val session: SessionFiles) : TabAction
}

/** Decides which tabs show the sessions to open, and selects a tab rather than open a second one with the same files. */
object TabPlan {

    /** Opens [files] (read with [parsers]) in [mode] next to the open [tabs], of which [current] is selected. */
    fun open(mode: OpenMode, files: List<Path>, parsers: List<Parser?>, tabs: List<SessionFiles>, current: Int?): List<TabAction> {
        val open = OpenTabs(tabs)
        fun place(session: SessionFiles, action: TabAction) =
            open.indexOf(session.paths)?.let(TabAction::Select) ?: action.also { if (it is TabAction.Add) open.add(session.paths) }
        return when (mode) {
            OpenMode.MERGE_INTO_CURRENT, OpenMode.MERGE_WITH_CURRENT_IN_NEW -> {
                val index = requireNotNull(current) { "$mode needs a current tab" }
                val tab = tabs[index]
                val merged = SessionFiles(tab.paths + files, tab.parsers + parsers, tab.filter)
                val action = if (mode == OpenMode.MERGE_INTO_CURRENT) TabAction.Replace(index, merged) else TabAction.InsertAfter(index, merged)
                listOf(place(merged, action))
            }
            OpenMode.MERGE -> SessionFiles(files, parsers).let { listOf(place(it, TabAction.Add(it))) }
            OpenMode.TABS -> files.zip(parsers) { file, parser -> SessionFiles(listOf(file), listOf(parser)) }.map { place(it, TabAction.Add(it)) }
        }
    }

    /**
     * Opens each of [sessions] in a tab next to the open [tabs], then selects the tab of the [selected] one.
     * Sessions without paths (nothing in them could be opened) are skipped.
     */
    fun reopen(sessions: List<SessionFiles>, tabs: List<SessionFiles>, selected: Int): List<TabAction> {
        val open = OpenTabs(tabs)
        val actions = ArrayList<TabAction>()
        val tabOf = sessions.map { session ->
            if (session.paths.isEmpty()) return@map null
            open.indexOf(session.paths) ?: open.add(session.paths).also { actions += TabAction.Add(session) }
        }
        tabOf.getOrNull(selected)?.let { actions += TabAction.Select(it) }
        return actions
    }

    /** The files of open tabs, by [fileKey], so that a link or another spelling of a file finds its tab. */
    private class OpenTabs(tabs: List<SessionFiles>) {
        private val paths = tabs.mapTo(ArrayList()) { it.paths }

        // Resolved only for tabs with as many files, as fileKey reads the file system.
        private val keys = ArrayList<Set<Path>?>(paths.map { null })

        fun indexOf(files: List<Path>): Int? {
            val wanted = keysOf(files)
            return paths.indices.firstOrNull { i ->
                paths[i].size == files.size && (keys[i] ?: keysOf(paths[i]).also { keys[i] = it }) == wanted
            }
        }

        fun add(files: List<Path>): Int {
            paths += files
            keys += null
            return paths.lastIndex
        }

        private fun keysOf(files: List<Path>) = files.mapTo(HashSet(), ::fileKey)
    }
}
