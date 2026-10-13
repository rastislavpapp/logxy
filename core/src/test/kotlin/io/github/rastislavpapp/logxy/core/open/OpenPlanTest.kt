package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class OpenPlanTest {

    @TempDir
    lateinit var dir: Path

    private fun write(name: String): Path =
        dir.resolve(name).also { Files.createDirectories(it.parent) }.also { Files.writeString(it, "one\n") }

    @Test
    fun `a link and its target dropped together open one file`() {
        val target = write("a.log")
        val link = dir.resolve("link.log")
        // Windows creates links only with a privilege.
        assumeTrue(runCatching { Files.createSymbolicLink(link, target) }.isSuccess)

        assertEquals(listOf(FoundFile(link)), OpenPlan.find(listOf(link, target)).files)
    }

    @Test
    fun `a remembered merge into the current session with too many files falls back to asking`() {
        val current = listOf(Path.of("/logs/a.log"), Path.of("/logs/b.log"))
        val dropped = (1..MergedLog.MAX_PARTS + 1).map { FoundFile(Path.of("/logs/more/$it.log")) }

        val decision = OpenPlan.decide(dropped, current, OpenMode.MERGE_INTO_CURRENT, includeSubfolders = true)

        assertEquals(Decision.Ask(dropped.map { it.path }, inSubfolders = emptySet()), decision)
    }
}
