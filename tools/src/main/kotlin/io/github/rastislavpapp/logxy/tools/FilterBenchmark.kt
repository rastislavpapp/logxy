package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.FilterResult
import io.github.rastislavpapp.logxy.core.filter.PatternCondition
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.pattern.PatternCounts
import io.github.rastislavpapp.logxy.core.session.TimeHistogram
import io.github.rastislavpapp.logxy.core.session.countTimes
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.file.Files
import java.nio.file.Path

/**
 * One of [benchmarkFilters] by name, run on a new engine each time as it caches what it computed. Open so JMH can
 * generate its subclass.
 */
@State(Scope.Benchmark)
open class FilterBenchmark {
    @Param("")
    @JvmField
    var file = ""

    @Param("")
    @JvmField
    var filter = ""

    private lateinit var log: ParsedLog
    private lateinit var condition: Filter

    @Setup
    fun setUp() {
        log = parse(Path.of(file))
        condition = benchmarkFilters.getValue(filter)
    }

    @TearDown
    fun tearDown() = log.close()

    @Benchmark
    fun run(): FilterResult = runBlocking { FilterEngine(log).prepare(condition).run() }
}

/** Counting patterns and times, and hiding the most common pattern. Open so JMH can generate its subclass. */
@State(Scope.Benchmark)
open class PatternBenchmark {
    @Param("")
    @JvmField
    var file = ""

    private lateinit var log: ParsedLog

    /** An engine that already found the templates. */
    private lateinit var usedEngine: FilterEngine
    private lateinit var hideTopPattern: Filter

    @Setup
    fun setUp() {
        log = parse(Path.of(file))
        usedEngine = FilterEngine(log)
        val top = runBlocking { usedEngine.countPatterns(null, log.recordCount) }.templates.first()!!
        hideTopPattern = Filter.Not(PatternCondition("Message", setOf(top)))
    }

    @TearDown
    fun tearDown() = log.close()

    /** A new engine finds the templates anew. */
    @Benchmark
    fun countPatterns(): PatternCounts = runBlocking { FilterEngine(log).countPatterns(null, log.recordCount) }

    /** A used engine only counts. */
    @Benchmark
    fun countPatternsAgain(): PatternCounts = runBlocking { usedEngine.countPatterns(null, log.recordCount) }

    @Benchmark
    fun hideMostCommonPattern(): FilterResult = runBlocking { FilterEngine(log).prepare(hideTopPattern).run() }

    @Benchmark
    fun countTimes(): TimeHistogram = runBlocking { countTimes(log) }
}

/** The `filter` rows: [benchmarkFilters] by name, patterns and count times. */
val filterRows = listOf(
    BenchmarkRow(FilterBenchmark::class.java, "run", "filter ", FilterBenchmark::filter.name),
    BenchmarkRow(PatternBenchmark::class.java, "countPatterns", "patterns: count all"),
    BenchmarkRow(PatternBenchmark::class.java, "countPatternsAgain", "patterns: count all again"),
    BenchmarkRow(PatternBenchmark::class.java, "hideMostCommonPattern", "filter hide most common pattern"),
    BenchmarkRow(PatternBenchmark::class.java, "countTimes", "count times"),
)

/** Parameters of [FilterBenchmark] and [PatternBenchmark], whose `file` parameters share the name. */
fun filterParams(file: Path): Map<String, List<String>> = mapOf(
    FilterBenchmark::file.name to listOf(file.toString()),
    FilterBenchmark::filter.name to benchmarkFilters.keys.toList(),
)

/**
 * Measures [filterRows] on [file], then shows a cached run of each filter and what it finds. [only] measures just
 * the row of that name.
 */
fun benchmarkFiltering(file: Path, runs: Runs, only: String? = null) {
    println("File: $file (${formatByteSize(Files.size(file))})\n")
    printHeader(runs)
    measureOrList(filterRows, filterParams(file), runs, only)
    if (only != null) return
    println()
    parse(file).use { log ->
        for ((name, condition) in benchmarkFilters) {
            val engine = FilterEngine(log)
            val records = runBlocking { engine.prepare(condition).run() }.rows.size
            val started = System.nanoTime()
            runBlocking { engine.prepare(condition).run() }
            val cached = (System.nanoTime() - started) / 1e9
            println("%-58s cached %7.3f s  -> %,d records".format("filter $name", cached, records))
        }
    }
}
