package io.github.rastislavpapp.logxy.core.config

import io.github.rastislavpapp.logxy.core.PlatformDetector
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.parse.Parser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** A named filter: the minimum level (null for all) and the query text. */
data class SavedFilter(val name: String, val minimumLevel: Level?, val query: String) {
    /** Whether it lets all records through: no minimum level, and no query. */
    val isEmpty: Boolean get() = minimumLevel == null && query.isBlank()
}

/**
 * The colors a row highlight can have, stored by name in lower case (`red`); [label] is the name the user sees.
 * The look gives each one a background for light themes and one for dark themes.
 */
enum class PaletteColor(val label: String) {
    RED("Red"),
    ORANGE("Orange"),
    YELLOW("Yellow"),
    GREEN("Green"),
    TEAL("Teal"),
    BLUE("Blue"),
    PURPLE("Purple"),
    GRAY("Gray"),
}

/** A row highlight: the rows of the records [query] (its text, as typed in the filter bar) matches get [color]. */
data class RowHighlight(val query: String, val color: PaletteColor) {
    companion object {
        /** The most row highlights there can be, as each record keeps the index of its own in one byte. */
        const val MAX = 255
    }
}

/**
 * How to open files: added to the current tab's session, merged with that session's files in a new tab,
 * merged with each other in a new tab, or in a tab each.
 */
enum class OpenMode(val key: String) {
    MERGE_INTO_CURRENT("merge-into-current"),
    MERGE_WITH_CURRENT_IN_NEW("merge-with-current"),
    MERGE("merge"),
    TABS("tabs"),
}

/**
 * What the user set up and saved:
 * - their own parsers, level names and saved filters;
 * - [openMode], how to open files without asking, or null to ask each time;
 * - whether opened folders include their subfolders;
 * - [deletedBuiltIns], the names of the built-in parsers they deleted;
 * - [rowHighlights], in order: a record takes the color of the first one it matches. At most [RowHighlight.MAX].
 * - [rememberRecent], whether closed sessions are kept as recent sessions to open again, and [reopenOnStart],
 *   whether a start opens the sessions open at the last exit; the latter only counts while the former is on.
 *
 * The memory limit is not here but in [VmOptions], as the launcher reads it from there.
 */
