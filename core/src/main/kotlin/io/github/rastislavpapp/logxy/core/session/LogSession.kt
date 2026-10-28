package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.FileActivity
import io.github.rastislavpapp.logxy.core.FileChange
import io.github.rastislavpapp.logxy.core.FileSnapshot
import io.github.rastislavpapp.logxy.core.archive.ArchiveOrigin
import io.github.rastislavpapp.logxy.core.config.RowHighlight
import io.github.rastislavpapp.logxy.core.filter.ChunkListener
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.ByteChunkList
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.merge.TimestampShift
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.parse.ParserMismatchException
import io.github.rastislavpapp.logxy.core.parse.open
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryCompiler
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.core.template.TemplateCounts
import io.github.rastislavpapp.logxy.core.template.VariableSeries
import io.github.rastislavpapp.logxy.core.template.VariableValues
import io.github.rastislavpapp.logxy.core.trace.TraceIndex
import io.github.rastislavpapp.logxy.core.trace.Traces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(LogSession::class.java)

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

/** What a [LogSession] tells its UI; always called on the session's scope, i.e. the UI thread. */
interface SessionListener {
    /** Indexing (re)started; [fraction] tells how far it got, from 0 to 1, and may be called on any thread. */
    fun indexing(fraction: () -> Double)

    /** The files could not be shown. */
    fun problem(title: String, detail: String, hint: String?)

    /** [shown] is ready to be shown, all records for now; the UI applies its filter next. */
    fun loaded(shown: ShownLog)

    /** The shown log of the last [loaded] ended, before its files are closed: drop it and what was read from it. */
    fun shownEnded()

    /** [paths] have no timestamps, so they were left out of the merge; the UI may open them on their own. */
    fun notMergeable(paths: List<Path>)

    /** The parsers in use, or how they were chosen, changed. */
    fun parserChanged()

    /**
     * Records at [changed] may have more text now (the last record of each file that grew); their rows
     * stay where they are. New rows, if any, follow with [rowsChanged].
     */
    fun recordsGrew(changed: IntArray)

    /**
     * Appended records have a field no record had before, so the records' schema shows it now; [recordsGrew]
     * follows.
     */
    fun fieldsShown()

    /**
     * All shown rows changed, e.g. to a new filter's result; [keepView] keeps the rows on screen in place.
     * There were [oldSize] rows before, [recordBefore] tells which record each of them showed.
     */
    fun rowsReplaced(keepView: Boolean, oldSize: Int, recordBefore: (row: Int) -> Int)

    /** The shown rows from [firstChanged] on changed, e.g. records were appended; there were [oldSize] rows before. */
    fun rowsChanged(firstChanged: Int, oldSize: Int)

    /** The level mapping changed; shown levels may have different colors. */
    fun levelMappingChanged()

    /** [LogSession.histogram] changed; while counted anew, it fills up from its earliest times. */
    fun histogramChanged()

    /**
     * The records the search found, or whether it runs ([ShownLog.isSearching]), changed; see [LogSession.search].
     * A search started anew calls it for each chunk of records it decides, with [LogSession.foundHistogram] updated.
     */
    fun foundChanged()

    /**
     * The row highlights of the records from [fromRecord] on changed (see [ShownLog.rowHighlight]), e.g. they were
     * evaluated for appended records, or anew for changed settings.
     */
    fun rowHighlightsChanged(fromRecord: Int)

    /** [LogSession.follow], keeping the end of the files in view, was turned on or off. */
    fun followChanged(follow: Boolean)

    /** [LogSession.status] changed: sizes, counts, filtering, or a problem to show. */
    fun statusChanged(status: SessionStatus)
}

/**
 * One or more open files without their UI: indexes them in the background, merges several by time
 * (see [MergedLog]), follows changes to them, and filters them. Not thread-safe: use it, and run
 * [scope], on one thread (the UI thread); [listener] is called there.
 *
 * [origin] tells which files were extracted from zips or decompressed, for naming them. [chosen] holds
 * the parser the user picked for each of the paths, null (or missing) to detect it. [redetection] turns
 * on detecting the parser of a single file again while it reads as Raw text; a merge refuses it.
 */
