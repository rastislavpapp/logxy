package io.github.rastislavpapp.logxy.core.config

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.parse.RoleKeys
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `round trip keeps parsers in order and level names`() {
        val settings = Settings(
            parsers = (1..12).map { Parser.Pattern("Parser $it", "%d [%t] %-5p %c - %m%n") } + listOf(
                Parser.Regex("Tricky: = and \\ ž", """^(?<timestamp>\S+) \\d+ = (?<message>.*)$""", "HH:mm:ss"),
                Parser.Json("JSON", RoleKeys(level = listOf("lvl", "log.level"), exception = emptyList())),
                Parser.Logfmt("logfmt", RoleKeys.LOGFMT.copy(message = listOf("m"))),
                Parser.AccessLog("My access log", mapOf("Status" to "HTTP status", "%X{a b}" to "Tricky: = ž")),
            ),
            levels = LevelMapping(Level.BUILT_IN_NAMES - "CONFIG" + mapOf("note" to Level.INFO, "40" to Level.ERROR)),
            filters = listOf(
                SavedFilter("Slow DB", Level.WARN, """logger == "com.example.db*" AND message =~ "\d+ ms""""),
                SavedFilter("Everything", null, ""),
            ),
            openMode = OpenMode.MERGE_WITH_CURRENT_IN_NEW,
            includeSubfolders = false,
            deletedBuiltIns = setOf("Spring Boot 3", "JSON lines"),
        )
        val file = dir.resolve("nested/dir/logxy.properties")
        Settings.save(file, settings)
        assertEquals(settings, Settings.load(file))
        assertEquals(listOf("logxy.properties"), Files.list(file.parent).map { it.fileName.toString() }.toList())
    }

    @Test
    fun `missing file means no settings`() {
        assertEquals(Settings(), Settings.load(dir.resolve("none.properties")))
    }

    @Test
    fun `unreadable entries are skipped`() {
        val file = Files.writeString(dir.resolve("logxy.properties"), """
            parser.1.name=Good
            parser.1.layout=%m%n
            parser.2.name=No layout
            parser.3.name=Bad type
            parser.3.type=xml
            parser.3.layout=x
            level.NOTE=info
            level.WEIRD=NOT_A_LEVEL
        """.trimIndent())
        val settings = Settings.load(file)
        assertEquals(listOf<Parser>(Parser.Pattern("Good", "%m%n")), settings.parsers)
        assertEquals(LevelMapping(Level.BUILT_IN_NAMES + ("NOTE" to Level.INFO)), settings.levels)
    }

    @Test
    fun `reads parsers of every kind, a missing type as a pattern`() {
        val file = Files.writeString(dir.resolve("logxy.properties"), """
            parser.1.name=Pattern
            parser.1.type=pattern
            parser.1.layout=%d [%t] %-5p %c - %m%n
            parser.2.name=Regex
            parser.2.type=regex
            parser.2.layout=^(?<message>.*)$
            parser.2.timestamp=HH:mm:ss
            parser.3.name=Regex without timestamp
            parser.3.type=regex
            parser.3.layout=^(?<message>.*)$
            parser.4.name=JSON
            parser.4.type=json
            parser.4.keys.timestamp=t
            parser.4.keys.level=lvl,severity
            parser.4.keys.message=
            parser.5.name=No type
            parser.5.layout=%m%n
        """.trimIndent())
        assertEquals(
            listOf(
                Parser.Pattern("Pattern", "%d [%t] %-5p %c - %m%n"),
                Parser.Regex("Regex", "^(?<message>.*)$", "HH:mm:ss"),
                Parser.Regex("Regex without timestamp", "^(?<message>.*)$", "yyyy-MM-dd HH:mm:ss.SSS"),
                Parser.Json(
                    "JSON",
                    RoleKeys(timestamp = listOf("t"), level = listOf("lvl", "severity"), message = emptyList()),
                ),
                Parser.Pattern("No type", "%m%n"),
            ),
            Settings.load(file).parsers,
        )
    }

    @Test
    fun `default level names are not written`() {
        val file = dir.resolve("logxy.properties")
        Settings.save(file, Settings())
        assertFalse(Files.readString(file).contains("level"))
        assertEquals(LevelMapping.DEFAULT, Settings.load(file).levels)
    }

    @Test
    fun `edited level names replace the built-in ones`() {
        val mapping = LevelMapping(mapOf("Warning" to Level.ERROR, "note" to Level.INFO))
        assertEquals(Level.ERROR, mapping.of(" WARNING "))
        assertEquals(Level.INFO, mapping.of("Note"))
        assertEquals(Level.UNKNOWN, mapping.of("fine"))
        val file = dir.resolve("logxy.properties")
        Settings.save(file, Settings(levels = mapping))
        assertEquals(mapping, Settings.load(file).levels)
    }

    @Test
    fun `known parsers are raw text, then the saved ones, then the built-in ones kept`() {
        val mine = Parser.Pattern("Mine", "%d [%t] %-5p %c - %m%n")
        val settings = Settings(parsers = listOf(mine), deletedBuiltIns = setOf("Spring Boot 3", "JSON lines"))
        assertEquals(
            listOf(
                "Raw text",
                "Mine",
                "date [thread] LEVEL logger - message",
                "time [thread] LEVEL logger - message",
                "Python logging",
                "logfmt",
                "Access log",
            ),
            settings.knownParsers.map { it.name },
        )
    }
}
