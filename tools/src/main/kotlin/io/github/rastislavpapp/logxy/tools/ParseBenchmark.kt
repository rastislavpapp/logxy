// Collecting first keeps garbage from earlier runs out of the timings and heap readings.
@file:Suppress("ExplicitGarbageCollectionCall")

package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.CompiledParser
import io.github.rastislavpapp.logxy.core.parse.Parser
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

/**
 * Parsing, the `parse` rows: [file] with [pattern] or the detected parser, and [files] (joined by
 * [File.pathSeparator]) one by one and at once. Open so JMH can generate its subclass.
 */
@State(Scope.Benchmark)
open class ParseBenchmark {
    @Param("")
    @JvmField
    var file = ""

    @Param("")
    @JvmField
    var files = ""

    /** A pattern layout; empty for the detected parser. */
    @Param("")
    @JvmField
    var pattern = ""

    private lateinit var parser: CompiledParser
    private val held = ArrayList<LogData>()

    @Setup(Level.Trial)
    fun compile() {
        if (file.isEmpty()) return
        val path = Path.of(file)
        val detected = if (pattern.isEmpty()) Parser.detect(path) else Parser.Pattern(pattern, pattern)
        parser = detected.compile() ?: error("No parser detected for $file")
    }

    @Setup(Level.Iteration)
    fun release() {
        held.forEach { it.close() }
        held.clear()
        System.gc()
    }

    @TearDown(Level.Trial)
    fun close() = held.forEach { it.close() }

    @Benchmark
    fun parseFile(): LogData = runBlocking { ParsedLog.build(Path.of(file), parser) }.also { held += it }

    @Benchmark
    fun parseFilesOneByOne(): List<LogData> {
        for (path in paths(files)) held += runBlocking { ParsedLog.build(path, detect(path)) }
        return held
    }

    /** As a session opens a merge: several files at once, at most one per core. */
    @Benchmark
    fun parseFilesAtOnce(): List<LogData> {
        val permits = Semaphore(Runtime.getRuntime().availableProcessors())
        held += runBlocking {
            paths(files).map { path ->
                async(Dispatchers.IO) { permits.withPermit { ParsedLog.build(path, detect(path)) } }
            }.awaitAll()
        }
        return held
    }
}

/** Paths joined by [File.pathSeparator], as a parameter of a benchmark holds them. */
fun paths(joined: String): List<Path> = joined.split(File.pathSeparator).map { Path.of(it) }

fun detect(file: Path): CompiledParser = Parser.detect(file).compile() ?: error("No parser detected for $file")

/** The `parse` rows: [file] if given, [files] one by one and at once if given. */
fun parseRows(file: Path?, files: List<Path>): List<Row> {
    val rows = ArrayList<Row>()
    if (file != null) rows += Row(ParseBenchmark::class.java, "parseFile") { "parse file" }
    if (files.isNotEmpty()) {
        rows += Row(ParseBenchmark::class.java, "parseFilesOneByOne") { "parse files one by one" }
        rows += Row(ParseBenchmark::class.java, "parseFilesAtOnce") { "parse files at once" }
    }
    return rows
}

/** Parameters of [ParseBenchmark]. */
fun parseParams(file: Path?, files: List<Path>, pattern: String? = null): Map<String, List<String>> = mapOf(
    "file" to listOf(file?.toString().orEmpty()),
    "files" to listOf(files.joinToString(File.pathSeparator)),
    "pattern" to listOf(pattern.orEmpty()),
)

/**
 * Measures parsing [files]: one file with [pattern] or the detected parser, plus the heap the result holds; several
 * one by one and at once.
 */
fun benchmarkParsing(files: List<Path>, runs: Runs, pattern: String? = null) {
    val single = files.singleOrNull()
    val several = if (single == null) files else emptyList()
    printHeader(runs)
    measure(parseRows(single, several), parseParams(single, several, pattern), runs, ::printRow)
    if (single != null) printHeap(single, pattern)
}

private fun printHeap(file: Path, pattern: String?) {
    val parser = pattern?.let { Parser.Pattern(it, it) } ?: Parser.detect(file)
    val compiled = parser.compile() ?: error("No parser detected for $file")
    val log = runBlocking { ParsedLog.build(file, compiled) }
    val runtime = Runtime.getRuntime()
    System.gc()
    val heap = runtime.totalMemory() - runtime.freeMemory()
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
