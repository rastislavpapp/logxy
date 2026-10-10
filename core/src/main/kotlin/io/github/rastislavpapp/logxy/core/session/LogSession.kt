package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.FileActivity
import io.github.rastislavpapp.logxy.core.FileChange
import io.github.rastislavpapp.logxy.core.FileSnapshot
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.FormatMismatchException
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import io.github.rastislavpapp.logxy.core.parse.open
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryCompiler
import io.github.rastislavpapp.logxy.core.query.QueryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** A session's state for a status bar; [records] is null while there is nothing to show (indexing, errors). */
data class SessionStatus(
    val size: Long? = null,
    val records: Int? = null,
    val shown: Int? = null,
    val unit: String = "record",
    val live: Boolean = false,
    val following: Boolean = false,
    /** A filter over all records is taking a while; [shown] is still the previous result. */
    val filtering: Boolean = false,
    val indexMillis: Long? = null,
    val message: String? = null,
)

/** What a [LogSession] tells its view; always called on the session's scope, i.e. the UI thread. */
interface SessionListener {
    /** Indexing (re)started; [fraction] tells how far it got, from 0 to 1, and may be called on any thread. */
    fun indexing(fraction: () -> Double)

    /** The files could not be shown. */
    fun problem(title: String, detail: String, hint: String?)

    /** [records] are ready to be shown; the view applies its filter next. */
    fun loaded(records: Records)

    /** [paths] have no timestamps, so they were left out of the merge; the view may open them on their own. */
    fun notMergeable(paths: List<Path>)

    /** The formats in use, or how they were chosen, changed. */
    fun formatChanged()

    /**
     * Records were appended; there were [oldRecordCount] before. The records at [changed] may have grown
     * (the last record of each file that grew). With a filter, [rowsExtended] follows.
     */
    fun recordsAppended(oldRecordCount: Int, changed: IntArray)

    /** All shown rows changed: [rows] with a filter, all records without. [keepView] keeps the rows on screen in place. */
    fun rowsReplaced(rows: FilteredRows?, keepView: Boolean)

    /** The filtered rows from position [firstChanged] on changed (new records); there were [oldSize] rows before. */
    fun rowsExtended(firstChanged: Int, oldSize: Int)

    /** The level mapping changed; shown levels may have different colors. */
    fun levelMappingChanged()

    fun followChanged(follow: Boolean)

    fun statusChanged(status: SessionStatus)
}

/**
 * One or more open files without their UI: indexes them in the background, merges several by time
 * (see [MergedLog]), follows changes to them, and filters them. Not thread-safe: use it, and run
 * [scope], on one thread (the UI thread); [listener] is called there.
 */
