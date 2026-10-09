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
import io.github.rastislavpapp.logxy.core.log.Role
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
import java.util.concurrent.atomic.AtomicReference

/** A session's state for a status bar; [records] is null while there is nothing to show (indexing, errors). */
data class SessionStatus(
    val size: Long? = null,
    val records: Int? = null,
    val shown: Int? = null,
    val unit: String = "record",
    val live: Boolean = false,
    val following: Boolean = false,
    val indexMillis: Long? = null,
    val message: String? = null,
)

/** What a [LogSession] tells its view; always called on the session's scope, i.e. the UI thread. */
interface SessionListener {
    /** Indexing (re)started; [progress] is updated from other threads. */
    fun indexing(progress: IndexProgress)

    /** The file could not be shown. */
    fun problem(title: String, detail: String, hint: String?)

    /** [log] is ready to be shown; the view applies its filter next. */
    fun loaded(log: LogData)

    /** The format in use, or how it was chosen, changed. */
    fun formatChanged()

    /** Records were appended to the log, which had [oldRecordCount] before; with a filter, [rowsExtended] follows. */
    fun recordsAppended(oldRecordCount: Int)

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
 * One open file without its UI: indexes it in the background, follows changes to it, and filters it.
 * Not thread-safe: use it, and run [scope], on one thread (the UI thread); [listener] is called there.
 */
class LogSession(
    val path: Path,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val listener: SessionListener,
) {
    private var job: Job? = null
    private val settingsListener = { settingsChanged() }

    /** The format the user picked, or null to detect it. */
    var chosenFormat: LogFormat? = null
        private set

    /** The format in use (or last used), chosen or detected. */
    var format: LogFormat = LogFormat.Raw
        private set

    /** Whether [format] was determined, by the user or by detection. */
    var formatDetected = false
        private set

    var log: LogData? = null
        private set

    private var engine: RecordFilter? = null
    private var activeFilter: Filter? = null
    private var filterJob: Job? = null

    /** The rows shown, or null for all records. */
    var shown: FilteredRows? = null
        private set

    // How much of the log the shown filter result covers: records, and bytes (the last record may grow without new ones).
    private var filteredRecords = 0
    private var filteredBytes = 0L

    private var activity = FileActivity.STATIC
    private var indexMillis = 0L
    private var problem: String? = null

    /** Whether the file looks like it is being written to. */
    val isLive get() = activity == FileActivity.LIVE

    /** Keep the end of the log in view; turned on for live files. */
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

    /** Uses [format] from now on, or detects the format if null; reads the file again. */
    fun useFormat(format: LogFormat?) {
        if (format == chosenFormat) return
        chosenFormat = format
        listener.formatChanged()
        reload()
    }

    /** Starts over with the file now at [path], e.g. after rotation, truncation or a format change. */
    fun reload() {
        job?.cancel()
        closeLog()
        launchLoad()
    }

    /**
     * Shows only records of [minimumLevel] or more severe (all if null) that match [query]. Throws
     * [QueryException] if the query does not fit this log. Does nothing until the log is loaded.
     */
    fun applyFilter(minimumLevel: Level?, query: Query.Group) {
        val log = log ?: return
        val parts = ArrayList<Filter>()
        val levelField = log.schema.fields.firstOrNull { it.role == Role.LEVEL }
        if (minimumLevel != null && levelField != null) parts += LevelCondition.atLeast(levelField.name, minimumLevel)
        val compiled = QueryCompiler(log.schema).compile(query)
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

    /** Cancels running work and releases the file. */
    fun dispose() {
        settings.removeListener(settingsListener)
        job?.cancel()
        closeLog()
    }

    private fun launchLoad() {
        job = scope.launch {
            val reference = load() ?: return@launch
            watch(reference)
        }
    }

    /** Indexes the file; returns the snapshot the index corresponds to, or null on error. */
    private suspend fun load(): FileSnapshot? {
        val progress = IndexProgress()
        listener.indexing(progress)
        setStatus(SessionStatus(message = "Indexing…"))
        try {
            val started = System.nanoTime()
            // Taken before indexing, so a file replaced meanwhile shows up as a change on the first poll.
            val snapshot = withContext(Dispatchers.IO) { FileSnapshot.take(path) }
                ?: throw IOException("File not found")
            val preferred = settings.current.formats
            format = chosenFormat ?: withContext(Dispatchers.IO) { LogFormat.detect(path, preferred) }
            formatDetected = true
            listener.formatChanged()
            // Hand the result over outside the coroutine, so a cancellation racing with completion cannot leak it.
            val built = AtomicReference<LogData>()
            try {
                val levels = settings.current.levels
                withContext(Dispatchers.IO) { built.set(format.open(path, progress, levels)) }
            } catch (e: CancellationException) {
                built.getAndSet(null)?.close()
                throw e
            }
            val loaded = built.get()
            indexMillis = (System.nanoTime() - started) / 1_000_000
            log = loaded
            engine = FilterEngine(loaded)
            val currentSize = withContext(Dispatchers.IO) { Files.size(path) }
            activity = FileActivity.classify(snapshot.lastModified, snapshot.size, currentSize)
            problem = null
            listener.loaded(loaded)
            if (activity == FileActivity.LIVE) follow = true
            updateStatus()
            return snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: FormatMismatchException) {
            listener.problem(
                "${path.fileName} does not match the parser “${format.name}”.",
                e.message.orEmpty(),
                "Choose another parser in the toolbar, or Raw text.",
            )
            setStatus(SessionStatus(message = "Parser does not match"))
            return null
        } catch (e: Exception) {
            listener.problem("Cannot open ${path.fileName}.", e.message ?: e.javaClass.simpleName, null)
            setStatus(SessionStatus(message = "Error"))
            return null
        }
    }

    private suspend fun watch(reference: FileSnapshot) {
        var known = reference
        while (true) {
            delay(if (activity == FileActivity.LIVE) LIVE_POLL_MILLIS else STATIC_POLL_MILLIS)
            val log = log ?: return
            try {
                val current = withContext(Dispatchers.IO) { FileSnapshot.take(path) }
                when (known.changeTo(current, log.indexedSize)) {
                    FileChange.MISSING -> {
                        problem = "file not found"
                        updateStatus()
                        continue
                    }
                    FileChange.SHRUNK, FileChange.REPLACED -> {
                        reload()
                        return
                    }
                    FileChange.GROWN -> {
                        val appended = withContext(Dispatchers.IO) { log.readAppended(current!!.size) }
                        val oldRecordCount = log.recordCount
                        log.append(appended)
                        listener.recordsAppended(oldRecordCount)
                        if (activeFilter != null) catchUpFilter()
                        if (activity == FileActivity.STATIC) {
                            activity = FileActivity.LIVE
                            follow = true
                        }
                    }
                    FileChange.UNCHANGED ->
                        activity = FileActivity.classify(current!!.lastModified, current.size, current.size)
                }
                known = current!!
                problem = null
            } catch (e: IOException) {
                // Closing the tab closes the file under a running read.
                currentCoroutineContext().ensureActive()
                problem = e.message ?: e.javaClass.simpleName
            }
            updateStatus()
        }
    }

    private fun settingsChanged() {
        val settings = settings.current
        val log = log
        val levelsChanged = log != null && !log.isRaw && log.levelMapping != settings.levels
        log?.levelMapping = settings.levels
        if (levelsChanged) listener.levelMappingChanged()
        // A saved format this session uses was edited: read the file with the new definition.
        val edited = chosenFormat?.let { chosen -> settings.formats.firstOrNull { it.name == chosen.name && it != chosen } }
        if (edited != null) chosenFormat = edited
        // Only renamed: nothing to read again, just show the new name.
        chosenFormat?.let { renamed(it, settings.formats) }?.let { chosenFormat = it }
        renamed(format, settings.formats)?.let { format = it }
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
            if (shown != null || !keepView) {
                shown = null
                listener.rowsReplaced(null, keepView)
                updateStatus()
            }
            return
        }
        filterJob = scope.launch {
            val result = evaluation.run()
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
        val log = log ?: return
        val rows = shown ?: return
        if (filterJob?.isActive == true || filteredBytes >= log.indexedSize) return
        // Records covered then may have grown since (the last one of a log), so they are filtered again.
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

    private fun setStatus(status: SessionStatus) {
        this.status = status
        listener.statusChanged(status)
    }

    private fun updateStatus() {
        val log = log ?: return
        setStatus(
            SessionStatus(
                size = log.indexedSize,
                records = log.recordCount,
                shown = shown?.size,
                unit = if (log.isRaw) "line" else "record",
                live = activity == FileActivity.LIVE,
                following = follow,
                indexMillis = indexMillis,
                message = problem,
            ),
        )
    }

    private fun closeLog() {
        filterJob?.cancel()
        filteredRecords = 0
        filteredBytes = 0L
        shown = null
        activeFilter = null
        log?.close()
        log = null
        engine = null
    }

    private companion object {
        const val LIVE_POLL_MILLIS = 500L
        const val STATIC_POLL_MILLIS = 2000L
    }
}
