package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.parse.RoleKeys
import io.github.rastislavpapp.logxy.core.session.RecordStart
import io.github.rastislavpapp.logxy.core.session.SessionPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionArgsTest {

    @Test
    fun `arguments written for a restart reopen the same sessions with the same parsers and filters`() {
        val sessions = listOf(
            OpenSession(
                listOf("/logs/a.log"),
                listOf(Parser.Raw),
                SavedFilter("", Level.WARN, """message ~ "time out" AND logger = "x.y""""),
            ),
            OpenSession(
                listOf("/logs/b.log", "/logs/old.zip!c.log", "/logs/d.log"),
                listOf(
                    Parser.Pattern("Mine", "%d [%t] %p %m%n"),
                    null,
                    Parser.Json("Json", RoleKeys(level = listOf("lvl"))),
                ),
                null,
                SessionPosition(RecordStart("/logs/old.zip!c.log", 4096), RecordStart("/logs/b.log", 0)),
                shifts = listOf(0, 7_200_000_000_000, -35_100_000_000_000),
            ),
            OpenSession(
                listOf("/logs/e.log"),
                listOf(Parser.Regex("Re", """(?<level>\w+) (?<message>.*)""", "HH:mm")),
                null,
            ),
            OpenSession(
                listOf("/logs/f.log"),
                listOf(null),
                SavedFilter("", null, "level = ERROR"),
                SessionPosition(lead = null, top = RecordStart("/logs/f.log", 120)),
                traceColumn = "Thread",
            ),
        )

        val reopened = SessionArgs.read(SessionArgs.write(sessions, selected = 2))

        assertEquals(Reopen(sessions, selected = 2), reopened)
    }

    @Test
    fun `plain paths are not a restart`() {
        assertEquals(null, SessionArgs.read(listOf("/logs/a.log")))
    }
}
