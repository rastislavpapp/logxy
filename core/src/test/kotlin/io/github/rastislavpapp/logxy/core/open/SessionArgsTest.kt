package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.parse.JsonKeys
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionArgsTest {

    @Test
    fun `arguments written for a restart reopen the same sessions with the same parsers and filters`() {
        val sessions = listOf(
            OpenSession(listOf("/logs/a.log"), listOf(LogFormat.Raw), SavedFilter("", Level.WARN, """message ~ "time out" AND logger = "x.y"""")),
            OpenSession(
                listOf("/logs/b.log", "/logs/old.zip!c.log", "/logs/d.log"),
                listOf(LogFormat.Pattern("Mine", "%d [%t] %p %m%n"), null, LogFormat.Json("Json", JsonKeys(level = listOf("lvl")))),
                null,
            ),
            OpenSession(listOf("/logs/e.log"), listOf(LogFormat.Regex("Re", """(?<level>\w+) (?<message>.*)""", "HH:mm")), null),
            OpenSession(listOf("/logs/f.log"), listOf(null), SavedFilter("", null, "level = ERROR")),
        )

        val reopened = SessionArgs.read(SessionArgs.write(sessions, selected = 2))

        assertEquals(Reopen(sessions, selected = 2), reopened)
    }

    @Test
    fun `plain paths are not a restart`() {
        assertEquals(null, SessionArgs.read(listOf("/logs/a.log")))
    }
}
