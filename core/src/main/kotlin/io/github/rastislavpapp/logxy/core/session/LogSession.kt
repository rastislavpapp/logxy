package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.FileActivity
import io.github.rastislavpapp.logxy.core.FileChange
import io.github.rastislavpapp.logxy.core.FileSnapshot
import io.github.rastislavpapp.logxy.core.archive.ArchiveOrigin
import io.github.rastislavpapp.logxy.core.filter.ChunkListener
import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.merge.MergedLog
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

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

    /** [shown] is ready to be shown, all records for now; the view applies its filter next. */
    fun loaded(shown: ShownLog)

    /** The shown log of the last [loaded] ended, before its files are closed: drop it and what was read from it. */
    fun shownEnded()

    /** [paths] have no timestamps, so they were left out of the merge; the view may open them on their own. */
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

    /** [LogSession.follow], keeping the end of the files in view, was turned on or off. */
    fun followChanged(follow: Boolean)

    /** [LogSession.status] changed: sizes, counts, filtering, or a problem to show. */
    fun statusChanged(status: SessionStatus)
}

/**
 * One or more open files without their UI: indexes them in the background, merges several by time
 * (see [MergedLog]), follows changes to them, and filters them. Not thread-safe: use it, and run
 * [scope], on one thread (the UI thread); [listener] is called there. [origin] tells which files were
 * extracted from zips or decompressed, for naming them. [chosen] holds the parser the
 * user picked for each of the paths, null (or missing) to detect it.
 */
