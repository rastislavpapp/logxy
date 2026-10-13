package io.github.rastislavpapp.logxy.core.config

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.parse.JsonKeys
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SettingsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `round trip keeps formats in order and level names`() {
        val settings = Settings(
            formats = (1..12).map { LogFormat.Pattern("Format $it", "%d [%t] %-5p %c - %m%n") } + listOf(
                LogFormat.Regex("Tricky: = and \\ ž", """^(?<timestamp>\S+) \\d+ = (?<message>.*)$""", "HH:mm:ss"),
                LogFormat.Json("JSON", JsonKeys(level = listOf("lvl", "log.level"), exception = emptyList())),
            ),
            levels = LevelMapping(Level.BUILT_IN_NAMES - "CONFIG" + mapOf("note" to Level.INFO, "40" to Level.ERROR)),
            filters = listOf(
                SavedFilter("Slow DB", Level.WARN, """logger == "com.example.db*" AND message =~ "\d+ ms""""),
                SavedFilter("Everything", null, ""),
            ),
            maxMemory = "8g",
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
            format.1.name=Good
            format.1.layout=%m%n
            format.2.name=No layout
            format.3.name=Bad type
            format.3.type=xml
            format.3.layout=x
            level.NOTE=info
            level.WEIRD=NOT_A_LEVEL
        """.trimIndent())
        val settings = Settings.load(file)
        assertEquals(listOf<LogFormat>(LogFormat.Pattern("Good", "%m%n")), settings.formats)
        assertEquals(LevelMapping(Level.BUILT_IN_NAMES + ("NOTE" to Level.INFO)), settings.levels)
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
    fun `memory limit is read strictly`() {
        assertEquals("8g", Settings.parseMemory(" 8G "))
        assertEquals("8g", Settings.parseMemory("8gb"))
        assertEquals("6144m", Settings.parseMemory("6144m"))
        assertNull(Settings.parseMemory("100m"))
        assertNull(Settings.parseMemory("8"))
        assertNull(Settings.parseMemory("-Xmx8g"))
    }

    @Test
    fun `known formats are raw text, then the saved ones, then the built-in ones kept`() {
        val mine = LogFormat.Pattern("Mine", "%d [%t] %-5p %c - %m%n")
        val settings = Settings(formats = listOf(mine), deletedBuiltIns = setOf("Spring Boot 3", "JSON lines"))
        assertEquals(
            listOf("Raw text", "Mine", "date [thread] LEVEL logger - message", "time [thread] LEVEL logger - message"),
            settings.knownFormats.map { it.name },
        )
    }
}
