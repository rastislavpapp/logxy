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
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.query.TextSearch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** Parses [file] with the detected parser and times typical filters, first and cached runs. */
fun benchmarkFiltering(file: Path) {
    val compiled = Parser.detect(file).compile() ?: error("No parser detected for $file")
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
        "ERROR and message contains 'timeout'" to
            Filter.And(listOf(error, TextCondition("Message", TextOp.EQUALS, "*Timeout*", ignoreCase = true))),
        "not (level >= WARN) or thread = main" to
            Filter.Or(listOf(Filter.Not(warn), TextCondition("Thread", TextOp.EQUALS, "main"))),
        "message matches regex 'order \\d{6}:'" to TextCondition("Message", TextOp.MATCHES, "order \\d{6}:"),
        "message matches 'timeout after \\d+ ms', any case" to
            TextCondition("Message", TextOp.MATCHES, "timeout after \\d+ ms", ignoreCase = true),
        "search 'timeout' in whole records" to RecordTextCondition(TextOp.EQUALS, "*timeout*", ignoreCase = true),
        "search whole word 'timeout' in whole records" to TextSearch("timeout", wholeWords = true).condition(),
        // Its fixed text is in every record, so the byte search only adds to the regex.
        "search regex 'com\\.example\\.\\w+\\.api\\.' in records" to
            RecordTextCondition(TextOp.MATCHES, "com\\.example\\.\\w+\\.api\\.", ignoreCase = true),
    )
    log.use {
        val engine = FilterEngine(log)
        for ((name, filter) in filters) {
            val times = (1..2).map {
                val t = System.nanoTime()
                val result = runBlocking { engine.prepare(filter).run() }
                (System.nanoTime() - t) / 1e9 to result.rows.size
            }
            println(
                "%-50s first %7.3f s   cached %7.3f s   -> %,d records"
                    .format(name, times[0].first, times[1].first, times[0].second),
            )
        }
    }
}
