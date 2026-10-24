// Collecting first keeps garbage from earlier runs out of the timings.
@file:Suppress("ExplicitGarbageCollectionCall")

package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.CompiledParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The logs a parse benchmark made, closed before the next run so that they do not add up in memory. */
@State(Scope.Benchmark)
abstract class ParsedLogs {
    protected val held = ArrayList<LogData>()

    @Setup(Level.Iteration)
    fun closeHeld() {
        held.forEach { it.close() }
        held.clear()
        System.gc()
    }

    @TearDown
    fun tearDown() = held.forEach { it.close() }
}

/** Parsing [file] with the pattern layout [pattern], or the detected parser. Open so JMH can generate its subclass. */
@State(Scope.Benchmark)
open class ParseBenchmark : ParsedLogs() {
    @Param("")
    @JvmField
    var file = ""

    /** A pattern layout; empty for the detected parser. */
    @Param("")
    @JvmField
    var pattern = ""

    private lateinit var parser: CompiledParser

    @Setup
    fun setUp() {
        val path = Path.of(file)
        parser = parserFor(path, pattern.ifEmpty { null }).compileFor(path)
    }

    @Benchmark
    fun parseFile(): LogData = runBlocking { ParsedLog.build(Path.of(file), parser) }.also { held += it }
}

/**
 * Parsing several [files] (joined by [File.pathSeparator]) with the detected parsers, one by one and at once. Open
 * so JMH can generate its subclass.
 */
@State(Scope.Benchmark)
open class ParseFilesBenchmark : ParsedLogs() {
    @Param("")
    @JvmField
    var files = ""

    @Benchmark
    fun oneByOne(): List<LogData> {
        for (path in paths(files)) held += parse(path)
        return held
    }

    /** As a session loads a merge: several files at once, at most one per core. */
    @Benchmark
    fun atOnce(): List<LogData> {
        val permits = Semaphore(Runtime.getRuntime().availableProcessors())
        held += runBlocking {
            val parts = paths(files).map { path ->
                async(Dispatchers.IO) {
                    permits.withPermit { ParsedLog.build(path, parserFor(path).compileFor(path)) }
                }
            }
            parts.awaitAll()
        }
        return held
    }
}

/** Paths joined by [File.pathSeparator], as a parameter of a benchmark holds them. */
fun paths(joined: String): List<Path> = joined.split(File.pathSeparator).map { Path.of(it) }

/** [file] parsed with the detected parser. */
fun parse(file: Path): ParsedLog = runBlocking { ParsedLog.build(file, parserFor(file).compileFor(file)) }

/** The `parse` row of one file. */
val parseFileRows = listOf(BenchmarkRow(ParseBenchmark::class.java, "parseFile", "parse file"))

/** The `parse` rows of several files. */
val parseFilesRows = listOf(
    BenchmarkRow(ParseFilesBenchmark::class.java, "oneByOne", "parse files one by one"),
    BenchmarkRow(ParseFilesBenchmark::class.java, "atOnce", "parse files at once"),
)

/** Parameters of [ParseBenchmark]. */
fun parseFileParams(file: Path, pattern: String? = null): Map<String, List<String>> = mapOf(
    ParseBenchmark::file.name to listOf(file.toString()),
    ParseBenchmark::pattern.name to listOf(pattern.orEmpty()),
)

/** Parameters of [ParseFilesBenchmark]. */
fun parseFilesParams(files: List<Path>): Map<String, List<String>> =
    mapOf(ParseFilesBenchmark::files.name to listOf(files.joinToString(File.pathSeparator)))

/**
 * Measures parsing [files]: one file with [pattern] or the detected parser, plus the heap the result holds; several
 * one by one and at once. [only] measures just the row of that name.
 */
fun benchmarkParsing(files: List<Path>, runs: Runs, pattern: String? = null, only: String? = null) {
    val single = files.singleOrNull()
    require(single != null || pattern == null) { "--pattern applies to one file" }
    printHeader(runs)
    if (single == null) {
        measureOrList(parseFilesRows, parseFilesParams(files), runs, only)
        return
    }
    measureOrList(parseFileRows, parseFileParams(single, pattern), runs, only)
    if (only == null) printHeap(single, pattern)
}

private fun printHeap(file: Path, pattern: String?) {
    val parser = parserFor(file, pattern)
    val compiled = parser.compileFor(file)
    val log = runBlocking { ParsedLog.build(file, compiled) }
    val heap = usedHeap()
    println("\nFile: $file (${formatByteSize(Files.size(file))}), parser: ${parser.name}, %,d records"
        .format(log.recordCount))
    val spans = log.schema.fields.filterIndexed { i, field ->
        val declared = compiled.schema.fields.getOrNull(i)?.type
        declared != null && declared != field.type
    }
    if (spans.isNotEmpty()) println("stored as spans, having mostly distinct values: ${spans.joinToString { it.name }}")
    val keys = log.schema.fields.drop(compiled.schema.fields.size)
    if (keys.isNotEmpty()) println("Map keys as columns: ${keys.joinToString { it.name }}")
    println("heap after GC: ${formatByteSize(heap)} (%.1f bytes per record), timestamps sorted: %s"
        .format(heap.toDouble() / log.recordCount, log.timestampsSorted))
    log.close()
}
