package io.github.rastislavpapp.logxy.core.config

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.parse.JsonKeys
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** A named filter: the minimum level (null for all) and the query text. */
data class SavedFilter(val name: String, val minimumLevel: Level?, val query: String)

/** How to open files: into the current tab's merge, merged with it in a new tab, merged on their own, or a tab each. */
enum class OpenMode(val key: String) {
    MERGE_INTO_CURRENT("merge-into-current"),
    MERGE_WITH_CURRENT_IN_NEW("merge-with-current"),
    MERGE("merge"),
    TABS("tabs"),
}

/**
 * The user's saved formats (parsers), level names, filters, the memory limit (e.g. `8g`, null for the JVM
 * default), how to open files without asking ([openMode], null to ask) and whether opened folders include
 * their subfolders, and the names of the built-in formats the user deleted.
 */
data class Settings(
    val formats: List<LogFormat> = emptyList(),
    val levels: LevelMapping = LevelMapping.DEFAULT,
    val filters: List<SavedFilter> = emptyList(),
    val maxMemory: String? = null,
    val openMode: OpenMode? = null,
    val includeSubfolders: Boolean = true,
    val deletedBuiltIns: Set<String> = emptySet(),
) {
    /** The built-in formats the user kept. */
    val builtInFormats: List<LogFormat> get() = LogFormat.BUILT_IN.filter { it.name !in deletedBuiltIns }

    companion object {
        /** `%APPDATA%\logxy` on Windows, `$XDG_CONFIG_HOME/logxy` or `~/.config/logxy` elsewhere. */
        fun directory(): Path {
            val appData = System.getenv("APPDATA")
            return when {
                System.getProperty("os.name").startsWith("Windows") && appData != null -> Path.of(appData, "logxy")
                else -> (System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), ".config")).resolve("logxy")
            }
        }

        fun defaultPath(): Path = directory().resolve("logxy.properties")

        /** For files that can be made again: `%LOCALAPPDATA%\logxy\cache` on Windows, `$XDG_CACHE_HOME/logxy` or `~/.cache/logxy` elsewhere. */
        fun cacheDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
            return when {
                System.getProperty("os.name").startsWith("Windows") && localAppData != null -> Path.of(localAppData, "logxy", "cache")
                else -> (System.getenv("XDG_CACHE_HOME")?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), ".cache")).resolve("logxy")
            }
        }

        /**
         * Reads settings from a properties file, e.g.:
         * ```
         * format.1.name=My app
         * format.1.type=pattern
         * format.1.layout=%d [%t] %-5p %c - %m%n
         * format.2.name=My JSON
         * format.2.type=json
         * format.2.keys.level=level,severity
         * deleted-built-in.1.name=Spring Boot 3
         * level-names=custom
         * level.NOTE=INFO
         * filter.1.name=Slow DB
         * filter.1.level=WARN
         * filter.1.query=logger == "com.example.db*"
         * max-memory=8g
         * open.mode=merge
         * open.subfolders=false
         * ```
         * Level names are stored only once the user changed them, and then completely. A missing file
         * means default settings; entries that cannot be read are skipped.
         */
        fun load(path: Path): Settings {
            if (!Files.exists(path)) return Settings()
            val properties = PropertiesFiles.load(path)

            val formats = numbered(properties, "format").mapNotNull { n -> readFormat(properties, "format.$n.")?.takeIf { it != LogFormat.Raw } }
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
                val level = properties.getProperty("filter.$n.level")?.trim()?.uppercase()
                SavedFilter(name, Level.entries.firstOrNull { it.name == level }, properties.getProperty("filter.$n.query").orEmpty())
            }
            val maxMemory = properties.getProperty("max-memory")?.let(::parseMemory)
            // Without the marker, level entries only add to the built-in names (as first released).
            val openMode = properties.getProperty("open.mode")?.let { mode -> OpenMode.entries.firstOrNull { it.key == mode } }
            val includeSubfolders = properties.getProperty("open.subfolders") != "false"
            val deletedBuiltIns = numbered(properties, "deleted-built-in")
                .mapNotNull { n -> properties.getProperty("deleted-built-in.$n.name")?.trim()?.takeIf { it.isNotEmpty() } }
                .toSet()
            return Settings(
                formats, LevelMapping(if (custom) levels else Level.BUILT_IN_NAMES + levels), filters, maxMemory, openMode, includeSubfolders,
                deletedBuiltIns,
            )
        }

        /** Writes [settings] to [path], replacing the file only once the new one is complete. */
        fun save(path: Path, settings: Settings) {
            val properties = Properties()
            settings.formats.filter { it != LogFormat.Raw }.forEachIndexed { i, format -> writeFormat(properties, "format.${i + 1}.", format) }
            settings.deletedBuiltIns.forEachIndexed { i, name -> properties["deleted-built-in.${i + 1}.name"] = name }
            settings.filters.forEachIndexed { i, filter ->
                properties["filter.${i + 1}.name"] = filter.name
                properties["filter.${i + 1}.level"] = filter.minimumLevel?.name.orEmpty()
                properties["filter.${i + 1}.query"] = filter.query
            }
            settings.maxMemory?.let { properties["max-memory"] = it }
            settings.openMode?.let { properties["open.mode"] = it.key }
            if (!settings.includeSubfolders) properties["open.subfolders"] = "false"
            if (settings.levels != LevelMapping.DEFAULT) {
                properties["level-names"] = "custom"
                settings.levels.names.forEach { (text, level) -> properties["level.$text"] = level.name }
            }
            PropertiesFiles.store(path, properties, "logxy settings")
        }

        /** Reads the format stored under [prefix] (e.g. `format.1.`); null if it is incomplete. */
        internal fun readFormat(properties: Properties, prefix: String): LogFormat? {
            val name = properties.getProperty("${prefix}name")?.trim().orEmpty()
            if (name.isEmpty()) return null
            val type = properties.getProperty("${prefix}type", "pattern")
            if (type == "raw") return LogFormat.Raw
            if (type == "json") return LogFormat.Json(name, jsonKeys(properties, "${prefix}keys."))
            val layout = properties.getProperty("${prefix}layout") ?: return null
            return when (type) {
                "pattern" -> LogFormat.Pattern(name, layout)
                "regex" -> LogFormat.Regex(name, layout, properties.getProperty("${prefix}timestamp", DEFAULT_TIMESTAMP))
                else -> null
            }
        }

        internal fun writeFormat(properties: Properties, prefix: String, format: LogFormat) {
            when (format) {
                is LogFormat.Pattern -> {
                    properties["${prefix}type"] = "pattern"
                    properties["${prefix}layout"] = format.pattern
                }
                is LogFormat.Regex -> {
                    properties["${prefix}type"] = "regex"
                    properties["${prefix}layout"] = format.regex
                    properties["${prefix}timestamp"] = format.timestampPattern
                }
                is LogFormat.Json -> {
                    properties["${prefix}type"] = "json"
                    JSON_ROLES.forEach { (key, keys) -> properties["${prefix}keys.$key"] = keys(format.keys).joinToString(",") }
                }
                LogFormat.Raw -> properties["${prefix}type"] = "raw"
            }
            properties["${prefix}name"] = format.name
        }

        const val DEFAULT_TIMESTAMP = "yyyy-MM-dd HH:mm:ss.SSS"

        /** The role keys of a JSON format as stored, e.g. `format.1.keys.level=level,severity`. */
        private val JSON_ROLES: List<Pair<String, (JsonKeys) -> List<String>>> = listOf(
            "timestamp" to JsonKeys::timestamp, "level" to JsonKeys::level, "message" to JsonKeys::message,
            "logger" to JsonKeys::logger, "thread" to JsonKeys::thread, "exception" to JsonKeys::exception,
        )

        /** Keys missing from the file keep their defaults. */
        private fun jsonKeys(properties: Properties, prefix: String): JsonKeys {
            fun keys(role: String, default: List<String>) =
                properties.getProperty(prefix + role)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default
            val defaults = JsonKeys()
            return JsonKeys(
                timestamp = keys("timestamp", defaults.timestamp),
                level = keys("level", defaults.level),
                message = keys("message", defaults.message),
                logger = keys("logger", defaults.logger),
                thread = keys("thread", defaults.thread),
                exception = keys("exception", defaults.exception),
            )
        }

        /**
         * A memory size in megabytes (`m`) or gigabytes (`g`), lowercased, or null if it is malformed or under
         * 256 MB. Strict, as the app passes it to a new JVM as `-Xmx`, which would not start with a bad value.
         */
        fun parseMemory(text: String): String? {
            val match = Regex("""(\d{1,7})\s*([mg])b?""").matchEntire(text.trim().lowercase()) ?: return null
            val (amount, unit) = match.destructured
            val megabytes = amount.toLong() * if (unit == "g") 1024 else 1
            return if (megabytes >= 256) "$amount$unit" else null
        }

        /** The N of all `prefix.N.name` keys, in order. */
        private fun numbered(properties: Properties, prefix: String): List<Int> = properties.stringPropertyNames()
            .mapNotNull { Regex("""${Regex.escape(prefix)}\.(\d+)\.name""").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            .sorted()
    }
}
