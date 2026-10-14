package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.Parser
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses [file] with the detected parser and times [benchmarkFilters]: [iterations] first runs on new engines,
 * then one cached run.
 */
fun benchmarkFiltering(file: Path, iterations: Int) {
    val compiled = Parser.detect(file).compile() ?: error("No parser detected for $file")
    val started = System.nanoTime()
    val log = runBlocking { ParsedLog.build(file, compiled) }
    println("File: $file (${formatByteSize(Files.size(file))}), %,d records, parsed in %.2f s, %d iterations\n"
        .format(log.recordCount, (System.nanoTime() - started) / 1e9, iterations))

    log.use {
        val engines = Array(benchmarkFilters.size) { FilterEngine(log) }
        val firstRuns = benchmarkFilters.map { DoubleArray(iterations) }
        val counts = IntArray(benchmarkFilters.size)
        // Round by round over all filters, so each one runs on code the JIT compiled for the whole mix.
        for (i in 0..<iterations) {
            for ((f, named) in benchmarkFilters.withIndex()) {
                val engine = FilterEngine(log)
                val t = System.nanoTime()
                counts[f] = runBlocking { engine.prepare(named.second).run() }.rows.size
                firstRuns[f][i] = (System.nanoTime() - t) / 1e9
                engines[f] = engine
            }
        }
        for ((f, named) in benchmarkFilters.withIndex()) {
            val t = System.nanoTime()
            runBlocking { engines[f].prepare(named.second).run() }
            val cached = (System.nanoTime() - t) / 1e9
            val sorted = firstRuns[f].sorted()
            println(
                "%-50s first: best %7.3f s  median %7.3f s   cached %7.3f s   -> %,d records"
                    .format(named.first, sorted.first(), sorted[sorted.size / 2], cached, counts[f]),
            )
        }
    }
}
