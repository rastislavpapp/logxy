package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.parse.Parser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TabPlanTest {

    @TempDir
    lateinit var dir: Path

    private fun write(name: String): Path = dir.resolve(name).also { Files.writeString(it, "one\n") }

    @Test
    fun `a link to a file another tab shows selects that tab`() {
        val target = write("a.log")
        val other = write("b.log")
        val link = dir.resolve("link.log")
        // Windows creates links only with a privilege.
        assumeTrue(runCatching { Files.createSymbolicLink(link, target) }.isSuccess)
        val tabs = listOf(SessionFiles(listOf(target), listOf(null)), SessionFiles(listOf(other), listOf(null)))

        val actions = TabPlan.open(OpenMode.TABS, listOf(link), listOf(null), tabs, current = 1)

        assertEquals(listOf(TabAction.Select(0)), actions)
    }

    @Test
    fun `two spellings of one file opened together open one tab`() {
        val file = write("a.log")
        val other = dir.resolve("sub/../a.log")

        val actions =
            TabPlan.open(OpenMode.TABS, listOf(file, other), listOf(null, null), tabs = emptyList(), current = null)

        assertEquals(listOf(TabAction.Add(SessionFiles(listOf(file), listOf(null))), TabAction.Select(0)), actions)
    }

    @Test
    fun `a merge into the current session replaces its tab, keeping its filter and parsers`() {
        val filter = SavedFilter("", Level.WARN, "")
        val tabs = listOf(SessionFiles(listOf(Path.of("/logs/a.log")), listOf(Parser.Raw), filter))

        val actions =
            TabPlan.open(OpenMode.MERGE_INTO_CURRENT, listOf(Path.of("/logs/b.log")), listOf(null), tabs, current = 0)

        val paths = listOf(Path.of("/logs/a.log"), Path.of("/logs/b.log"))
        val merged = SessionFiles(paths, listOf(Parser.Raw, null), filter)
        assertEquals(listOf(TabAction.Replace(0, merged)), actions)
    }

    @Test
    fun `a restore selects the tab of the selected session when one before it is skipped or open already`() {
        val open = SessionFiles(listOf(Path.of("/logs/a.log")), listOf(null))
        val skipped = SessionFiles(emptyList(), emptyList())
        val selected = SessionFiles(listOf(Path.of("/logs/b.log")), listOf(null))

        val actions = TabPlan.reopen(listOf(skipped, open, selected), tabs = listOf(open), selected = 2)

        assertEquals(listOf(TabAction.Add(selected), TabAction.Select(1)), actions)
    }
}