class LogSession(
    paths: List<Path>,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val listener: SessionListener,
) {
    constructor(path: Path, scope: CoroutineScope, settings: SettingsStore, listener: SessionListener) :
        this(listOf(path), scope, settings, listener)

    private class Source(val path: Path) {
        var format: LogFormat = LogFormat.Raw
        var log: LogData? = null
        var known: FileSnapshot? = null
        var activity = FileActivity.STATIC
    }

    init {
        require(paths.isNotEmpty()) { "A session needs a file" }
    }

    private var sources = paths.map { Source(it) }
    private var job: Job? = null
    private val settingsListener = { settingsChanged() }

    /** The files shown; files of a merge that turned out to have no timestamps are left out. */
    val paths: List<Path> get() = sources.map { it.path }

    /** Several files merged into one view. */
    val isMerged get() = sources.size > 1

    /** The format the user picked for a single file, or null to detect it. Merged files are always detected. */
    var chosenFormat: LogFormat? = null
        private set

    /** The format in use (or last used) for each of [paths]. */
    val formats: List<LogFormat> get() = sources.map { it.format }

    /** The format of the first (for a single file: the) file. */
    val format: LogFormat get() = sources.first().format

    /** Whether the formats were determined, by the user or by detection. */
    var formatDetected = false
        private set

    /** What is shown: the log of a single file, or a [MergedLog]. */
    var records: Records? = null
        private set

    private var engine: RecordFilter? = null
    private var activeFilter: Filter? = null
    private var filterJob: Job? = null

    /** The rows shown, or null for all records. */
    var shown: FilteredRows? = null
        private set

    // How much of the records the shown filter result covers: records, and bytes (the last record may grow without new ones).
    private var filteredRecords = 0
    private var filteredBytes = 0L
    private var filtering = false
    private var running: PreparedFilter? = null

    /** How far the filter over all records got, from 0 to 1, while [SessionStatus.filtering]. */
    val filterProgress: Double get() = running?.progress ?: 0.0

    private var indexMillis = 0L
    private var problem: String? = null

    /** Whether a file looks like it is being written to. */
    val isLive get() = sources.any { it.activity == FileActivity.LIVE }

    /** Keep the end of the records in view; turned on for live files. */
    var follow = false
        set(value) {
            if (field == value) return
            field = value
            listener.followChanged(value)
            updateStatus()
        }

    var status = SessionStatus(message = "Indexing…")
        private set

    fun start() {
        settings.addListener(settingsListener)
        launchLoad()
    }

    /** Uses [format] for a single file from now on, or detects the format if null; reads the file again. */
    fun useFormat(format: LogFormat?) {
        require(!isMerged) { "Merged files always use detected formats" }
        if (format == chosenFormat) return
        chosenFormat = format
        listener.formatChanged()
        reload()
    }

    /** Starts over with the files now at [paths], e.g. after rotation, truncation or a format change. */
    fun reload() {
        job?.cancel()
        closeLogs()
        launchLoad()
    }

    /**
     * Shows only records of [minimumLevel] or more severe (all if null) that match [query]. Throws
     * [QueryException] if the query does not fit these records. Does nothing until they are loaded.
     */
    fun applyFilter(minimumLevel: Level?, query: Query.Group) {
        val records = records ?: return
        val parts = ArrayList<Filter>()
        val levelField = records.schema.fields.firstOrNull { it.role == Role.LEVEL }
        if (minimumLevel != null && levelField != null) parts += LevelCondition.atLeast(levelField.name, minimumLevel)
        val compiled = QueryCompiler(records.schema).compile(query)
        // Only placeholders (empty values): nothing to filter.
        if (compiled != Filter.And(emptyList())) parts += compiled
        val filter = if (parts.isEmpty()) null else Filter.And(parts)
        val evaluation = try {
            filter?.let { engine?.prepare(it) }
        } catch (e: IllegalArgumentException) {
            throw QueryException(e.message ?: "Invalid filter")
        }
        activeFilter = filter
        runFilter(keepView = false, evaluation)
    }

    /** Cancels running work and releases the files. */
    fun dispose() {
        settings.removeListener(settingsListener)
        job?.cancel()
        closeLogs()
    }

    private fun launchLoad() {
        job = scope.launch {
            if (load()) watch()
        }
    }

    /** Indexes the files and merges them if there are several; returns false on error. */
    private suspend fun load(): Boolean {
        val sizes = withContext(Dispatchers.IO) { sources.map { runCatching { Files.size(it.path) }.getOrDefault(0L) } }
        val progresses = sources.map { IndexProgress() }
        val total = maxOf(1L, sizes.sum())
        listener.indexing { (progresses.sumOf { it.bytesDone }.toDouble() / total).coerceIn(0.0, 1.0) }
        setStatus(SessionStatus(message = "Indexing…"))
        // Logs opened so far, closed if loading is cancelled or fails.
        val opened = Collections.synchronizedList(ArrayList<LogData>())
        var current = sources.first()
        try {
            val started = System.nanoTime()
            val preferred = settings.current.formats
            val levels = settings.current.levels
            for ((i, source) in sources.withIndex()) {
                current = source
                // Taken before indexing, so a file replaced meanwhile shows up as a change on the first poll.
                source.known = withContext(Dispatchers.IO) { FileSnapshot.take(source.path) }
                    ?: throw IOException("${source.path.fileName}: file not found")
                val chosen = if (isMerged) null else chosenFormat
                source.format = chosen ?: withContext(Dispatchers.IO) { LogFormat.detect(source.path, preferred) }
                withContext(Dispatchers.IO) { opened += source.format.open(source.path, progresses[i], levels) }
                source.log = opened.last()
            }
            formatDetected = true
            listener.formatChanged()

            if (isMerged) {
                val unmergeable = sources.filter { it.log!!.schema.indexOf(Role.TIMESTAMP) < 0 }
                if (unmergeable.isNotEmpty()) {
                    unmergeable.forEach { it.log!!.close() }
                    opened.removeAll(unmergeable.map { it.log!! }.toSet())
                    sources = sources - unmergeable.toSet()
                    listener.notMergeable(unmergeable.map { it.path })
                    listener.formatChanged()
                }
            }
            val view: Records = when {
                sources.isEmpty() -> {
                    listener.problem("None of these files has timestamps to merge by.", "They are opened in their own tabs.", null)
                    setStatus(SessionStatus(message = "Nothing to merge"))
                    return false
                }
                sources.size == 1 -> sources.single().log!!
                else -> MergedLog.build(sources.map { it.log!! })
            }
            engine = if (view is MergedLog) MergedFilter(view) else FilterEngine(view as LogData)
            records = view
            indexMillis = (System.nanoTime() - started) / 1_000_000
            withContext(Dispatchers.IO) {
                for (source in sources) {
                    val known = source.known!!
                    source.activity = FileActivity.classify(known.lastModified, known.size, Files.size(source.path))
                }
            }
            problem = null
            listener.loaded(view)
            if (isLive) follow = true
            updateStatus()
            return true
        } catch (e: CancellationException) {
            opened.forEach { it.close() }
            throw e
        } catch (e: FormatMismatchException) {
            opened.forEach { it.close() }
            listener.problem(
                "${current.path.fileName} does not match the parser “${current.format.name}”.",
                e.message.orEmpty(),
                "Choose another parser in the toolbar, or Raw text.",
            )
            setStatus(SessionStatus(message = "Parser does not match"))
            return false
        } catch (e: Exception) {
            opened.forEach { it.close() }
            listener.problem("Cannot open ${current.path.fileName}.", e.message ?: e.javaClass.simpleName, null)
            setStatus(SessionStatus(message = "Error"))
            return false
        }
    }

    private suspend fun watch() {
        while (true) {
            delay(if (isLive) LIVE_POLL_MILLIS else STATIC_POLL_MILLIS)
            val records = records ?: return
            try {
                val oldRecordCount = records.recordCount
                val changed = ArrayList<Int>()
                val problems = ArrayList<String>()
                var grown = false
                var promoted = false
                for ((part, source) in sources.withIndex()) {
                    val log = source.log ?: return
                    val current = withContext(Dispatchers.IO) { FileSnapshot.take(source.path) }
                    when (source.known!!.changeTo(current, log.indexedSize)) {
                        FileChange.MISSING -> {
                            problems += if (isMerged) "${source.path.fileName} not found" else "file not found"
                            continue
                        }
                        FileChange.SHRUNK, FileChange.REPLACED -> {
                            reload()
                            return
                        }
                        FileChange.GROWN -> {
                            if (log.recordCount > 0) changed += position(part, log.recordCount - 1)
                            val appended = withContext(Dispatchers.IO) { log.readAppended(current!!.size) }
                            log.append(appended)
                            grown = true
                            if (source.activity == FileActivity.STATIC) {
                                source.activity = FileActivity.LIVE
                                promoted = true
                            }
                        }
                        FileChange.UNCHANGED ->
                            source.activity = FileActivity.classify(current!!.lastModified, current.size, current.size)
                    }
                    source.known = current!!
                }
                problem = problems.joinToString(", ").ifEmpty { null }
                if (grown) {
                    // A last record parsed differently now cannot keep its merged place: merge again.
                    if (records is MergedLog && !records.appendNew()) {
                        reload()
                        return
                    }
                    listener.recordsAppended(oldRecordCount, changed.toIntArray())
                    if (activeFilter != null) catchUpFilter()
                    if (promoted) follow = true
                }
            } catch (e: IOException) {
                // Closing the tab closes the files under a running read.
                currentCoroutineContext().ensureActive()
                problem = e.message ?: e.javaClass.simpleName
            }
            updateStatus()
        }
    }

    /** Where [record] of the file at [part] is shown. */
    private fun position(part: Int, record: Int): Int = (records as? MergedLog)?.position(part, record) ?: record

    private fun settingsChanged() {
        val settings = settings.current
        var levelsChanged = false
        for (source in sources) {
            val log = source.log ?: continue
            if (!log.isRaw && log.levelMapping != settings.levels) levelsChanged = true
            log.levelMapping = settings.levels
        }
        if (levelsChanged) listener.levelMappingChanged()
        // A saved format this session uses was edited: read the file with the new definition.
        val edited = chosenFormat?.let { chosen -> settings.formats.firstOrNull { it.name == chosen.name && it != chosen } }
        if (edited != null) chosenFormat = edited
        // Only renamed: nothing to read again, just show the new name.
        chosenFormat?.let { renamed(it, settings.formats) }?.let { chosenFormat = it }
        for (source in sources) renamed(source.format, settings.formats)?.let { source.format = it }
        listener.formatChanged()
        if (edited != null) {
            reload()
        } else if (levelsChanged && activeFilter != null) {
            runFilter(keepView = true, activeFilter?.let { engine?.prepare(it) })
        }
    }

    /** The saved format that [format] became by a rename, i.e. with the same definition under a new name. */
    private fun renamed(format: LogFormat, saved: List<LogFormat>): LogFormat? {
        if (format == LogFormat.Raw || format in LogFormat.BUILT_IN || saved.any { it.name == format.name }) return null
        return saved.firstOrNull { candidate ->
            when (format) {
                is LogFormat.Pattern -> candidate is LogFormat.Pattern && candidate.pattern == format.pattern
                is LogFormat.Regex -> candidate is LogFormat.Regex && candidate.regex == format.regex &&
                    candidate.timestampPattern == format.timestampPattern
                LogFormat.Raw -> false
            }
        }
    }

    /** Runs [evaluation] of [activeFilter] over all records, or shows all records without a filter. */
    private fun runFilter(keepView: Boolean, evaluation: PreparedFilter?) {
        filterJob?.cancel()
        if (evaluation == null) {
            if (shown != null || !keepView || filtering) {
                filtering = false
                running = null
                shown = null
                listener.rowsReplaced(null, keepView)
                updateStatus()
            }
            return
        }
        running = evaluation
        filterJob = scope.launch {
            // Only slow filters say so; fast ones would just flash it.
            val indicator = launch {
                delay(FILTERING_INDICATOR_MILLIS)
                filtering = true
                updateStatus()
            }
            val result = evaluation.run()
            indicator.cancel()
            filtering = false
            running = null
            val rows = FilteredRows(result.rows)
            shown = rows
            filteredRecords = result.recordCount
            filteredBytes = result.indexedSize
            listener.rowsReplaced(rows, keepView)
            updateStatus()
            // Still the current job (a replaced one would have been cancelled), but done.
            filterJob = null
            catchUpFilter()
        }
    }

    /**
     * Filters what was appended since the shown result, keeping the result for older records. Never
     * cancels a running filter: whichever finishes calls this again, so nothing appended is missed.
     */
    private fun catchUpFilter() {
        val filter = activeFilter ?: return
        val rows = shown ?: return
        if (filterJob?.isActive == true || filteredBytes >= indexedSize()) return
        // Records covered then may have grown since (the last one of each file), so they are filtered again.
        val engine = engine ?: return
        val from = engine.changedFrom(filteredRecords)
        val evaluation = engine.prepare(filter, from)
        filterJob = scope.launch {
            val result = evaluation.run()
            val oldSize = rows.size
            val firstChanged = rows.replaceFrom(from, result.rows)
            filteredRecords = result.recordCount
            filteredBytes = result.indexedSize
            listener.rowsExtended(firstChanged, oldSize)
            updateStatus()
            filterJob = null
            catchUpFilter()
        }
    }

    private fun indexedSize(): Long = sources.sumOf { it.log?.indexedSize ?: 0L }

    private fun setStatus(status: SessionStatus) {
        this.status = status
        listener.statusChanged(status)
    }

    private fun updateStatus() {
        val records = records ?: return
        setStatus(
            SessionStatus(
                size = indexedSize(),
                records = records.recordCount,
                shown = shown?.size,
                unit = if (records.isRaw) "line" else "record",
                live = isLive,
                following = follow,
                filtering = filtering,
                indexMillis = indexMillis,
                message = problem,
            ),
        )
    }

    private fun closeLogs() {
        filterJob?.cancel()
        filteredRecords = 0
        filteredBytes = 0L
        filtering = false
        running = null
        shown = null
        activeFilter = null
        for (source in sources) {
            source.log?.close()
            source.log = null
        }
        records = null
        engine = null
    }

    private companion object {
        const val LIVE_POLL_MILLIS = 500L
        const val STATIC_POLL_MILLIS = 2000L
        const val FILTERING_INDICATOR_MILLIS = 200L
    }
}
