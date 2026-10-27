package io.github.rastislavpapp.logxy.core.open

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.merge.TimestampShift
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.session.RecordStart
import io.github.rastislavpapp.logxy.core.session.SessionPosition
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64
import java.util.Properties

/**
 * A session to open: its [paths] (extracted files by their zip or compressed file, as written by
 * `ArchiveOrigin`), the parser for each ([parsers], null to detect it), its [filter], and the [position] to go
 * back to once it is loaded, whose files are among [paths].
 */
data class OpenSession(
    val paths: List<String>,
    val parsers: List<Parser?>,
    val filter: SavedFilter?,
    val position: SessionPosition<String>? = null,
    /** The timestamp shift of each of [paths] in nanoseconds; empty if none has one. */
    val shifts: List<Long> = emptyList(),
) {
    /** Whether this session has the same files as [other], in any order. */
    fun sameFiles(other: OpenSession) = paths.toSet() == other.paths.toSet()

    /**
     * The number of [start]'s file among [paths], from 1, as stored sessions and restart arguments write a
     * position's file; null if it is none of them.
     */
    fun fileNumber(start: RecordStart<String>): Int? {
        val index = paths.indexOf(start.file)
        return if (index < 0) null else index + 1
    }

    companion object {
        /**
         * The start at [offset] in the file numbered [file] among [paths], as [fileNumber] numbers them; null if
         * either is missing or there is no such file.
         */
        fun startIn(paths: List<String>, file: Int?, offset: Long?): RecordStart<String>? {
            val path = if (file == null) null else paths.getOrNull(file - 1)
            if (path == null || offset == null) return null
            return RecordStart(path, offset)
        }

        /** [shifts] as [OpenSession.shifts] keeps them: empty if none is a shift. */
        fun keptShifts(shifts: List<Long>): List<Long> = if (shifts.all { it == 0L }) emptyList() else shifts
    }
}

/** The sessions to open again, and the index of the one to select. */
data class Reopen(val sessions: List<OpenSession>, val selected: Int)

/** Command-line arguments that carry the open sessions over a restart. */
object SessionArgs {

    private const val SESSION_ARG = "--tab"
    private const val SELECT_ARG = "--select"
    private const val LEVEL_ARG = "--level"
    private const val QUERY_ARG = "--query"
    private const val PARSERS_ARG = "--parsers"
    private const val LEAD_ARG = "--lead"
    private const val TOP_ARG = "--top"
    private const val SHIFTS_ARG = "--shifts"

    /**
     * Arguments that make the next start reopen [sessions], each with its filter and chosen parsers, and select
     * the tab at [selected] (-1 for none); [read] reads them back.
     */
    fun write(sessions: List<OpenSession>, selected: Int): List<String> = buildList {
        for (session in sessions) {
            add(SESSION_ARG)
            addAll(session.paths)
            session.filter?.minimumLevel?.let {
                add(LEVEL_ARG)
                add(it.name)
            }
            // Encoded, as Windows command lines do not reliably keep the quotes queries are full of.
            session.filter?.query?.takeIf { it.isNotBlank() }?.let {
                add(QUERY_ARG)
                add(encode(it))
            }
            if (session.parsers.any { it != null }) {
                val properties = Properties()
                session.parsers.forEachIndexed { i, parser ->
                    if (parser != null) Settings.writeParser(properties, "${i + 1}.", parser)
                }
                add(PARSERS_ARG)
                add(encode(StringWriter().also { properties.store(it, null) }.toString()))
            }
            addStart(LEAD_ARG, session, session.position?.lead)
            addStart(TOP_ARG, session, session.position?.top)
            if (session.shifts.isNotEmpty()) {
                add(SHIFTS_ARG)
                add(session.shifts.joinToString(",") { TimestampShift.format(it) })
            }
        }
        if (selected >= 0) add(SELECT_ARG).also { add(selected.toString()) }
    }

    /** The sessions in [args] written by [write]; null if they are not, e.g. paths given on the command line. */
    fun read(args: List<String>): Reopen? {
        if (args.firstOrNull() != SESSION_ARG) return null
        class Group(
            val paths: MutableList<String> = ArrayList(),
            var level: Level? = null,
            var query: String = "",
            var parsers: List<Parser?>? = null,
            var lead: RecordStart<String>? = null,
            var top: RecordStart<String>? = null,
            var shifts: List<Long> = emptyList(),
        ) {
            fun session(): OpenSession {
                val filter = SavedFilter("", level, query).takeUnless { it.isEmpty }
                val position = if (lead == null && top == null) null else SessionPosition(lead, top)
                return OpenSession(paths, parsers ?: paths.map { null }, filter, position, shifts)
            }
        }
        val groups = ArrayList<Group>()
        var selected = 0
        val iterator = args.iterator()
        fun value() = if (iterator.hasNext()) iterator.next() else ""

        // The start written after LEAD_ARG or TOP_ARG: its file's number among the group's paths, and its offset.
        fun start(group: Group): RecordStart<String>? {
            val file = value().toIntOrNull()
            val offset = value().toLongOrNull()
            return OpenSession.startIn(group.paths, file, offset)
        }
        while (iterator.hasNext()) {
            when (val arg = iterator.next()) {
                SESSION_ARG -> groups += Group()
                LEVEL_ARG -> {
                    val level = value()
                    groups.last().level = Level.entries.firstOrNull { it.name == level }
                }
                QUERY_ARG -> groups.last().query = decode(value()).orEmpty()
                PARSERS_ARG -> groups.last().let { group ->
                    val properties = Properties().apply { load(StringReader(decode(value()).orEmpty())) }
                    group.parsers = group.paths.indices.map { Settings.readParser(properties, "${it + 1}.") }
                }
                LEAD_ARG -> groups.last().let { it.lead = start(it) }
                TOP_ARG -> groups.last().let { it.top = start(it) }
                SHIFTS_ARG -> groups.last().let { group ->
                    val texts = value().split(',')
                    val shifts = group.paths.indices.map { TimestampShift.read(texts.getOrNull(it)) }
                    group.shifts = OpenSession.keptShifts(shifts)
                }
                SELECT_ARG -> selected = value().toIntOrNull() ?: 0
                else -> groups.last().paths.add(arg)
            }
        }
        return Reopen(groups.map { it.session() }, selected)
    }

    /** Adds [start] after [arg] as the number of its file among [session]'s paths, from 1, and its offset. */
    private fun MutableList<String>.addStart(arg: String, session: OpenSession, start: RecordStart<String>?) {
        if (start == null) return
        val file = session.fileNumber(start) ?: return
        addAll(listOf(arg, "$file", "${start.offset}"))
    }

    private fun encode(text: String) = Base64.getUrlEncoder().encodeToString(text.toByteArray())

    private fun decode(text: String) = try {
        String(Base64.getUrlDecoder().decode(text))
    } catch (_: IllegalArgumentException) {
        null
    }
}