data class Settings(
    val parsers: List<Parser> = emptyList(),
    val levels: LevelMapping = LevelMapping.DEFAULT,
    val filters: List<SavedFilter> = emptyList(),
    val openMode: OpenMode? = null,
    val includeSubfolders: Boolean = true,
    val deletedBuiltIns: Set<String> = emptySet(),
    val rowHighlights: List<RowHighlight> = emptyList(),
    val rememberRecent: Boolean = false,
    val reopenOnStart: Boolean = false,
) {
    /** The built-in parsers the user kept. */
    val builtInParsers: List<Parser> get() = Parser.BUILT_IN.filter { it.name !in deletedBuiltIns }

    /** The parsers a file can be read with, as parser menus list them. */
    val knownParsers: List<Parser> get() = listOf(Parser.Raw) + parsers + builtInParsers

    companion object {
        /** `%APPDATA%\logxy` on Windows, `$XDG_CONFIG_HOME/logxy` or `~/.config/logxy` elsewhere. */
        fun directory(): Path {
            val appData = System.getenv("APPDATA")
            return when {
                PlatformDetector.isWindows() && appData != null -> Path.of(appData, "logxy")
                else -> (System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), ".config")).resolve("logxy")
            }
        }

        /** The user's settings file: `logxy.properties` in [directory]. */
        fun defaultPath(): Path = directory().resolve("logxy.properties")

        /**
         * The folder for files that can be made again, such as extracted zips: `%LOCALAPPDATA%\logxy\cache` on
         * Windows, `$XDG_CACHE_HOME/logxy` or `~/.cache/logxy` elsewhere.
         */
        fun cacheDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
            val windows = PlatformDetector.isWindows()
            return when {
                windows && localAppData != null -> Path.of(localAppData, "logxy", "cache")
                else -> (System.getenv("XDG_CACHE_HOME")?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), ".cache")).resolve("logxy")
            }
        }

        /** Reads settings from a properties file, as [fromProperties]; a missing file means default settings. */
        fun load(path: Path): Settings =
            if (Files.exists(path)) fromProperties(PropertiesFiles.load(path)) else Settings()

        /** Writes [settings] to [path], replacing the file only once the new one is complete. */
        fun save(path: Path, settings: Settings) =
            PropertiesFiles.store(path, toProperties(settings), "logxy settings")

        /**
         * Reads settings from properties, e.g.:
         * ```
         * parser.1.name=My app
         * parser.1.type=pattern
         * parser.1.layout=%d [%t] %-5p %c - %m%n
         * parser.1.alias.Logger=Class
         * parser.2.name=My JSON
         * parser.2.type=json
         * parser.2.keys.level=level,severity
         * deleted-built-in.1.name=Spring Boot 3
         * level-names=custom
         * level.NOTE=INFO
         * filter.1.name=Slow DB
         * filter.1.level=WARN
         * filter.1.query=logger == "com.example.db*"
         * highlight.1.query=level >= ERROR
         * highlight.1.color=red
         * open.mode=merge
         * open.subfolders=false
         * recent.remember=true
         * recent.reopen=true
         * ```
         * Level names are stored only once the user changed them, and then completely. Entries that cannot be read
         * are skipped. `recent.reopen` is read as off without `recent.remember`.
         */
        fun fromProperties(properties: Properties): Settings {
            val parsers = numbered(properties, "parser")
                .mapNotNull { n -> readParser(properties, "parser.$n.") }
                .filter { it != Parser.Raw }
            val levels = properties.stringPropertyNames()
                .filter { it.startsWith("level.") }
                .mapNotNull { key ->
                    val level = Level.entries.firstOrNull { it.name == properties.getProperty(key).trim().uppercase() }
                    level?.let { key.removePrefix("level.") to it }
                }
                .toMap()
            val custom = properties.getProperty("level-names") == "custom"
            val filters = numbered(properties, "filter").mapNotNull { n ->
                val name = properties.getProperty("filter.$n.name")?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                val levelName = properties.getProperty("filter.$n.level")?.trim()?.uppercase()
                val level = Level.entries.firstOrNull { it.name == levelName }
                SavedFilter(name, level, properties.getProperty("filter.$n.query").orEmpty())
            }
            val rowHighlights = numbered(properties, "highlight", "query").mapNotNull { n ->
                val query = properties.getProperty("highlight.$n.query").trim()
                val colorName = properties.getProperty("highlight.$n.color")?.trim()
                val color = PaletteColor.entries.firstOrNull { it.name.equals(colorName, ignoreCase = true) }
                if (query.isEmpty() || color == null) null else RowHighlight(query, color)
            }.take(RowHighlight.MAX)
            val openModeKey = properties.getProperty("open.mode")
            val openMode = OpenMode.entries.firstOrNull { it.key == openModeKey }
            val includeSubfolders = properties.getProperty("open.subfolders") != "false"
            val deletedBuiltIns = numbered(properties, "deleted-built-in")
                .mapNotNull { n -> properties.getProperty("deleted-built-in.$n.name")?.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            // Without the `level-names=custom` marker, the level entries only add to the built-in names, as
            // they did in the first release.
            val levelMapping = LevelMapping(if (custom) levels else Level.BUILT_IN_NAMES + levels)
            val rememberRecent = properties.getProperty("recent.remember") == "true"
            val reopenOnStart = rememberRecent && properties.getProperty("recent.reopen") == "true"
            return Settings(
                parsers, levelMapping, filters, openMode, includeSubfolders, deletedBuiltIns, rowHighlights,
                rememberRecent, reopenOnStart,
            )
        }

        /** [settings] as [fromProperties] reads them. */
        fun toProperties(settings: Settings): Properties {
            val properties = Properties()
            settings.parsers.filter { it != Parser.Raw }.forEachIndexed { i, parser ->
                writeParser(properties, "parser.${i + 1}.", parser)
            }
            settings.deletedBuiltIns.forEachIndexed { i, name -> properties["deleted-built-in.${i + 1}.name"] = name }
            settings.filters.forEachIndexed { i, filter ->
                properties["filter.${i + 1}.name"] = filter.name
                properties["filter.${i + 1}.level"] = filter.minimumLevel?.name.orEmpty()
                properties["filter.${i + 1}.query"] = filter.query
            }
            settings.rowHighlights.forEachIndexed { i, highlight ->
                properties["highlight.${i + 1}.query"] = highlight.query
                properties["highlight.${i + 1}.color"] = highlight.color.name.lowercase()
            }
            settings.openMode?.let { properties["open.mode"] = it.key }
            if (!settings.includeSubfolders) properties["open.subfolders"] = "false"
            if (settings.rememberRecent) properties["recent.remember"] = "true"
            if (settings.reopenOnStart) properties["recent.reopen"] = "true"
            if (settings.levels != LevelMapping.DEFAULT) {
                properties["level-names"] = "custom"
                settings.levels.names.forEach { (text, level) -> properties["level.$text"] = level.name }
            }
            return properties
        }

        /** Reads the parser stored under [prefix] (e.g. `parser.1.`); null if it is incomplete. */
        internal fun readParser(properties: Properties, prefix: String): Parser? {
            val name = properties.getProperty("${prefix}name")?.trim().orEmpty()
            if (name.isEmpty()) return null
            val type = properties.getProperty("${prefix}type", "pattern")
            val kind = Parser.KINDS.firstOrNull { it.type == type } ?: return null
            val aliasPrefix = "${prefix}alias."
            val aliases = properties.stringPropertyNames()
                .filter { it.startsWith(aliasPrefix) }
                .associate { it.removePrefix(aliasPrefix) to properties.getProperty(it) }
            return kind.read(name) { properties.getProperty(prefix + it) }?.withAliases(aliases)
        }

        /**
         * Stores [parser] under [prefix] (e.g. `parser.1.`) as [readParser] reads it back; also used for the
         * arguments a restart passes on.
         */
        internal fun writeParser(properties: Properties, prefix: String, parser: Parser) {
            properties["${prefix}type"] = parser.kind.type
            parser.properties().forEach { (key, value) -> properties[prefix + key] = value }
            parser.aliases.forEach { (field, alias) -> properties["${prefix}alias.$field"] = alias }
            properties["${prefix}name"] = parser.name
        }

        /** The N of all `prefix.N.name` keys, or of the keys ending in [key] instead of `name`, in order. */
        private fun numbered(properties: Properties, prefix: String, key: String = "name"): List<Int> {
            val pattern = Regex("""${Regex.escape(prefix)}\.(\d+)\.${Regex.escape(key)}""")
            return properties.stringPropertyNames()
                .mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toInt() }
                .sorted()
        }
    }
}
