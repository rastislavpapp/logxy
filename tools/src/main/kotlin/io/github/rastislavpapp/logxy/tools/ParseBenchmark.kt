package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.Parser
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** Measures parsing [file] with [pattern] or the detected parser, plus the heap the result holds. */
fun benchmarkParsing(file: Path, iterations: Int, pattern: String? = null) {
    val parser = pattern?.let { Parser.Pattern(it, it) } ?: Parser.detect(file)
    val compiled = parser.compile() ?: error("No parser detected for $file")
    val size = Files.size(file)
    println("File: $file (${formatByteSize(size)}), parser: ${parser.name}\n")
    var log: ParsedLog? = null
    repeat(iterations) { i ->
        log?.close()
        log = null
        System.gc()
        val started = System.nanoTime()
        val parsed = runBlocking { ParsedLog.build(file, compiled) }
        val seconds = (System.nanoTime() - started) / 1e9
        log = parsed
        println("run ${i + 1}: %.3f s (%.2f GB/s), %,d records".format(seconds, size / seconds / 1e9, parsed.recordCount))
    }
    val runtime = Runtime.getRuntime()
    System.gc()
    val heap = runtime.totalMemory() - runtime.freeMemory()
    val last = log!!
    val spans = last.schema.fields.filterIndexed { i, field -> compiled.schema.fields.getOrNull(i)?.type.let { it != null && it != field.type } }
    if (spans.isNotEmpty()) println("stored as spans, having mostly distinct values: ${spans.joinToString { it.name }}")
    val keys = last.schema.fields.drop(compiled.schema.fields.size)
    if (keys.isNotEmpty()) println("Map keys as columns: ${keys.joinToString { it.name }}")
    println("\nheap after GC: ${formatByteSize(heap)} (%.1f bytes per record), timestamps sorted: %s"
        .format(heap.toDouble() / last.recordCount, last.timestampsSorted))
    last.close()
}
