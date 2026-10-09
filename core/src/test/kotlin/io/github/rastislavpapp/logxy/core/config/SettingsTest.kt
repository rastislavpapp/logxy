package io.github.rastislavpapp.logxy.core.config

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.parse.LogFormat
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
    fun `round trip keeps formats in order and level names`() {
        val settings = Settings(
            formats = (1..12).map { LogFormat.Pattern("Format $it", "%d [%t] %-5p %c - %m%n") } + listOf(
                LogFormat.Regex("Tricky: = and \\ ž", """^(?<timestamp>\S+) \\d+ = (?<message>.*)$""", "HH:mm:ss"),
            ),
            levels = LevelMapping(Level.BUILT_IN_NAMES - "CONFIG" + mapOf("note" to Level.INFO, "40" to Level.ERROR)),
            filters = listOf(
                SavedFilter("Slow DB", Level.WARN, """logger == "com.example.db*" AND message =~ "\d+ ms""""),
                SavedFilter("Everything", null, ""),
            ),
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
}
