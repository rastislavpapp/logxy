package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterResult
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.session.TimeHistogram
import io.github.rastislavpapp.logxy.core.session.countTimes
import io.github.rastislavpapp.logxy.core.template.TemplateCounts
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.io.File
import java.nio.file.Path

/** [files] (joined by [File.pathSeparator]) parsed one after another, as a session does, and merged. */
@State(Scope.Benchmark)
abstract class MergedFiles {
    @Param("")
    @JvmField
    var files = ""

    /** Per file, see [IndexOptions.readChannels]; empty for the default. */
    @Param("")
    @JvmField
    var readChannels = ""

    protected lateinit var parts: List<LogData>
    protected lateinit var merged: MergedLog

    @Setup
    fun setUp() {
        val options = indexOptions(readChannels.toIntOrNull())
        parts = parseParts(paths(files), options)
        merged = runBlocking { MergedLog.build(parts) }
    }

    @TearDown
    fun tearDown() = parts.forEach { it.close() }
}

/** Merging and counting on the merged log. Open so JMH can generate its subclass. */
@State(Scope.Benchmark)
open class MergeBenchmark : MergedFiles() {
    /** As for a filter's result: positions to look up. */
    private val everyOther by lazy { IntArray(merged.recordCount / 2) { it * 2 } }

    @Benchmark
    fun merge(): MergedLog = runBlocking { MergedLog.build(parts) }

    @Benchmark
    fun countTemplates(): TemplateCounts = runBlocking { MergedFilter(merged).countTemplates(null, merged.recordCount) }

    @Benchmark
    fun countTimes(): TimeHistogram = runBlocking { countTimes(merged) }

    @Benchmark
    fun countTimesOfEveryOtherRow(): TimeHistogram = runBlocking { countTimes(merged, everyOther) }
}

/** One of [mergedBenchmarkFilters] by name, on a new filter each time. Open so JMH can generate its subclass. */
@State(Scope.Benchmark)
open class MergedFilterBenchmark : MergedFiles() {
    @Param("")
    @JvmField
    var filter = ""

    private val condition: Filter by lazy { mergedBenchmarkFilters.getValue(filter) }

    @Benchmark
    fun run(): FilterResult = runBlocking { MergedFilter(merged).prepare(condition, 0).run() }
}

private fun indexOptions(readChannels: Int?): IndexOptions =
    if (readChannels == null) IndexOptions() else IndexOptions().copy(readChannels = readChannels)

private fun parseParts(files: List<Path>, options: IndexOptions): List<LogData> = files.map { file ->
    val parser = parserFor(file).compileFor(file)
    runBlocking { ParsedLog.build(file, parser, options = options) }
}

/** The `merge` rows: merging, [mergedBenchmarkFilters] by name, and counting on the merged log. */
val mergeRows = listOf(
    BenchmarkRow(MergeBenchmark::class.java, "merge", "merge: merge files"),
    BenchmarkRow(MergedFilterBenchmark::class.java, "run", "merge: filter ", MergedFilterBenchmark::filter.name),
    BenchmarkRow(MergeBenchmark::class.java, "countTemplates", "merge: count templates"),
    BenchmarkRow(MergeBenchmark::class.java, "countTimes", "merge: count times"),
    BenchmarkRow(MergeBenchmark::class.java, "countTimesOfEveryOtherRow", "merge: count times of every other row"),
)

/** Parameters of [MergeBenchmark] and [MergedFilterBenchmark]. */
fun mergeParams(files: List<Path>, readChannels: Int? = null): Map<String, List<String>> = mapOf(
    MergedFiles::files.name to listOf(files.joinToString(File.pathSeparator)),
    MergedFiles::readChannels.name to listOf(readChannels?.toString().orEmpty()),
    MergedFilterBenchmark::filter.name to mergedBenchmarkFilters.keys.toList(),
)

/**
 * Measures [mergeRows] on [files], then the heap a merge adds. [readChannels] is per file; [only] measures just the
 * row of that name.
 */
fun benchmarkMerging(files: List<Path>, runs: Runs, readChannels: Int?, only: String? = null) {
    val options = indexOptions(readChannels)
    println("read channels per file: ${options.readChannels}\n")
    printHeader(runs)
    measureOrList(mergeRows, mergeParams(files, readChannels), runs, only)
    if (only != null) return
    val parts = parseParts(files, options)
    val before = usedHeap()
    val log = runBlocking { MergedLog.build(parts) }
    val added = usedHeap() - before
    println("\nmerged %,d records, heap +%s (%.1f bytes per record)"
        .format(log.recordCount, formatByteSize(added), added.toDouble() / log.recordCount))
    parts.forEach { it.close() }
}
