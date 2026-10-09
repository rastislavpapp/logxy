package io.github.rastislavpapp.logxy.core.config

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Properties

/** The user's saved formats and extra level names. */
data class Settings(
    val formats: List<LogFormat> = emptyList(),
    val levels: LevelMapping = LevelMapping.DEFAULT,
) {
    companion object {
        /** `%APPDATA%\logxy` on Windows, `$XDG_CONFIG_HOME/logxy` or `~/.config/logxy` elsewhere. */
        fun defaultPath(): Path {
            val appData = System.getenv("APPDATA")
            val directory = when {
                System.getProperty("os.name").startsWith("Windows") && appData != null -> Path.of(appData, "logxy")
                else -> (System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), ".config")).resolve("logxy")
            }
            return directory.resolve("logxy.properties")
        }

        /**
         * Reads settings from a properties file, e.g.:
         * ```
         * format.1.name=My app
         * format.1.type=pattern
         * format.1.layout=%d [%t] %-5p %c - %m%n
         * level.NOTE=INFO
         * ```
         * A missing file means no settings; entries that cannot be read are skipped.
         */
        fun load(path: Path): Settings {
            if (!Files.exists(path)) return Settings()
            val properties = Properties()
            Files.newBufferedReader(path).use { properties.load(it) }

            val formatNumbers = properties.stringPropertyNames()
                .mapNotNull { Regex("""format\.(\d+)\.name""").matchEntire(it)?.groupValues?.get(1)?.toInt() }
                .sorted()
            val formats = formatNumbers.mapNotNull { n ->
                val name = properties.getProperty("format.$n.name")?.trim().orEmpty()
                val layout = properties.getProperty("format.$n.layout") ?: return@mapNotNull null
                if (name.isEmpty()) return@mapNotNull null
                when (properties.getProperty("format.$n.type", "pattern")) {
                    "pattern" -> LogFormat.Pattern(name, layout)
                    "regex" -> LogFormat.Regex(name, layout, properties.getProperty("format.$n.timestamp", DEFAULT_TIMESTAMP))
                    else -> null
                }
            }
            val levels = properties.stringPropertyNames()
                .filter { it.startsWith("level.") }
                .mapNotNull { key ->
                    val level = Level.entries.firstOrNull { it.name == properties.getProperty(key).trim().uppercase() }
                    level?.let { key.removePrefix("level.") to it }
                }
                .toMap()
            return Settings(formats, LevelMapping(levels))
        }

        /** Writes [settings] to [path], replacing the file only once the new one is complete. */
        fun save(path: Path, settings: Settings) {
            val properties = SortedProperties()
            settings.formats.forEachIndexed { i, format ->
                val n = i + 1
                when (format) {
                    is LogFormat.Pattern -> {
                        properties["format.$n.type"] = "pattern"
                        properties["format.$n.layout"] = format.pattern
                    }
                    is LogFormat.Regex -> {
                        properties["format.$n.type"] = "regex"
                        properties["format.$n.layout"] = format.regex
                        properties["format.$n.timestamp"] = format.timestampPattern
                    }
                    LogFormat.Raw -> return@forEachIndexed
                }
                properties["format.$n.name"] = format.name
            }
            settings.levels.aliases.forEach { (text, level) -> properties["level.$text"] = level.name }

            Files.createDirectories(path.toAbsolutePath().parent)
            val temporary = Files.createTempFile(path.toAbsolutePath().parent, "logxy", ".tmp")
            try {
                Files.newBufferedWriter(temporary).use { properties.store(it, "logxy settings") }
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: IOException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        const val DEFAULT_TIMESTAMP = "yyyy-MM-dd HH:mm:ss.SSS"
    }
}

/** Writes keys in sorted order, so the file stays readable and diffs stay small. */
private class SortedProperties : Properties() {
    override val entries: MutableSet<MutableMap.MutableEntry<Any, Any>>
        get() = Collections.synchronizedSet(sortedSetOf(compareBy { it.key.toString() }, *super.entries.toTypedArray()))
}
