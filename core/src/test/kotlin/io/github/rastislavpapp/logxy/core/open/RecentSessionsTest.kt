package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.parse.RoleKeys
import io.github.rastislavpapp.logxy.core.session.RecordStart
import io.github.rastislavpapp.logxy.core.session.SessionPosition
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentSessionsTest {

    @TempDir
    lateinit var dir: Path

    private fun file(name: String): String = Files.writeString(dir.resolve(name), "one\n").toString()

    @Test
    fun `stored sessions read back with their parsers, filter, position and archive origins`() {
        val log = file("a.log")
        val zipEntry = file("old.zip") + "!/logs/server.log"
        val compressed = file("c.log.gz")
        val merged = OpenSession(
            listOf(log, zipEntry),
            listOf(Parser.Pattern("Mine", "%d [%t] %p %m%n"), Parser.Json("Json", RoleKeys(level = listOf("lvl")))),
            SavedFilter("", Level.WARN, """message ~ "time out""""),
            SessionPosition(RecordStart(zipEntry, 4096), RecordStart(log, 0)),
            // -9:45 for the second file.
            shifts = listOf(0, -35_100_000_000_000),
        )
        val compressedTop = SessionPosition(lead = null, top = RecordStart(compressed, 7))
        val single = OpenSession(listOf(compressed), listOf(null), null, compressedTop)
        val raw = OpenSession(listOf(log), listOf(Parser.Raw), SavedFilter("", null, "level = ERROR"))
        val sessions = RecentSessions(recent = listOf(merged, single), atExit = Reopen(listOf(raw, merged), 1))
        val path = dir.resolve("recent.properties")

        RecentSessions.save(path, sessions)

        assertEquals(sessions, RecentSessions.load(path))
    }

    private fun session(vararg paths: String, query: String = "") =
        OpenSession(paths.toList(), paths.map { null }, SavedFilter("", null, query).takeIf { query.isNotEmpty() })

    @Test
    fun `a closed session goes on top and the list keeps the ten newest`() {
        var sessions = RecentSessions()
        for (i in 1..11) sessions = sessions.withClosed(session("/logs/$i.log"))

        val expected = (11 downTo 2).map { listOf("/logs/$it.log") }
        assertEquals(expected, sessions.recent.map { it.paths })
    }

    @Test
    fun `a closed session with the same files in another order replaces the older entry`() {
        val older = session("/logs/a.log", "/logs/b.log", query = "level = WARN")
        val newer = session("/logs/b.log", "/logs/a.log", query = "level = ERROR")
        val sessions = RecentSessions()
            .withClosed(older)
            .withClosed(session("/logs/c.log"))
            .withClosed(newer)

        assertEquals(listOf(newer, session("/logs/c.log")), sessions.recent)
    }

    @Test
    fun `an entry whose files are all gone is dropped when stored, one with some files left is kept`() {
        val left = file("left.log")
        val partly = session(left, dir.resolve("deleted.log").toString())
        val gone = session(dir.resolve("deleted.zip").toString() + "!/a.log")
        val path = dir.resolve("recent.properties")

        RecentSessions.save(path, RecentSessions(listOf(gone, partly)))

        assertEquals(listOf(partly), RecentSessions.load(path).recent)
    }
}