class LogSession internal constructor(
    paths: List<Path>,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val listener: SessionListener,
    private val origin: (Path) -> ArchiveOrigin?,
    chosen: List<Parser?>,
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
    ) : this(paths, scope, settings, listener, origin, chosen, { it.newFilter() })

    constructor(path: Path, scope: CoroutineScope, settings: SettingsStore, listener: SessionListener) :
        this(listOf(path), scope, settings, listener)

    private class Source(val path: Path, var chosen: Parser?) {
        var parser: Parser = Parser.Raw
        var log: LogData? = null
        var known: FileSnapshot? = null
        var activity = FileActivity.STATIC
    }

    init {
        require(paths.isNotEmpty()) { "A session needs a file" }
    }

    private var sources = paths.mapIndexed { i, path -> Source(path, chosen.getOrNull(i)) }
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

    /** Several files merged into one view. */
    val isMerged get() = sources.size > 1

    /** The parser the user picked for a single file (the first one of a merge), or null to detect it. */
    val chosenParser: Parser? get() = sources.first().chosen

    /** The parser the user picked for each of [paths], null where it is detected. */
    val chosenParsers: List<Parser?> get() = sources.map { it.chosen }

    /** The parser in use (or last used) for each of [paths]. */
    val parsers: List<Parser> get() = sources.map { it.parser }

    /** The parser of the first (for a single file: the) file. */
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

    // The values of variables over all records, and the records they were counted in: the loaded log and its count.
    private val allValues = HashMap<Pair<String, Int>, VariableValues>()
    private var allValuesOf: Pair<LoadedLog, Int>? = null

    private var activeFilter: Filter? = null
    private var filterJob: Job? = null

    /** Whether the rows shown are [activeFilter]'s result (all records without one). */
    private var filterShown = false

    // How much of the records the shown filter result covers: records, and bytes (the last record may grow without
    // new ones).
    private var filteredRecords = 0
    private var filteredBytes = 0L
    private var filtering = false
    private var running: PreparedFilter? = null

    /** How far the filter over all records got, from 0 to 1, while [SessionStatus.filtering]. */
    val filterProgress: Double get() = running?.progress ?: 0.0

    /** The filter whose result the rows show, null for all records. */
    private var shownFilter: Filter? = null

    private var searchCondition: Filter? = null
    private var searchJob: Job? = null
    private var searching: PreparedFilter? = null

    // What [searchCondition] is for, and the search whose records the found ones are, once its run is done.
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

    // How much of the records the found records cover, as for the filter result.
    private var foundRecords = 0
    private var foundBytes = 0L

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

    /** Times of the shown rows; null until they are counted, or if the records have no timestamps. */
    var histogram: TimeHistogram? = null
        private set

    // The shown rows before [settledRows], which no longer change, counted apart from the rest, which may.
    private var settled: TimeHistogram? = null
    private var settledRows = 0
    private var countJob: Job? = null
    private var counting: TimeCount? = null
    private var countAgain = false

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

    /** Starts the first load and listens to settings changes; call once, after the listener is ready for events. */
    fun start() {
        settings.addListener(settingsListener)
        launchLoad()
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
        reload()
    }

    /** Starts over with the files now at [paths], e.g. after rotation, truncation or a parser change. */
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
                val running = searchJob?.isActive == true
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
     * The values at each of [variables] of the shown rows whose message has [template], over time, read in one pass;
     * null if there is nothing loaded or the records have no message or no time. Cancelling the caller stops it.
     */
    suspend fun variableSeries(template: String, variables: IntArray): List<VariableSeries>? {
        while (true) countJob?.takeIf { it.isActive }?.join() ?: break
        val shown = shown ?: return null
        val engine = engine ?: return null
        if (shown.schema.indexOf(Role.MESSAGE) < 0 || shown.schema.indexOf(Role.TIMESTAMP) < 0) return null
        return engine.variableSeries(shown.rowArray, shown.rowCount, template, variables)
    }

    /**
     * How often each value occurs at [variable] (from 0) of the shown rows whose message has [template]; null if
     * there is nothing loaded or the records have no message. Cancelling the caller stops it.
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

    /** The template of [record]'s message; null if it has none, or nothing is loaded. */
    fun templateOf(record: Int): String? {
        val loaded = loaded ?: return null
        if (loaded.records.schema.indexOf(Role.MESSAGE) < 0) return null
        return loaded.templateOf(record).ifEmpty { null }
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
            val options = defaults.copy(readChannels = readChannels)
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
                    listener.notMergeable(unmergeable.map { it.path })
                    listener.parserChanged()
                }
            }
            if (nothingToMerge) {
                listener.problem(
                    "None of these files has timestamps to merge by.",
                    "They are opened in their own tabs.",
                    null,
                )
                setStatus(SessionStatus(message = "Nothing to merge"))
                return false
            }
            val loaded = loadedLog(sources.map { it.log!! }, sourceNames)
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
            problem = null
            filterShown = false
            listener.loaded(shown)
            if (isLive) follow = true
            updateStatus()
            return true
        } catch (e: CancellationException) {
            opened.forEach { it.close() }
            throw e
        } catch (e: ParserMismatchException) {
            opened.forEach { it.close() }
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
            listener.problem("Cannot open ${current().path.fileName}.", e.message ?: e.javaClass.simpleName, null)
            setStatus(SessionStatus(message = "Error"))
            return false
        } catch (e: OutOfMemoryError) {
            // Drop every reference to what was indexed, so the memory is free again for other tabs.
            opened.forEach { it.close() }
            opened.clear()
            sources.forEach { it.log = null }
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

    private suspend fun watch() {
        while (true) {
            withTimeoutOrNull(if (isLive) LIVE_POLL_MILLIS else STATIC_POLL_MILLIS) { pushes.receive() }
            val loaded = loaded ?: return
            try {
                val oldRecordCount = loaded.records.recordCount
                val changed = ArrayList<Int>()
                val problems = ArrayList<String>()
                var grown = false
                var promoted = false
                for ((part, source) in sources.withIndex()) {
                    val log = source.log ?: return
                    // A failing file must not keep what the others appended from being merged and shown.
                    try {
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
                problem = problems.joinToString(", ").ifEmpty { null }
                if (grown) {
                    when (loaded.settle()) {
                        Appended.MERGE_AGAIN -> {
                            reload()
                            return
                        }
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
        var edited = false
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
            edited = true
        }
        // Only renamed: nothing to read again, just show the new name.
        for (source in sources) source.chosen?.let { renamed(it, settings.parsers) }?.let { source.chosen = it }
        for (source in sources) renamed(source.parser, settings.parsers)?.let { source.parser = it }
        savedParsers = settings.parsers
        listener.parserChanged()
        if (edited) {
            reload()
        } else if (levelsChanged && activeFilter != null) {
            runFilter(keepView = true, activeFilter?.let { engine?.prepare(it) })
        } else if (levelsChanged) {
            countTimes(anew = true)
        }
    }

    /** The saved parser that [parser] became by a rename, i.e. with the same definition under a new name. */
    private fun renamed(parser: Parser, saved: List<Parser>): Parser? {
        if (parser == Parser.Raw || parser in Parser.BUILT_IN || saved.any { it.name == parser.name }) return null
        return saved.firstOrNull { parser.sameDefinition(it) }
    }

    /** Runs [evaluation] of [activeFilter] over all records, or shows all records without a filter. */
    private fun runFilter(keepView: Boolean, evaluation: PreparedFilter?) {
        filterJob?.cancel()
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
            return
        }
        val filter = activeFilter
        running = evaluation
        filterJob = scope.launch {
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
                // Keep the previous result, but stop showing the filter as running.
                indicator.cancel()
                filtering = false
                running = null
                filterJob = null
                problem = when (e) {
                    is OutOfMemoryError -> "Not enough memory to filter"
                    else -> e.message ?: e.javaClass.simpleName
                }
                updateStatus()
                return@launch
            }
            indicator.cancel()
            filtering = false
            running = null
            val oldSize = shown.size
            val recordBefore = shown.showRows(result.rows)
            filterShown = true
            filteredRecords = result.recordCount
            filteredBytes = result.indexedSize
            shownFilter = filter
            notifyRows { rowsReplaced(keepView, oldSize, recordBefore) }
            updateStatus()
            countTimes(anew = true)
            searchShown(anew = true)
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
        val shown = shown?.takeIf { it.isFiltered } ?: return
        if (filterJob?.isActive == true || filteredBytes >= indexedSize()) return
        // Records covered then may have grown since (the last one of each file), so they are filtered again.
        val engine = engine ?: return
        val from = engine.changedFrom(filteredRecords)
        val evaluation = engine.prepare(filter, from)
        filterJob = scope.launch {
            val result = evaluation.run()
            val oldSize = shown.rowCount
            val firstChanged = shown.replaceRowsFrom(from, result.rows)
            filteredRecords = result.recordCount
            filteredBytes = result.indexedSize
            notifyRows { rowsChanged(firstChanged, oldSize) }
            updateStatus()
            countTimes(anew = false)
            searchShown(anew = false)
            filterJob = null
            catchUpFilter()
        }
    }

    /**
     * Finds the shown rows that [searchCondition] matches, ANDed with [shownFilter]: [anew] all of them, cancelling
     * a running search, or only those added or changed since the found records. Like [catchUpFilter], a catch-up
     * never cancels a running search: whichever finishes calls this again, so nothing appended is missed. With
     * [narrow], the found records are those of a previous search that [searchCondition] only narrows: it looks
     * among them, and they stay shown until it is done.
     */
    private fun searchShown(anew: Boolean, narrow: Boolean = false) {
        if (anew) {
            searchJob?.cancel()
            searchJob = null
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
        } else if (searchJob?.isActive == true) {
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
        if (!anew && (searchError != null || foundBytes >= shownBytes())) return
        val from = if (anew) 0 else engine.changedFrom(foundRecords)
        // The found records were computed for the rows shown now; the last record may have grown since.
        val within = if (anew && narrow) shown.narrowing(engine.changedFrom(foundRecords)) else null
        val evaluation = engine.prepare(
            shownFilter?.let { Filter.And(listOf(it, condition)) } ?: condition,
            from,
            within,
        )
        val spec = searchSpec
        if (anew) {
            searching = evaluation
            foundChunked = false
            if (within == null) {
                foundRecords = 0
                foundBytes = 0L
                foundTimes = foundTimeCount(shown)
                foundHistogram = foundTimes?.emptyHistogram()
                shown.startFound(records?.recordCount ?: 0)
                listener.foundChanged()
            } else {
                searchNarrows = true
                shown.markSearching()
            }
        }
        searchJob = scope.launch {
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
                    evaluation.run(onChunk.takeIf { anew && within == null })
                }
            } catch (e: Throwable) {
                if (e is CancellationException || (e !is Exception && e !is OutOfMemoryError)) throw e
                // A failed catch-up keeps what was found before.
                searchError = when (e) {
                    is OutOfMemoryError -> "Not enough memory to search"
                    else -> e.message ?: e.javaClass.simpleName
                }
                searching = null
                searchJob = null
                searchNarrows = false
                if (anew) {
                    foundRecords = 0
                    foundBytes = 0L
                    shown.clearFound()
                    foundHistogram = null
                }
                // A search typed on meanwhile has nothing to narrow, so it starts from scratch.
                val queued = queuedSearch
                if (queued != null) {
                    queuedSearch = null
                    searchSpec = queued
                    searchCondition = queued.condition()
                    searchShown(anew = true)
                    return@launch
                }
                listener.foundChanged()
                return@launch
            }
            val found = shownOnly(result.rows, shown)
            val countFound = countFoundTimes(shown, from, found, anew)
            if (anew) shown.showFound(found) else shown.replaceFoundFrom(from, found)
            countFound?.invoke()
            // Records past the shown rows were left out, so they are searched again once shown.
            foundRecords = minOf(result.recordCount, shownRecords())
            foundBytes = minOf(result.indexedSize, shownBytes())
            searching = null
            searchJob = null
            searchNarrows = false
            if (anew) foundSpec = spec
            // A search typed on meanwhile narrows this one's records, which stay unannounced as they are replaced.
            val queued = queuedSearch
            if (queued != null && foundSpec == searchSpec) {
                queuedSearch = null
                searchSpec = queued
                searchCondition = queued.condition()
                searchShown(anew = true, narrow = true)
                return@launch
            }
            listener.foundChanged()
            searchShown(anew = false)
        }
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
     * Counts the times of the found records for [foundHistogram], all of [rows] (sorted) or those given to
     * [TimeCount.countRecords]; null if the records have no timestamps.
     */
    private fun foundTimeCount(shown: ShownLog, rows: IntArray? = null): TimeCount? {
        val loaded = loaded ?: return null
        if (loaded.records.schema.indexOf(Role.TIMESTAMP) < 0) return null
        val (expectedFrom, expectedTo) = expectedTimes(shown)
        val size = rows?.size ?: 0
        return TimeCount(loaded, rows, 0, size, size, expectedFrom, expectedTo)
    }

    /**
     * Counts the times for [foundHistogram] once the search found [found] (sorted) among the records from [from] on,
     * on other threads; returns what puts them in. Null if nothing is to be put in: the records have no times, or
     * the chunks of a search anew counted them already.
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
            val unsure = engine?.changedFrom(filteredRecords) ?: 0
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
    private fun shownRecords(): Int = if (shown?.isFiltered == true) filteredRecords else records?.recordCount ?: 0

    private fun shownBytes(): Long = if (shown?.isFiltered == true) filteredBytes else indexedSize()

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
        val changing = engine.changedFrom(if (shown.isFiltered) filteredRecords else records.recordCount)
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
        filterJob?.cancel()
        searchJob?.cancel()
        searchJob = null
        searching = null
        searchError = null
        queuedSearch = null
        foundSpec = null
        searchNarrows = false
        foundRecords = 0
        foundBytes = 0L
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
        filteredRecords = 0
        filteredBytes = 0L
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
