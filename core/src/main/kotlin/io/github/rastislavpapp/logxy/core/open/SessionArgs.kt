package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64
import java.util.Properties

/**
 * A session to open: its [paths] (extracted files by their zip or compressed file, as written by
 * `ArchiveOrigin`), the parser for each ([formats], null to detect it) and its [filter].
 */
data class OpenSession(val paths: List<String>, val formats: List<LogFormat?>, val filter: SavedFilter?)

/** The sessions to open again, and the index of the one to select. */
data class Reopen(val sessions: List<OpenSession>, val selected: Int)

/** Command-line arguments that carry the open sessions over a restart. */
object SessionArgs {

    private const val SESSION_ARG = "--tab"
    private const val SELECT_ARG = "--select"
    private const val LEVEL_ARG = "--level"
    private const val QUERY_ARG = "--query"
    private const val PARSERS_ARG = "--parsers"

    /**
     * Arguments that make the next start reopen [sessions], each with its filter and chosen parsers, and select
     * the tab at [selected] (-1 for none); [read] reads them back.
     */
    fun write(sessions: List<OpenSession>, selected: Int): List<String> = buildList {
        for (session in sessions) {
            add(SESSION_ARG)
            addAll(session.paths)
            session.filter?.minimumLevel?.let { add(LEVEL_ARG); add(it.name) }
            // Encoded, as Windows command lines do not reliably keep the quotes queries are full of.
            session.filter?.query?.takeIf { it.isNotBlank() }?.let { add(QUERY_ARG); add(encode(it)) }
            if (session.formats.any { it != null }) {
                val properties = Properties()
                session.formats.forEachIndexed { i, format -> if (format != null) Settings.writeFormat(properties, "${i + 1}.", format) }
                add(PARSERS_ARG)
                add(encode(StringWriter().also { properties.store(it, null) }.toString()))
            }
        }
        if (selected >= 0) add(SELECT_ARG).also { add(selected.toString()) }
    }

    /** The sessions in [args] written by [write]; null if they are not, e.g. paths given on the command line. */
    fun read(args: List<String>): Reopen? {
        if (args.firstOrNull() != SESSION_ARG) return null
        class Group(val paths: MutableList<String> = ArrayList(), var level: Level? = null, var query: String = "", var formats: List<LogFormat?>? = null) {
            fun session() = OpenSession(
                paths, formats ?: paths.map { null }, SavedFilter("", level, query).takeIf { it.minimumLevel != null || it.query.isNotBlank() },
            )
        }
        val groups = ArrayList<Group>()
        var selected = 0
        val iterator = args.iterator()
        fun value() = if (iterator.hasNext()) iterator.next() else ""
        while (iterator.hasNext()) when (val arg = iterator.next()) {
            SESSION_ARG -> groups += Group()
            LEVEL_ARG -> value().let { level -> groups.last().level = Level.entries.firstOrNull { it.name == level } }
            QUERY_ARG -> groups.last().query = decode(value()).orEmpty()
            PARSERS_ARG -> groups.last().let { group ->
                val properties = Properties().apply { load(StringReader(decode(value()).orEmpty())) }
                group.formats = group.paths.indices.map { Settings.readFormat(properties, "${it + 1}.") }
            }
            SELECT_ARG -> selected = value().toIntOrNull() ?: 0
            else -> groups.last().paths.add(arg)
        }
        return Reopen(groups.map { it.session() }, selected)
    }

    private fun encode(text: String) = Base64.getUrlEncoder().encodeToString(text.toByteArray())

    private fun decode(text: String) = try {
        String(Base64.getUrlDecoder().decode(text))
    } catch (_: IllegalArgumentException) {
        null
    }
}
