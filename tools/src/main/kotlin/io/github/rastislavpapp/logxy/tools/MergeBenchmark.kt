// Collecting first keeps garbage out of the heap readings.
@file:Suppress("ExplicitGarbageCollectionCall")

package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.Parser
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Parses [files] one after another, as a session does, then measures merging them, the heap the merge
 * adds, and filtering the merged view. [readChannels] is per file, see [IndexOptions.readChannels].
 */
fun benchmarkMerging(files: List<Path>, iterations: Int, readChannels: Int?) {
    val options = IndexOptions().let { if (readChannels == null) it else it.copy(readChannels = readChannels) }
    println("read channels per file: ${options.readChannels}")
    val (parts, parseSeconds) = measured {
        files.map { file ->
            val compiled = Parser.detect(file).compile() ?: error("No parser detected for $file")
            val (log, seconds) = measured { runBlocking { ParsedLog.build(file, compiled, options = options) } }
            println("$file: %,d records in %.3f s".format(log.recordCount, seconds))
            log
        }
    }
    println("parse all: %.3f s".format(parseSeconds))
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
        val records = first.first.rows.size
        println("%-30s first %.3f s, cached %.3f s -> %,d records".format(name, first.second, cached.second, records))
    }
    parts.forEach { it.close() }
}

private fun <T> measured(block: () -> T): Pair<T, Double> {
    val started = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - started) / 1e9
}
