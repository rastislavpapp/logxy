package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Parses [files], then measures merging them, the heap the merge adds, and filtering the merged view. */
fun benchmarkMerging(files: List<Path>, iterations: Int) {
    val parts = files.map { file ->
        val compiled = LogFormat.detect(file).compile() ?: error("No built-in format matches $file")
        runBlocking { ParsedLog.build(file, compiled) }.also { println("$file: %,d records".format(it.recordCount)) }
    }
    val runtime = Runtime.getRuntime()
    fun heap(): Long {
        System.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    var merged: MergedLog? = null
    repeat(iterations) { i ->
        merged = null
        val before = heap()
        val started = System.nanoTime()
        val log = runBlocking { MergedLog.build(parts) }
        val seconds = (System.nanoTime() - started) / 1e9
        merged = log
        val added = heap() - before
        println("merge ${i + 1}: %.3f s, %,d records, heap +%s (%.1f bytes per record)"
            .format(seconds, log.recordCount, formatByteSize(added), added.toDouble() / log.recordCount))
    }
    val log = merged!!
    val filter = MergedFilter(log)
    for ((name, condition) in listOf(
        "level >= WARN" to LevelCondition.atLeast("Level", Level.WARN),
        "message contains 'timeout'" to TextCondition("Message", TextOp.EQUALS, "*timeout*"),
    )) {
        val first = measured { runBlocking { filter.prepare(condition, 0).run() } }
        val cached = measured { runBlocking { filter.prepare(condition, 0).run() } }
        println("%-30s first %.3f s, cached %.3f s -> %,d records".format(name, first.second, cached.second, first.first.rows.size))
    }
    parts.forEach { it.close() }
}

private fun <T> measured(block: () -> T): Pair<T, Double> {
    val started = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - started) / 1e9
}