class LogSession internal constructor(
    paths: List<Path>,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val listener: SessionListener,
    private val origin: (Path) -> ArchiveOrigin?,
    chosen: List<Parser?>,
    private val redetection: Redetection?,
    /** The timestamp shift of each of the paths of a merge in nanoseconds; 0 (or missing) for none. */
    shifts: List<Long> = emptyList(),
    /** Filters the records of a load; tests watch what it is asked. */
    private val engineOf: (LoadedLog) -> RecordFilter,
) {
    constructor(
        paths: List<Path>,
        scope: CoroutineScope,
        settings: SettingsStore,
        listener: SessionListener,
        origin: (Path) -> ArchiveOrigin? = { null },
        chosen: List<Parser?> = emptyList(),
        redetection: Redetection? = null,
        shifts: List<Long> = emptyList(),
    ) : this(paths, scope, settings, listener, origin, chosen, redetection, shifts, { it.newFilter() })

    constructor(path: Path, scope: CoroutineScope, settings: SettingsStore, listener: SessionListener) :
        this(listOf(path), scope, settings, listener)

    private class Source(val path: Path, var chosen: Parser?, var shift: Long) {
        var parser: Parser = Parser.Raw
        var log: LogData? = null
        var known: FileSnapshot? = null
        var activity = FileActivity.STATIC
    }

    init {
        require(paths.isNotEmpty()) { "A session needs a file" }
        require(redetection == null || paths.size == 1) { "A merge takes a parser per file" }
    }

    private val redetectionClock = redetection?.let(::RedetectionClock)

    private var sources = paths.mapIndexed { i, path ->
        Source(path, chosen.getOrNull(i), TimestampShift.of(shifts, i))
    }
        set(value) {
            field = value
            sourceNames = MergedLog.sourceNames(paths, origin)
        }
    private var job: Job? = null

    // Conflated: a burst of pushes while the files are read wakes the next poll once, which then reads it all.
    private val pushes = Channel<Unit>(Channel.CONFLATED)
    private val settingsListener = { settingsChanged() }

    /** The saved parsers before the latest change, to tell a detected saved parser from a guess of the same name. */
    private var savedParsers = settings.current.parsers

    /** The files shown; files of a merge that turned out to have no timestamps are left out. */
    val paths: List<Path> get() = sources.map { it.path }

    /** Short names of [paths], as the source field of a merge shows them. */
    var sourceNames: List<String> = MergedLog.sourceNames(paths, origin)
        private set

    /** Whether several files are merged into one session. */
    val isMerged get() = sources.size > 1

    /** The parser the user picked for a single file (the first one of a merge), or null to detect it. */
    val chosenParser: Parser? get() = sources.first().chosen

    /** The parser the user picked for each of [paths], null where it is detected. */
    val chosenParsers: List<Parser?> get() = sources.map { it.chosen }

    /** The timestamp shift of each of [paths] in nanoseconds, 0 for none; only a merge's files have one. */
    val shifts: List<Long> get() = sources.map { it.shift }

    /** The parser in use (or last used) for each of [paths]. */
    val parsers: List<Parser> get() = sources.map { it.parser }

    /** The parser of the first file, which for a single file is its only one. */
    val parser: Parser get() = sources.first().parser

    /** Whether the parsers were determined, by the user or by detection. */
    var parserDetected = false
        private set

    /** The records of the files loaded, null while there are none (indexing, errors). */
    internal var loaded: LoadedLog? = null
        private set

    internal val records: Records? get() = loaded?.records

    /** What is shown of the files loaded, null while there is nothing to show (indexing, errors). */
    var shown: ShownLog? = null
        private set

    private var engine: RecordFilter? = null

    // The variable values of each template variable over all records, and what they were counted over: the
    // loaded log and its record count.
    private val allValues = HashMap<Pair<String, Int>, VariableValues>()
    private var allValuesOf: Pair<LoadedLog, Int>? = null

    private var activeFilter: Filter? = null

    /** The evaluation of [activeFilter]; what it covers is what the shown filter result covers. */
    private val filterRun = LoadEvaluation(scope)

    /** Whether the rows shown are [activeFilter]'s result, or all records while there is no active filter. */
    private var filterShown = false

    private var filtering = false
    private var running: PreparedFilter? = null

    /** How far the filter over all records got, from 0 to 1, while [SessionStatus.filtering]. */
    val filterProgress: Double get() = running?.progress ?: 0.0

    /** The filter whose result the rows show, null for all records. */
    private var shownFilter: Filter? = null

    private var searchCondition: Filter? = null

    /** The evaluation of [searchCondition] in the shown log; what it covers is what the found records cover. */
    private val searchRun = LoadEvaluation(scope)
    private var searching: PreparedFilter? = null

    // The search [searchCondition] was made from, and, once its run is done, the search that found the found
    // records.
    private var searchSpec: TextSearch? = null
    private var foundSpec: TextSearch? = null

    // A search that only narrows the running one, started when that one is done.
    private var queuedSearch: TextSearch? = null

    /**
     * Whether the running search only looks among the records of the one before, which takes a moment: the
     * previous found records are still shown until it is done.
     */
    var searchNarrows = false
        private set

    /** How far the search over all rows got, from 0 to 1, while [ShownLog.isSearching]. */
    val searchProgress: Double get() = searching?.progress ?: 0.0

    /** Why the search failed, e.g. for lack of memory, so it no longer follows the rows; null if it did not. */
    var searchError: String? = null
        private set

    /**
     * Times of the found records; null if there is no search or the records have no timestamps. It fills up as a
     * search reports chunks, moving and widening on its own, unlike [histogram].
     */
    var foundHistogram: TimeHistogram? = null
        private set

    // Counts the times of the matches a running search reports, from the snapshots it searches.
    private var foundTimes: TimeCount? = null
    private var foundChunked = false

    /** The evaluation of the row highlights; what it covers is what [ShownLog.rowHighlight] covers. */
    private val highlightRun = LoadEvaluation(scope)

    /** The row highlights of the settings that were evaluated last, null while none were. */
    private var evaluatedHighlights: List<RowHighlight>? = null

    /** [evaluatedHighlights] compiled for the records: null for one that does not fit them. */
    private var highlightFilters = emptyList<Filter?>()

    /** Whether the row highlights of a new load wait for the filter's first result, so as not to delay it. */
    private var highlightsWait = false

    /** Times of the shown rows; null until they are counted, or if the records have no timestamps. */
    var histogram: TimeHistogram? = null
        private set

    // The times of the shown rows before [settledRows], which no longer change, counted apart from those of the
    // later rows, which still may.
    private var settled: TimeHistogram? = null
    private var settledRows = 0
    private var countJob: Job? = null
    private var counting: TimeCount? = null
    private var countAgain = false

    private var indexMillis = 0L
    private var problem: String? = null

    /** The problem reading the files that was logged last, so the polls that meet it again do not log it again. */
    private var loggedReadProblem: String? = null

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

    /** Starts the first load and listens to settings changes; call once, after the listener is ready for events. */
    fun start() {
        settings.addListener(settingsListener)
        redetectionClock?.start()
        launchLoad()
    }

    /**
     * Tells that nothing more will be written to the file, e.g. its process ended: a session with [Redetection]
     * detects its parser once more over the whole file if its window is still open. Callable from any thread.
     */
    fun writerEnded() {
        redetectionClock?.writerEnded()
        pushes.trySend(Unit)
    }

    /**
     * Tells that a file grew, so it is read now rather than at the next poll, which takes a noticeable moment even for
     * a live file. For a writer that knows when it appended, such as a console capturing a process's output. Callable
     * from any thread; the session still checks the files itself, so a push for a file that did not change is harmless.
     */
    fun grew() {
        pushes.trySend(Unit)
    }

    /** Uses [parser] for a single file from now on, or detects the parser if null; reads the file again. */
    fun useParser(parser: Parser?) {
        require(!isMerged) { "A merge takes a parser per file" }
        useParsers(listOf(parser))
    }

    /** Uses [parsers] for the files at [paths] from now on, detecting those that are null; reads the files again. */
    fun useParsers(parsers: List<Parser?>) {
        require(parsers.size == sources.size) { "${parsers.size} parsers for ${sources.size} files" }
        if (parsers == chosenParsers) return
        sources.forEachIndexed { i, source -> source.chosen = parsers[i] }
        listener.parserChanged()
        val picked = parsers.map { parser -> if (parser == null) "detect" else "“${parser.name}”" }
        reload("parser picked: " + picked.joinToString())
    }

    /**
     * Shifts the timestamps of each of a merge's [paths] by [shifts] nanoseconds from now on, and merges them again;
     * the files are read again too.
     */
    fun shiftTimestamps(shifts: List<Long>) {
        require(shifts.size == sources.size) { "${shifts.size} shifts for ${sources.size} files" }
        if (shifts == this.shifts) return
        sources.forEachIndexed { i, source -> source.shift = shifts[i] }
        val texts = shifts.map(TimestampShift::format)
        reload("timestamp shifts: " + texts.joinToString { it.ifEmpty { "none" } })
    }

    /** Starts over with the files now at [paths], e.g. after rotation, truncation or a parser change. */
    fun reload() = reload("Reload")

    /** [reload], logged with [reason]. */
    private fun reload(reason: String) {
        log.info("Reloading {}: {}", pathsText(), reason)
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
        // The same filter again, e.g. clearing an empty one: its result is shown or on its way.
        if (filter == activeFilter && (filterShown || running != null)) return
        val evaluation = try {
            filter?.let { engine?.prepare(it) }
        } catch (e: IllegalArgumentException) {
            throw QueryException(e.message ?: "Invalid filter")
        }
        activeFilter = filter
        runFilter(keepView = false, evaluation)
    }

    /**
     * Finds the shown rows that [search] matches into [ShownLog.foundCount] and the like, following the rows as
     * they change, also after a reload; null finds nothing. Throws [QueryException] for an invalid regex, which
     * finds nothing either.
     *
     * A search with more text typed after the previous one ([TextSearch.narrows]) only looks among the records
     * that one found, and keeps showing them meanwhile; if the previous one is still running, it waits for that.
     */
    fun search(search: TextSearch?) {
        try {
            val previous = searchSpec
            if (search != null && previous != null) {
                val running = searchRun.isRunning
                if (running && searchError == null && search == previous) {
                    queuedSearch = null
                    return
                }
                if (search.narrows(previous)) {
                    if (running && searchError == null) {
                        queuedSearch = search
                        return
                    }
                    if (!running && foundSpec == previous && searchError == null) {
                        searchSpec = search
                        searchCondition = search.condition()
                        searchShown(anew = true, narrow = true)
                        return
                    }
                }
            }
            searchSpec = search
            searchCondition = search?.condition()
            searchShown(anew = true)
        } catch (e: Exception) {
            if (e !is QueryException && e !is IllegalArgumentException) throw e
            searchSpec = null
            searchCondition = null
            searchShown(anew = true)
            throw e as? QueryException ?: QueryException(e.message ?: "Invalid search")
        }
    }

    /** How far [templateCounts] got with finding the templates of new records, from 0 to 1. */
    val templateProgress: Double get() = engine?.templateProgress ?: 1.0

    /**
     * Counts the shown rows by the template of their message (see
     * [io.github.rastislavpapp.logxy.core.template.Templates]); null if there is nothing loaded or the records have
     * no message. Cancelling the caller stops it.
     */
    suspend fun templateCounts(): TemplateCounts? {
        // Counting the times takes a fraction of finding the templates: let it have the threads first.
        while (true) countJob?.takeIf { it.isActive }?.join() ?: break
        val shown = shown ?: return null
        val engine = engine ?: return null
        if (shown.schema.indexOf(Role.MESSAGE) < 0) return null
        return engine.countTemplates(shown.rowArray, shown.rowCount)
    }

    /**
     * The variable values at each of [variables] in the shown rows whose message has the message template
     * [template], over time, read in one pass. Null if there is nothing loaded or the records have no message or
     * no time. Cancelling the caller stops it.
     */
    suspend fun variableSeries(template: String, variables: IntArray): List<VariableSeries>? {
        while (true) countJob?.takeIf { it.isActive }?.join() ?: break
        val shown = shown ?: return null
        val engine = engine ?: return null
        if (shown.schema.indexOf(Role.MESSAGE) < 0 || shown.schema.indexOf(Role.TIMESTAMP) < 0) return null
        return engine.variableSeries(shown.rowArray, shown.rowCount, template, variables)
    }

    /**
     * How often each variable value occurs at template variable [variable], counted from 0, in the shown rows
     * whose message has the message template [template]. Null if there is nothing loaded or the records have no
     * message. Cancelling the caller stops it.
     */
    suspend fun variableValues(template: String, variable: Int): VariableValues? {
        while (true) countJob?.takeIf { it.isActive }?.join() ?: break
        val shown = shown ?: return null
        val engine = engine ?: return null
        if (shown.schema.indexOf(Role.MESSAGE) < 0) return null
        return engine.variableValues(shown.rowArray, shown.rowCount, template, variable)
    }

    /**
     * As [variableValues], but over all records rather than the shown rows; kept until the records change, as the
     * filter editor asks again whenever a value is edited.
     */
    suspend fun allVariableValues(template: String, variable: Int): VariableValues? {
        while (true) countJob?.takeIf { it.isActive }?.join() ?: break
        val loaded = loaded ?: return null
        val engine = engine ?: return null
        if (loaded.records.schema.indexOf(Role.MESSAGE) < 0) return null
        val recordCount = loaded.records.recordCount
        if (allValuesOf != loaded to recordCount) {
            allValues.clear()
            allValuesOf = loaded to recordCount
        }
        val key = template to variable
        allValues[key]?.let { return it }
        val values = engine.variableValues(null, recordCount, template, variable)
        if (allValuesOf == loaded to recordCount) allValues[key] = values
        return values
    }

    /** The traces of the loaded records by the trace ids in [traceIndex]'s column, kept until the session reloads. */
    private var traceIndex: TraceIndex? = null
    private var traceIndexOf: Pair<LoadedLog, String>? = null

    /** How far [traces] got with reading new records, from 0 to 1. */
    val traceProgress: Double get() = traceIndex?.progress ?: 1.0

    /**
     * The traces of all records by the trace ids in the field named [column]; null if nothing is loaded or the
     * trace ids cannot come from that field (see [Traces.canHoldTraceIds]). Only the records appended since the last
     * call with the same column are read. Cancelling the caller stops it, keeping what it read.
     */
    suspend fun traces(column: String): Traces? {
        val loaded = loaded ?: return null
        val field = traceField(loaded.records.schema, column)
        if (field < 0) return null
        var index = traceIndex
        if (index == null || traceIndexOf != loaded to column) {
            index = TraceIndex(column, if (isMerged) sourceNames else emptyList())
            traceIndex = index
            traceIndexOf = loaded to column
        }
        return index.update(loaded.traceSource(field))
    }

    /** The template of [record]'s message; null if it has none, or nothing is loaded. */
    fun templateOf(record: Int): String? {
        val loaded = loaded ?: return null
        if (loaded.records.schema.indexOf(Role.MESSAGE) < 0) return null
        return loaded.templateOf(record).ifEmpty { null }
    }

    /** Where [record] of the loaded records starts; null if nothing is loaded or there is no such record. */
    fun startOf(record: Int): RecordStart<Path>? {
        val loaded = loaded ?: return null
        if (record !in 0..<loaded.records.recordCount) return null
        var start: RecordStart<Path>? = null
        loaded.sourceReader().spans(record, record + 1) { part, offset, _ ->
            start = RecordStart(loaded.parts[part].path, offset)
        }
        return start
    }

    /**
     * The loaded record that starts at [start] as [startOf] gave it; if the file changed since, e.g. it was
     * truncated or rotated, the last record starting before [start]. -1 if there is none: nothing is loaded, the
     * file is not one of the session's, or [start] lies past the end of the file.
     */
    fun recordAt(start: RecordStart<Path>): Int {
        val loaded = loaded ?: return -1
        val file = start.file.toAbsolutePath().normalize()
        val part = loaded.parts.indexOfFirst { it.path.toAbsolutePath().normalize() == file }
        if (part < 0) return -1
        val snapshot = loaded.parts[part].filterSnapshot()
        if (snapshot.count == 0 || start.offset >= snapshot.end) return -1
        val cursor = snapshot.cursor()
        cursor.moveTo(0)
        if (cursor.start > start.offset) return -1
        // The last record starting at or before the offset.
        var low = 0
        var high = snapshot.count - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            cursor.moveTo(middle)
            if (cursor.start <= start.offset) low = middle else high = middle - 1
        }
        return loaded.recordOf(part, low)
    }

    private val bookmarkStore = Bookmarks(::startOf, ::recordAt)

    // The records as bookmarks are found in: an appended record of a merge can come between earlier ones.
    private val bookmarkLoad: Any? get() = loaded?.let { it to it.records.recordCount }

    /** The bookmarked records of the loaded records, sorted; empty while nothing is loaded. */
    val bookmarks: IntArray get() = bookmarkStore.records(bookmarkLoad)

    /**
     * Where each bookmarked record starts, to keep the bookmarks for when the session opens again. It can be set
     * before the records are loaded, e.g. to the bookmarks of a recent session: the session finds their records once
     * it has loaded, and drops a bookmark whose record no longer starts where it did.
     */
    var bookmarkStarts: List<RecordStart<Path>>
        get() = bookmarkStore.starts(bookmarkLoad)
        set(value) = bookmarkStore.restore(value)

    /**
     * Removes the bookmarks of [records] if every one has one, else bookmarks them all, up to [MAX_BOOKMARKS]. Reads
     * only as many of [records] as it needs. Null, having done nothing, while nothing is loaded.
     */
    fun toggleBookmarks(records: Sequence<Int>): BookmarkToggle? {
        val load = bookmarkLoad ?: return null
        return bookmarkStore.toggle(load, records)
    }

    fun removeAllBookmarks() = bookmarkStore.clear()

    /** The bookmarked records the shown log shows, sorted; the filter hides the others, which keep their bookmark. */
    fun shownBookmarks(): IntArray {
        val shown = shown ?: return IntArray(0)
        return bookmarks.filter { record ->
            val row = shown.rowOf(record)
            row >= 0 && shown.recordAt(row) == record
        }.toIntArray()
    }

    /** Cancels running work and releases the files. */
    fun dispose() {
        settings.removeListener(settingsListener)
        job?.cancel()
        closeLogs()
    }

    private fun launchLoad() {
        job = scope.launch {
            // The watch's reloads run in this coroutine rather than by [reload]: a job that cancels itself and
            // launches the next load may lose it, as IntelliJ ties what is dispatched to the EDT to the calling
            // coroutine.
            while (load()) {
                // The files must be read again, e.g. truncated, or redetection found their parser.
                val reason = watch() ?: break
                log.info("Reloading {}: {}", pathsText(), reason)
                closeLogs()
            }
        }
    }

    /** Indexes the files and merges them if there are several; returns false on error. */
    private suspend fun load(): Boolean {
        val sizes = withContext(Dispatchers.IO) { sources.map { runCatching { Files.size(it.path) }.getOrDefault(0L) } }
        val loading = sources.mapIndexed { i, source ->
            val chosen = source.chosen
            val parser = if (chosen == null) "detecting the parser" else "parser “${chosen.name}”"
            "${source.path} (${formatByteSize(sizes[i])}, $parser)"
        }
        log.info("Loading {}", loading.joinToString())
        val progresses = sources.map { IndexProgress() }
        val total = maxOf(1L, sizes.sum())
        listener.indexing { (progresses.sumOf { it.bytesDone }.toDouble() / total).coerceIn(0.0, 1.0) }
        setStatus(SessionStatus(message = "Indexing…"))
        // Logs opened so far, closed if loading is cancelled or fails.
        val opened = Collections.synchronizedList(ArrayList<LogData>())
        // The file whose error is reported; the others of a merge are only cancelled because of it.
        val failed = AtomicReference<Source>()
        fun current() = failed.get() ?: sources.first()
        try {
            val started = System.nanoTime()
            val preferred = settings.current.parsers
            val builtIn = settings.current.builtInParsers
            val levels = settings.current.levels
            // A merge filters its files in parallel, so each needs only its share of channels; this bounds file
            // handles. At least a few each, as a small file is parsed by one thread and that slows down a lot with
            // fewer.
            val defaults = IndexOptions()
            val readChannels = maxOf(minOf(4, defaults.parallelism), defaults.parallelism / sources.size)
            // A process's output, which redetection is for, may start with lines before its first record.
            val preamble = redetection != null && sources.first().chosen == null
            val options = defaults.copy(readChannels = readChannels, preamble = preamble)
            // Files under the split size are parsed by one thread each, so a folder of them needs several at once.
            val permits = Semaphore(options.parallelism)
            coroutineScope {
                sources.mapIndexed { i, source ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            try {
                                // Taken before indexing, so a file replaced meanwhile shows up as a change on the
                                // first poll.
                                source.known = FileSnapshot.take(source.path)
                                    ?: throw IOException("${source.path.fileName}: file not found")
                                source.parser = source.chosen ?: Parser.detect(source.path, preferred, builtIn)
                                val log = source.parser.open(source.path, progresses[i], levels, options)
                                opened += log
                                source.log = log
                            } catch (e: Throwable) {
                                if (e !is CancellationException) failed.compareAndSet(null, source)
                                throw e
                            }
                        }
                    }
                }.awaitAll()
            }
            parserDetected = true
            listener.parserChanged()

            var nothingToMerge = false
            if (isMerged) {
                // An optional timestamp no record had is hidden, e.g. JSON whose time key is unknown.
                val unmergeable = sources.filter { source ->
                    val schema = source.log!!.schema
                    schema.indexOf(Role.TIMESTAMP).let { it < 0 || schema.fields[it].hidden }
                }
                if (unmergeable.isNotEmpty()) {
                    unmergeable.forEach { it.log!!.close() }
                    opened.removeAll(unmergeable.map { it.log!! }.toSet())
                    // Keep at least the files themselves, so the tab still names them and can be reloaded.
                    nothingToMerge = unmergeable.size == sources.size
                    if (nothingToMerge) sources.forEach { it.log = null } else sources = sources - unmergeable.toSet()
                    log.info("Left out of the merge, as they have no timestamps: {}", unmergeable.map { it.path })
                    listener.notMergeable(unmergeable.map { it.path })
                    listener.parserChanged()
                }
            }
            if (nothingToMerge) {
                log.info("Nothing to merge: none of {} has timestamps", pathsText())
                listener.problem(
                    "None of these files has timestamps to merge by.",
                    "They are opened in their own tabs.",
                    null,
                )
                setStatus(SessionStatus(message = "Nothing to merge"))
                return false
            }
            val shifts = LongArray(sources.size) { sources[it].shift }
            val loaded = loadedLog(sources.map { it.log!! }, sourceNames, shifts)
            engine = engineOf(loaded)
            this.loaded = loaded
            val shown = ShownLog(loaded)
            this.shown = shown
            indexMillis = (System.nanoTime() - started) / 1_000_000
            withContext(Dispatchers.IO) {
                for (source in sources) {
                    val known = source.known!!
                    source.activity = FileActivity.classify(known.lastModified, known.size, Files.size(source.path))
                }
            }
            val parsers = sources.joinToString { source ->
                val how = if (source.chosen == null) "detected" else "picked"
                "${source.path}: “${source.parser.name}” ($how)"
            }
            val unit = if (loaded.records.isRaw) "lines" else "records"
            log.info("Loaded {}, {} {} in {} ms", parsers, loaded.records.recordCount, unit, indexMillis)
            problem = null
            loggedReadProblem = null
            filterShown = false
            highlightsWait = true
            listener.loaded(shown)
            // The listener applies a filter, if any; without one there is nothing to wait for.
            if (!filterRun.isRunning) highlightAfterFilter()
            if (isLive) follow = true
            updateStatus()
            return true
        } catch (e: CancellationException) {
            opened.forEach { it.close() }
            throw e
        } catch (e: ParserMismatchException) {
            opened.forEach { it.close() }
            log.warn("{} does not match the parser “{}”", current().path, current().parser.name, e)
            listener.problem(
                "${current().path.fileName} does not match the parser “${current().parser.name}”.",
                e.message.orEmpty(),
                if (isMerged) {
                    "Choose another parser for it in the toolbar, or edit the parser."
                } else {
                    "Choose another parser in the toolbar, or Raw text."
                },
            )
            setStatus(SessionStatus(message = "Parser does not match"))
            return false
        } catch (e: Exception) {
            opened.forEach { it.close() }
            log.warn("Cannot load {}", current().path, e)
            listener.problem("Cannot open ${current().path.fileName}.", e.message ?: e.javaClass.simpleName, null)
            setStatus(SessionStatus(message = "Error"))
            return false
        } catch (e: OutOfMemoryError) {
            // Drop every reference to what was indexed, so the memory is free again for other tabs.
            opened.forEach { it.close() }
            opened.clear()
            sources.forEach { it.log = null }
            log.warn("Not enough memory to load {}", pathsText(), e)
            val what = if (isMerged) "these ${sources.size} files" else "${current().path.fileName}"
            listener.problem(
                "Not enough memory to open $what.",
                "Logxy can use up to ${formatByteSize(Runtime.getRuntime().maxMemory())} of memory. " +
                    "To allow more, raise the limit in File > Preferences > Memory.",
                if (isMerged) {
                    "Merge fewer files, or close other tabs."
                } else {
                    "Close other tabs, or choose a parser that fits the file."
                },
            )
            setStatus(SessionStatus(message = "Out of memory"))
            return false
        }
    }

    /** Follows the files; returns why they must be read again, or null when there is nothing left to follow. */
    private suspend fun watch(): String? {
        while (true) {
            withTimeoutOrNull(if (isLive) LIVE_POLL_MILLIS else STATIC_POLL_MILLIS) { pushes.receive() }
            val loaded = loaded ?: return null
            try {
                val oldRecordCount = loaded.records.recordCount
                val changed = ArrayList<Int>()
                val problems = ArrayList<String>()
                var grown = false
                var promoted = false
                for ((part, source) in sources.withIndex()) {
                    val log = source.log ?: return null
                    // A failing file must not keep what the others appended from being merged and shown.
                    try {
                        val current = withContext(Dispatchers.IO) { FileSnapshot.take(source.path) }
                        when (source.known!!.changeTo(current, log.indexedSize)) {
                            FileChange.MISSING -> {
                                problems += if (isMerged) "${source.path.fileName} not found" else "file not found"
                                continue
                            }
                            FileChange.SHRUNK -> return "${source.path} shrank"
                            FileChange.REPLACED -> return "${source.path} was replaced"
                            FileChange.GROWN -> {
                                val appended = withContext(Dispatchers.IO) { log.readAppended(current!!.size) }
                                loaded.append(part, appended).let { if (it >= 0) changed += it }
                                grown = true
                                if (source.activity == FileActivity.STATIC) {
                                    source.activity = FileActivity.LIVE
                                    promoted = true
                                }
                            }
                            FileChange.UNCHANGED -> {
                                val size = current!!.size
                                source.activity = FileActivity.classify(current.lastModified, size, size)
                            }
                        }
                        source.known = current!!
                    } catch (e: IOException) {
                        // Closing the tab closes the files under a running read.
                        currentCoroutineContext().ensureActive()
                        val message = e.message ?: e.javaClass.simpleName
                        problems += if (isMerged) "${source.path.fileName}: $message" else message
                    }
                }
                setReadProblem(problems.joinToString(", ").ifEmpty { null })
                if (grown) {
                    redetectionClock?.grew()
                    when (loaded.settle()) {
                        Appended.MERGE_AGAIN -> return "a last record was parsed differently, so the merge is redone"
                        Appended.NEW_FIELDS -> listener.fieldsShown()
                        Appended.GREW -> {}
                    }
                    listener.recordsGrew(changed.toIntArray())
                    if (activeFilter != null) {
                        catchUpFilter()
                    } else {
                        if (loaded.records.recordCount > oldRecordCount) {
                            notifyRows { rowsChanged(oldRecordCount, oldRecordCount) }
                        }
                        countTimes(anew = false)
                        searchShown(anew = false)
                    }
                    catchUpHighlights()
                    if (promoted) follow = true
                }
            } catch (e: IOException) {
                // Closing the tab closes the files under a running read.
                currentCoroutineContext().ensureActive()
                setReadProblem(e.message ?: e.javaClass.simpleName)
            }
            if (redetectionDue()) {
                val current = settings.current
                val path = sources.first().path
                val found = withContext(Dispatchers.IO) {
                    runCatching { Parser.detect(path, current.parsers, current.builtInParsers) }
                }
                found.exceptionOrNull()?.let { log.warn("Cannot detect the parser of {} again", path, it) }
                val detected = found.getOrNull()
                if (detected != null) log.debug("Detected the parser of {} again: “{}”", path, detected.name)
                if (detected != null && detected != Parser.Raw) return "redetection found the parser “${detected.name}”"
            }
            updateStatus()
        }
    }

    /** Shows [problem] reading the files, null for none; logs it when it starts or changes, not on every poll. */
    private fun setReadProblem(problem: String?) {
        this.problem = problem
        if (problem != null && problem != loggedReadProblem) log.warn("Cannot read {}: {}", pathsText(), problem)
        loggedReadProblem = problem
    }

    /** Whether the file, read as Raw text by detection, is to be detected again now; never after the user picked. */
    private fun redetectionDue(): Boolean {
        val source = sources.first()
        return source.chosen == null && source.parser == Parser.Raw && redetectionClock?.due() == true
    }

    private fun settingsChanged() {
        val settings = settings.current
        var levelsChanged = false
        for (source in sources) {
            val log = source.log ?: continue
            if (!log.isRaw && log.levelMapping != settings.levels) levelsChanged = true
            log.levelMapping = settings.levels
        }
        if (levelsChanged) listener.levelMappingChanged()
        // A saved parser this session uses was edited: read the files with the new definition.
        var editedParser: String? = null
        for (source in sources) {
            val used = source.chosen ?: source.parser.takeIf { it in savedParsers } ?: continue
            val changed = settings.parsers.firstOrNull { it.name == used.name && it != used } ?: continue
            // Only the column names changed: the records stay as they are.
            if (changed.sameDefinition(used)) {
                if (source.chosen != null) source.chosen = changed
                source.parser = changed
                continue
            }
            // A detected parser is detected again, as the edit may no longer fit the file best.
            if (source.chosen != null) source.chosen = changed
            editedParser = changed.name
        }
        // Only renamed: nothing to read again, just show the new name.
        for (source in sources) source.chosen?.let { renamed(it, settings.parsers) }?.let { source.chosen = it }
        for (source in sources) renamed(source.parser, settings.parsers)?.let { source.parser = it }
        savedParsers = settings.parsers
        listener.parserChanged()
        if (editedParser != null) {
            reload("saved parser “$editedParser” edited")
        } else if (levelsChanged && activeFilter != null) {
            runFilter(keepView = true, activeFilter?.let { engine?.prepare(it) })
        } else if (levelsChanged) {
            countTimes(anew = true)
        }
        // A load waiting for its filter evaluates the current row highlights once it is done.
        if (editedParser == null && (levelsChanged || settings.rowHighlights != evaluatedHighlights)) highlightAll()
    }

    /** The saved parser that [parser] became by a rename, i.e. with the same definition under a new name. */
    private fun renamed(parser: Parser, saved: List<Parser>): Parser? {
        if (parser == Parser.Raw || parser in Parser.BUILT_IN || saved.any { it.name == parser.name }) return null
        return saved.firstOrNull { parser.sameDefinition(it) }
    }

    /** Runs [evaluation] of [activeFilter] over all records, or shows all records without a filter. */
    private fun runFilter(keepView: Boolean, evaluation: PreparedFilter?) {
        filterRun.cancel()
        filterShown = evaluation == null
        val shown = shown ?: return
        if (evaluation == null) {
            if (shown.isFiltered || !keepView || filtering) {
                filtering = false
                running = null
                val oldSize = shown.size
                shownFilter = null
                val recordBefore = shown.showRows(null)
                notifyRows { rowsReplaced(keepView, oldSize, recordBefore) }
                updateStatus()
                countTimes(anew = true)
                searchShown(anew = true)
            }
            highlightAfterFilter()
            return
        }
        val filter = activeFilter
        running = evaluation
        filterRun.start {
            // Only slow filters say so; fast ones would just flash it.
            val indicator = launch {
                delay(FILTERING_INDICATOR_MILLIS)
                filtering = true
                updateStatus()
            }
            val result = try {
                evaluation.run()
            } catch (e: Throwable) {
                if (e is CancellationException || (e !is Exception && e !is OutOfMemoryError)) throw e
                log.warn("Cannot filter {} by {}", pathsText(), filter, e)
                // Keep the previous result, but stop showing the filter as running.
                indicator.cancel()
                filtering = false
                running = null
                filterRun.finished()
                problem = when (e) {
                    is OutOfMemoryError -> "Not enough memory to filter"
                    else -> e.message ?: e.javaClass.simpleName
                }
                updateStatus()
                highlightAfterFilter()
                return@start
            }
            indicator.cancel()
            filtering = false
            running = null
            val oldSize = shown.size
            val recordBefore = shown.showRows(result.rows)
            filterShown = true
            filterRun.cover(result.recordCount, result.indexedSize)
            shownFilter = filter
            notifyRows { rowsReplaced(keepView, oldSize, recordBefore) }
            updateStatus()
            countTimes(anew = true)
            searchShown(anew = true)
            highlightAfterFilter()
            // Still the current job (a replaced one would have been cancelled), but done.
            filterRun.finished()
            catchUpFilter()
        }
    }

    /** Filters what was appended since the shown result, keeping the result for older records; see [LoadEvaluation]. */
    private fun catchUpFilter() {
        val filter = activeFilter ?: return
        val shown = shown?.takeIf { it.isFiltered } ?: return
        val engine = engine ?: return
        val prepare = { from: Int -> engine.prepare(filter, from) }
        filterRun.catchUp(engine, indexedSize(), prepare) { from, evaluation ->
            val result = evaluation.run()
            val oldSize = shown.rowCount
            val firstChanged = shown.replaceRowsFrom(from, result.rows)
            filterRun.cover(result.recordCount, result.indexedSize)
            notifyRows { rowsChanged(firstChanged, oldSize) }
            updateStatus()
            countTimes(anew = false)
            searchShown(anew = false)
            filterRun.finished()
            catchUpFilter()
        }
    }

    /** Starts the row highlights of a new load, which waited for the filter's first result: it is shown, or failed. */
    private fun highlightAfterFilter() {
        if (!highlightsWait) return
        highlightsWait = false
        highlightAll()
    }

    /**
     * Evaluates the row highlights of the settings over all records, cancelling a running evaluation; the records
     * keep the row highlights they have until it is done. Row highlights that do not fit the records are left out.
     */
    private fun highlightAll() {
        if (highlightsWait) return
        val shown = shown ?: return
        val engine = engine ?: return
        val records = records ?: return
        highlightRun.cancel()
        val highlights = settings.current.rowHighlights
        val compiled = compileHighlights(highlights.map { it.query }, QueryCompiler(records.schema))
        val evaluations = prepareHighlights(engine, compiled, 0)
        evaluatedHighlights = highlights
        // One that does not prepare, such as one with an invalid regex, is left out of the catch-ups too.
        highlightFilters = compiled.mapIndexed { i, filter ->
            if (evaluations[i] == null) null else filter
        }
        if (evaluations.all { it == null }) {
            highlightRun.cover(0, 0L)
            val had = shown.hasRowHighlights
            shown.showRowHighlights(highlights, HighlightsPart(0, ByteChunkList(), 0, 0L))
            if (had) notifyRows { rowHighlightsChanged(0) }
            return
        }
        // The records keep indexes into the row highlights before; should this run fail, a catch-up starts over from
        // the first record rather than read them against the new ones.
        highlightRun.cover(0, 0L)
        highlightRun.start { showHighlights(shown, highlights, 0, evaluations) }
    }

    /** Evaluates the row highlights of what was appended since they were evaluated; see [LoadEvaluation]. */
    private fun catchUpHighlights() {
        if (highlightsWait || highlightFilters.all { it == null }) return
        val shown = shown ?: return
        val engine = engine ?: return
        val highlights = evaluatedHighlights ?: return
        val filters = highlightFilters
        val prepare = { from: Int -> prepareHighlights(engine, filters, from) }
        highlightRun.catchUp(engine, indexedSize(), prepare) { from, evaluations ->
            showHighlights(shown, highlights, from, evaluations)
        }
    }

    /**
     * The job of [highlightAll] and [catchUpHighlights]: runs [evaluations] of [highlights] over the records from
     * [from] on and shows what they give. A failed evaluation keeps the row highlights the records had.
     */
    private suspend fun showHighlights(
        shown: ShownLog,
        highlights: List<RowHighlight>,
        from: Int,
        evaluations: List<PreparedFilter?>,
    ) {
        val part = try {
            runHighlights(from, evaluations)
        } catch (e: Throwable) {
            if (e is CancellationException || (e !is Exception && e !is OutOfMemoryError)) throw e
            log.warn("Cannot evaluate the row highlights of {}", pathsText(), e)
            null
        }
        if (part == null) {
            highlightRun.finished()
            return
        }
        shown.showRowHighlights(highlights, part)
        highlightRun.cover(part.recordCount, part.indexedSize)
        highlightRun.finished()
        notifyRows { rowHighlightsChanged(from) }
        catchUpHighlights()
    }

    /**
     * Finds the shown rows that [searchCondition] matches, by searching with it ANDed with [shownFilter]. With
     * [anew], searches all of them, cancelling a running search; otherwise only the rows added or changed since
     * the found records were computed. Like [catchUpFilter], such a catch-up never cancels a running search:
     * whichever finishes calls this again, so nothing appended is missed.
     *
     * With [narrow], the found records are those of a previous search that [searchCondition] only narrows: the
     * search looks among them, and they stay shown until it is done.
     */
    private fun searchShown(anew: Boolean, narrow: Boolean = false) {
        if (anew) {
            searchRun.cancel()
            searching = null
            searchError = null
            queuedSearch = null
            searchNarrows = false
            foundSpec = null
            // A narrowing search keeps showing the times of the previous one until it counts its own.
            if (!narrow) {
                foundHistogram = null
                foundTimes = null
            }
        } else if (searchRun.isRunning) {
            return
        }
        val shown = shown ?: return
        val engine = engine ?: return
        val condition = searchCondition
        if (condition == null) {
            if (anew) {
                shown.clearFound()
                listener.foundChanged()
            }
            return
        }
        // A failed search stops following the rows until the next one.
        if (!anew && searchError != null) return
        val filter = shownFilter
        val shownCondition = if (filter == null) condition else Filter.And(listOf(filter, condition))
        val spec = searchSpec
        if (!anew) {
            val prepare = { from: Int -> engine.prepare(shownCondition, from) }
            searchRun.catchUp(engine, shownBytes(), prepare) { from, evaluation ->
                runSearch(shown, evaluation, spec, from, anew = false, narrowed = false)
            }
            return
        }
        // The found records were computed for the rows shown now; the last record may have grown since.
        val within = if (narrow) shown.narrowing(engine.changedFrom(searchRun.coveredRecords)) else null
        val evaluation = engine.prepare(shownCondition, 0, within)
        searching = evaluation
        foundChunked = false
        if (within == null) {
            searchRun.cover(0, 0L)
            foundTimes = foundTimeCount(shown)
            foundHistogram = foundTimes?.emptyHistogram()
            shown.startFound(records?.recordCount ?: 0)
            listener.foundChanged()
        } else {
            searchNarrows = true
            shown.markSearching()
        }
        searchRun.start { runSearch(shown, evaluation, spec, 0, anew = true, narrowed = within != null) }
    }

    /**
     * The job of [searchShown]: runs [evaluation] of [spec] over the records from [from] on, [anew] over all of them,
     * and shows what it finds. A search anew that is not [narrowed] shows its matches chunk by chunk meanwhile.
     */
    private suspend fun runSearch(
        shown: ShownLog,
        evaluation: PreparedFilter,
        spec: TextSearch?,
        from: Int,
        anew: Boolean,
        narrowed: Boolean,
    ) {
        val result = try {
            coroutineScope {
                // Only a search anew shows what it finds meanwhile, unless it narrows the records shown (they stay
                // until it is done); the chunks arrive on worker threads.
                val times = foundTimes
                val onChunk = ChunkListener { first, end, matches ->
                    // Counted here, on the chunk's own thread: the other threads may all be busy searching.
                    val counted = times?.countRecords(matches)
                    launch { foundChunk(shown, first, end, matches, counted) }
                }
                evaluation.run(onChunk.takeIf { anew && !narrowed })
            }
        } catch (e: Throwable) {
            if (e is CancellationException || (e !is Exception && e !is OutOfMemoryError)) throw e
            log.warn("Cannot search {} for {}", pathsText(), spec, e)
            // A failed catch-up keeps what was found before.
            searchError = when (e) {
                is OutOfMemoryError -> "Not enough memory to search"
                else -> e.message ?: e.javaClass.simpleName
            }
            searching = null
            searchRun.finished()
            searchNarrows = false
            if (anew) {
                searchRun.cover(0, 0L)
                shown.clearFound()
                foundHistogram = null
            }
            // A search typed meanwhile has nothing to narrow, so it starts from scratch.
            val queued = queuedSearch
            if (queued != null) {
                queuedSearch = null
                searchSpec = queued
                searchCondition = queued.condition()
                searchShown(anew = true)
                return
            }
            listener.foundChanged()
            return
        }
        val found = shownOnly(result.rows, shown)
        val countFound = countFoundTimes(shown, from, found, anew)
        if (anew) shown.showFound(found) else shown.replaceFoundFrom(from, found)
        countFound?.invoke()
        // Records past the shown rows were left out, so they are searched again once shown.
        searchRun.cover(minOf(result.recordCount, shownRecords()), minOf(result.indexedSize, shownBytes()))
        searching = null
        searchRun.finished()
        searchNarrows = false
        if (anew) foundSpec = spec
        // A search typed meanwhile narrows this one's found records. They are not announced, as the narrowing
        // search replaces them.
        val queued = queuedSearch
        if (queued != null && foundSpec == searchSpec) {
            queuedSearch = null
            searchSpec = queued
            searchCondition = queued.condition()
            searchShown(anew = true, narrow = true)
            return
        }
        listener.foundChanged()
        searchShown(anew = false)
    }

    /**
     * Shows the matches [records] among the records `[first, end)`, a chunk the running search decided, with their
     * times [counted] (null if the records have none).
     */
    private fun foundChunk(shown: ShownLog, first: Int, end: Int, records: IntArray, counted: TimeHistogram?) {
        val reported = records.size
        val matches = shownOnly(records, shown)
        shown.addFoundChunk(first, end, matches)
        foundChunked = true
        val histogram = foundHistogram
        if (counted != null && histogram != null) {
            // Records the shown log lacks must not count, but [counted] has them: count the rest again.
            val times = foundTimes
            val recounted = if (matches.size != reported) times?.countRecords(matches) else null
            histogram.addAll(recounted ?: counted)
        }
        listener.foundChanged()
    }

    /**
     * A count of the found records' times for [foundHistogram]: when run, of all of [rows], which are sorted, or
     * else of the records passed to [TimeCount.countRecords]. Null if the records have no timestamps.
     */
    private fun foundTimeCount(shown: ShownLog, rows: IntArray? = null): TimeCount? {
        val loaded = loaded ?: return null
        if (loaded.records.schema.indexOf(Role.TIMESTAMP) < 0) return null
        val (expectedFrom, expectedTo) = expectedTimes(shown)
        val size = rows?.size ?: 0
        return TimeCount(loaded, rows, 0, size, size, expectedFrom, expectedTo)
    }

    /**
     * Counts, on other threads, the times for [foundHistogram] once the search found [found], sorted, among the
     * records from [from] on. Returns a function that puts them into [foundHistogram]. Null if there is nothing to
     * put in: the records have no times, or the chunks of a search anew counted them already.
     */
    private suspend fun countFoundTimes(shown: ShownLog, from: Int, found: IntArray, anew: Boolean): (() -> Unit)? {
        if (anew && foundChunked) return null
        if (anew) {
            val count = foundTimeCount(shown, found) ?: return null
            val (before, after) = count.run()
            before.addAll(after)
            return { foundHistogram = before }
        }
        // The records from [from] on are found again: take away what they counted before, and count the new ones.
        val histogram = foundHistogram ?: return null
        val count = foundTimeCount(shown) ?: return null
        val old = shown.foundFrom(from)
        val gone = histogram.emptyLike()
        val added = histogram.emptyLike()
        withContext(Dispatchers.Default) {
            count.countRecords(old, gone)
            count.countRecords(found, added)
        }
        return {
            histogram.subtractAll(gone)
            histogram.addAll(added)
        }
    }

    /**
     * [records] (sorted) without those the shown rows lack: records past the ones they cover, and of a filter's
     * result, records that were still growing when it was computed, as it may have judged them by less text.
     * Compacts [records] in place.
     */
    private fun shownOnly(records: IntArray, shown: ShownLog): IntArray {
        val end = records.binarySearch(shownRecords()).let { if (it >= 0) it else -it - 1 }
        var size = end
        if (shown.isFiltered) {
            val unsure = engine?.changedFrom(filterRun.coveredRecords) ?: 0
            size = records.binarySearch(unsure, 0, end).let { if (it >= 0) it else -it - 1 }
            for (i in size..<end) {
                val record = records[i]
                val row = shown.rowOf(record)
                if (row >= 0 && shown.recordAt(row) == record) records[size++] = record
            }
        }
        return if (size == records.size) records else records.copyOf(size)
    }

    // How much of the records the shown rows cover: records, and bytes.
    private fun shownRecords(): Int =
        if (shown?.isFiltered == true) filterRun.coveredRecords else records?.recordCount ?: 0

    private fun shownBytes(): Long = if (shown?.isFiltered == true) filterRun.coveredBytes else indexedSize()

    /**
     * Counts the times of the shown rows on other threads: [anew] all of them, keeping [histogram] meanwhile,
     * or only those added or changed since the last count. A count of new rows asked for while another runs
     * follows it.
     */
    private fun countTimes(anew: Boolean) {
        val loaded = loaded ?: return
        val records = loaded.records
        val engine = engine ?: return
        if (records.schema.indexOf(Role.TIMESTAMP) < 0) return
        if (anew) {
            countJob?.cancel()
        } else if (countJob?.isActive == true) {
            countAgain = true
            return
        }
        countAgain = false
        val shown = shown ?: return
        val size = shown.rowCount
        // From the first record still growing on, rows may change: they are counted again each time.
        val changing = engine.changedFrom(if (shown.isFiltered) filterRun.coveredRecords else records.recordCount)
        val settle = shown.rowOf(changing).let { if (it < 0) size else it }
        val base = settled?.takeIf { !anew && settle >= settledRows }
        val from = if (base == null) 0 else settledRows
        val (expectedFrom, expectedTo) = expectedTimes(shown)
        val count = TimeCount(loaded, shown.rowArray, from, size, settle, expectedFrom, expectedTo)
        counting = count
        // Counted anew, the histogram is shown as it fills up; a stale part may still arrive after another count
        // started.
        val onCounted: ((TimeHistogram) -> Unit)? = if (base != null) {
            null
        } else {
            { counted ->
                scope.launch {
                    if (counting === count) {
                        histogram = counted
                        listener.histogramChanged()
                    }
                }
            }
        }
        countJob = scope.launch {
            val (settledPart, restPart) = count.run(onCounted)
            val all = base?.apply { addAll(settledPart) } ?: settledPart
            settled = all
            settledRows = settle
            histogram = all.copy().apply { addAll(restPart) }
            counting = null
            countJob = null
            listener.histogramChanged()
            if (countAgain) countTimes(anew = false)
        }
    }

    /** The times of about the first and the last of the shown rows, for the bins to start with. */
    private fun expectedTimes(shown: ShownLog): Pair<Long, Long> {
        val first = shown.timeFrom(0)
        val last = shown.timeUntil(shown.size - 1)
        // An end without times takes the other's, so the bins do not start out spanning the years since the epoch.
        val from = when {
            first != Records.NO_TIMESTAMP -> first
            last != Records.NO_TIMESTAMP -> last
            else -> 0
        }
        val to = if (last != Records.NO_TIMESTAMP) last else from
        return from to maxOf(from, to)
    }

    private fun indexedSize(): Long = sources.sumOf { it.log?.indexedSize ?: 0L }

    private fun pathsText(): String = paths.joinToString()

    private fun setStatus(status: SessionStatus) {
        this.status = status
        listener.statusChanged(status)
    }

    /**
     * Tells the listener the shown rows changed. Its failure is reported to the thread's uncaught-exception
     * handler instead of thrown, so the session still completes its own update (status, counts, search, jobs)
     * and keeps following the files.
     */
    private inline fun notifyRows(notify: SessionListener.() -> Unit) {
        try {
            listener.notify()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler.uncaughtException(thread, e)
        }
    }

    private fun updateStatus() {
        val records = records ?: return
        setStatus(
            SessionStatus(
                size = indexedSize(),
                records = records.recordCount,
                shown = shown?.takeIf { it.isFiltered }?.rowCount,
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
        // Before closing: what the view still holds must not read closed files.
        shown?.let {
            it.end()
            shown = null
            listener.shownEnded()
        }
        filterRun.clear()
        searchRun.clear()
        highlightRun.clear()
        evaluatedHighlights = null
        highlightFilters = emptyList()
        searching = null
        searchError = null
        queuedSearch = null
        foundSpec = null
        searchNarrows = false
        foundHistogram = null
        foundTimes = null
        shownFilter = null
        countJob?.cancel()
        countJob = null
        counting = null
        countAgain = false
        histogram = null
        settled = null
        settledRows = 0
        filtering = false
        running = null
        activeFilter = null
        for (source in sources) {
            source.log?.close()
            source.log = null
        }
        loaded = null
        engine = null
    }

    private companion object {
        const val LIVE_POLL_MILLIS = 500L
        const val STATIC_POLL_MILLIS = 2000L
        const val FILTERING_INDICATOR_MILLIS = 200L
    }
}
