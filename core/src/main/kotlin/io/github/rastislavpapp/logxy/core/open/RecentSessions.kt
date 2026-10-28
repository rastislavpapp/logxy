package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import io.github.rastislavpapp.logxy.core.config.PropertiesFiles
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.merge.TimestampShift
import io.github.rastislavpapp.logxy.core.session.RecordStart
import io.github.rastislavpapp.logxy.core.session.SessionPosition
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Properties

/**
 * The recent sessions, newest first, and the sessions that were open at exit ([atExit], null if none were),
 * which the next start can open again.
 */
data class RecentSessions(val recent: List<OpenSession> = emptyList(), val atExit: Reopen? = null) {

    /**
     * With [session], just closed, at the top of [recent]. It replaces a recent session with the same files, in any
     * order; the oldest recent sessions beyond [MAX] are dropped.
     */
    fun withClosed(session: OpenSession): RecentSessions {
        val others = recent.filterNot { it.sameFiles(session) }
        return copy(recent = (listOf(session) + others).take(MAX))
    }

    companion object {
        /** How many recent sessions are kept. */
        const val MAX = 10

        private const val RECENT = "recent"
        private const val OPEN_AT_EXIT = "open"
        private const val SELECTED = "open.selected"

        /** The recent sessions stored at [path]; none if the file does not exist. */
        fun load(path: Path): RecentSessions = fromProperties(PropertiesFiles.load(path))

        /** Stores [sessions] at [path], without the recent sessions whose files are all [gone]. */
        fun save(path: Path, sessions: RecentSessions) =
            PropertiesFiles.store(path, toProperties(sessions), "logxy recent sessions")

        /**
         * Whether none of [session]'s files can be found any more; a file from a zip counts as there while the
         * zip is.
         */
        fun gone(session: OpenSession) = session.paths.none { text ->
            val file = ZipSpool.parseOrigin(text)?.zip ?: try {
                Path.of(text)
            } catch (_: InvalidPathException) {
                return@none false
            }
            Files.exists(file)
        }

        /**
         * Reads the sessions from properties, e.g.:
         * ```
         * recent.1.file.1=/logs/a.log
         * recent.1.file.2=/logs/old.zip!/server.log
         * recent.1.parser.2.name=My app
         * recent.1.parser.2.type=pattern
         * recent.1.parser.2.layout=%d [%t] %-5p %c - %m%n
         * recent.1.shift.2=+5:30
         * recent.1.level=WARN
         * recent.1.query=logger == "com.example.db*"
         * recent.1.lead.file=2
         * recent.1.lead.offset=4096
         * recent.1.top.file=1
         * recent.1.top.offset=0
         * recent.1.bookmark.1.file=1
         * recent.1.bookmark.1.offset=512
         * recent.1.traces=MDC[requestId]
         * open.1.file.1=/logs/b.log
         * open.selected=1
         * ```
         * A position's file, also a bookmark's, is the number of one of the session's files; bookmarks are numbered
         * from 1 without gaps. Sessions without files are skipped.
         */
        fun fromProperties(properties: Properties): RecentSessions {
            val recent = sessions(properties, RECENT)
            val open = sessions(properties, OPEN_AT_EXIT)
            val selected = properties.getProperty(SELECTED)?.toIntOrNull()?.minus(1) ?: -1
            return RecentSessions(recent, if (open.isEmpty()) null else Reopen(open, selected))
        }

        /** [sessions] as [fromProperties] reads them, without the recent sessions whose files are all [gone]. */
        fun toProperties(sessions: RecentSessions): Properties {
            val properties = Properties()
            sessions.recent.filterNot(::gone).forEachIndexed { i, session ->
                write(properties, "$RECENT.${i + 1}.", session)
            }
            sessions.atExit?.let { atExit ->
                atExit.sessions.forEachIndexed { i, session -> write(properties, "$OPEN_AT_EXIT.${i + 1}.", session) }
                if (atExit.selected >= 0) properties[SELECTED] = "${atExit.selected + 1}"
            }
            return properties
        }

        private fun write(properties: Properties, prefix: String, session: OpenSession) {
            session.paths.forEachIndexed { i, path -> properties["${prefix}file.${i + 1}"] = path }
            session.parsers.forEachIndexed { i, parser ->
                if (parser != null) Settings.writeParser(properties, "${prefix}parser.${i + 1}.", parser)
            }
            session.shifts.forEachIndexed { i, shift ->
                if (shift != 0L) properties["${prefix}shift.${i + 1}"] = TimestampShift.format(shift)
            }
            val filter = session.filter
            filter?.minimumLevel?.let { properties["${prefix}level"] = it.name }
            if (filter != null && filter.query.isNotBlank()) properties["${prefix}query"] = filter.query
            fun writeStart(name: String, start: RecordStart<String>?) {
                if (start == null) return
                val file = session.fileNumber(start) ?: return
                properties["$prefix$name.file"] = "$file"
                properties["$prefix$name.offset"] = "${start.offset}"
            }
            writeStart("lead", session.position?.lead)
            writeStart("top", session.position?.top)
            // Numbered without gaps, as reading stops at the first one missing.
            val bookmarks = session.position?.bookmarks.orEmpty().filter { session.fileNumber(it) != null }
            bookmarks.forEachIndexed { i, start -> writeStart("bookmark.${i + 1}", start) }
            session.traceColumn?.let { properties["${prefix}traces"] = it }
        }

        /** The sessions stored under `[name].N.`, in the order of N. */
        private fun sessions(properties: Properties, name: String): List<OpenSession> {
            val key = Regex("""${Regex.escape(name)}\.(\d+)\.file\.1""")
            val numbers = properties.stringPropertyNames()
                .mapNotNull { key.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                .sorted()
            return numbers.map { read(properties, "$name.$it.") }
        }

        private fun read(properties: Properties, prefix: String): OpenSession {
            val paths = generateSequence(1) { it + 1 }
                .map { properties.getProperty("${prefix}file.$it") }
                .takeWhile { it != null }
                .map { it!! }
                .toList()
            val parsers = paths.indices.map { Settings.readParser(properties, "${prefix}parser.${it + 1}.") }
            val shifts = paths.indices.map { TimestampShift.read(properties.getProperty("${prefix}shift.${it + 1}")) }
            val levelName = properties.getProperty("${prefix}level")
            val level = Level.entries.firstOrNull { it.name == levelName }
            val query = properties.getProperty("${prefix}query").orEmpty()
            val filter = SavedFilter("", level, query).takeUnless { it.isEmpty }
            fun readStart(name: String): RecordStart<String>? {
                val file = properties.getProperty("$prefix$name.file")?.toIntOrNull()
                val offset = properties.getProperty("$prefix$name.offset")?.toLongOrNull()
                return OpenSession.startIn(paths, file, offset)
            }
            val lead = readStart("lead")
            val top = readStart("top")
            val bookmarks = generateSequence(1) { it + 1 }
                .takeWhile { properties.getProperty("${prefix}bookmark.$it.offset") != null }
                .mapNotNull { readStart("bookmark.$it") }
                .toList()
            val position = SessionPosition.of(lead, top, bookmarks)
            val traceColumn = properties.getProperty("${prefix}traces")
            return OpenSession(paths, parsers, filter, position, OpenSession.keptShifts(shifts), traceColumn)
        }
    }
}
