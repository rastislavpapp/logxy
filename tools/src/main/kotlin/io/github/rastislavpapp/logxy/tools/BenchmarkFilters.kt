package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.RecordTextCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.query.TextSearch

private val warn = LevelCondition.atLeast("Level", Level.WARN)
private val error = LevelCondition.atLeast("Level", Level.ERROR)

/**
 * Typical filters on a generated log, by name: `tools filter` times them, and the baseline check records each as
 * `filter <name>`, so renaming one drops its saved baseline.
 */
val benchmarkFilters: Map<String, Filter> = linkedMapOf(
    "level >= WARN" to warn,
    "thread = main" to TextCondition("Thread", TextOp.EQUALS, "main"),
    "message contains 'timeout'" to TextCondition("Message", TextOp.EQUALS, "*timeout*"),
    "ERROR and logger prefix" to
        Filter.And(listOf(error, TextCondition("Logger", TextOp.EQUALS, "com.example.order*"))),
    "ERROR and message contains 'timeout', any case" to
        Filter.And(listOf(error, TextCondition("Message", TextOp.EQUALS, "*Timeout*", ignoreCase = true))),
    "not (level >= WARN) or thread = main" to
        Filter.Or(listOf(Filter.Not(warn), TextCondition("Thread", TextOp.EQUALS, "main"))),
    "message regex" to TextCondition("Message", TextOp.MATCHES, "order \\d{6}:"),
    // The query bar's regexes always ignore case.
    "message regex, any case" to
        TextCondition("Message", TextOp.MATCHES, "timeout after \\d+ ms", ignoreCase = true),
    "search 'timeout' in records" to RecordTextCondition(TextOp.EQUALS, "*timeout*", ignoreCase = true),
    "search whole word 'timeout' in records" to TextSearch("timeout", wholeWords = true).condition(),
    // Its fixed text is in every record, so the byte search only adds to the regex.
    "search regex 'com\\.example\\.\\w+\\.api\\.' in records" to
        RecordTextCondition(TextOp.MATCHES, "com\\.example\\.\\w+\\.api\\.", ignoreCase = true),
)

/** Row highlights a user might have, as query text: three on dictionary fields, two that read the message. */
val benchmarkHighlights = listOf(
    "level >= ERROR",
    "level >= WARN AND thread == main",
    """logger == "com.example.order*"""",
    """message == "*timeout*"""",
    """message =~ "order \d{6}:"""",
)

/**
 * Typical filters on the merged log of generated logs, by name: `tools merge` times them, and the baseline check
 * records each as `merge: filter <name>`.
 */
val mergedBenchmarkFilters: Map<String, Filter> = linkedMapOf(
    "level >= WARN" to warn,
    "message contains 'timeout'" to TextCondition("Message", TextOp.EQUALS, "*timeout*"),
)
