package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.RecordTextCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** Parses [file] with the detected format and times typical filters, first and cached runs. */
fun benchmarkFiltering(file: Path) {
    val compiled = LogFormat.detect(file).compile() ?: error("No format detected for $file")
    val started = System.nanoTime()
    val log = runBlocking { ParsedLog.build(file, compiled) }
    println("File: $file (${formatByteSize(Files.size(file))}), %,d records, parsed in %.2f s\n"
        .format(log.recordCount, (System.nanoTime() - started) / 1e9))

    val warn = LevelCondition.atLeast("Level", Level.WARN)
    val error = LevelCondition.atLeast("Level", Level.ERROR)
    val timeout = TextCondition("Message", TextOp.EQUALS, "*timeout*")
    val filters = listOf(
        "level >= WARN" to warn,
        "thread = main" to TextCondition("Thread", TextOp.EQUALS, "main"),
        "ERROR and logger starts with com.example.order" to
            Filter.And(listOf(error, TextCondition("Logger", TextOp.EQUALS, "com.example.order*"))),
        "message contains 'timeout' (whole file)" to timeout,
        "ERROR and message contains 'timeout'" to Filter.And(listOf(error, TextCondition("Message", TextOp.EQUALS, "*Timeout*", ignoreCase = true))),
        "not (level >= WARN) or thread = main" to
            Filter.Or(listOf(Filter.Not(warn), TextCondition("Thread", TextOp.EQUALS, "main"))),
        "message matches regex 'order \\d{6}:'" to TextCondition("Message", TextOp.MATCHES, "order \\d{6}:"),
        "search 'timeout' in whole records" to RecordTextCondition(TextOp.EQUALS, "*timeout*", ignoreCase = true),
    )
    log.use {
        val engine = FilterEngine(log)
        for ((name, filter) in filters) {
            val times = (1..2).map {
                val t = System.nanoTime()
                val result = runBlocking { engine.prepare(filter).run() }
                (System.nanoTime() - t) / 1e9 to result.rows.size
            }
            println("%-50s first %7.3f s   cached %7.3f s   -> %,d records".format(name, times[0].first, times[1].first, times[0].second))
        }
    }
}
