package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A saved description of how to read a log: raw lines, a pattern layout, or a regex. */
sealed interface LogFormat {
    val name: String

    /** Compiled form, or null for [Raw]. Throws IllegalArgumentException for an invalid layout. */
    fun compile(): RecordFormat?

    data object Raw : LogFormat {
        override val name = "Raw text"
        override fun compile(): RecordFormat? = null
    }

    data class Pattern(override val name: String, val pattern: String) : LogFormat {
        override fun compile() = PatternFormat(pattern)
    }

    data class Regex(override val name: String, val regex: String, val timestampPattern: String) : LogFormat {
        override fun compile() = RegexFormat(regex, timestampPattern)
    }

    companion object {
        val BUILT_IN: List<LogFormat> = listOf(
            Pattern("date [thread] LEVEL logger - message", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n"),
            Pattern("date LEVEL [thread] logger - message", "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%t] %c - %m%n"),
            Pattern("date LEVEL [logger] (thread) message", "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%c] (%t) %m%n"),
            Pattern("date LEVEL logger - message", "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p %c - %m%n"),
            Pattern("time [thread] LEVEL logger - message", "%d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n"),
        )

        private const val SAMPLE_BYTES = 256 * 1024
        private const val SAMPLE_LINES = 1000

        /**
         * The candidate that matches most of the file's first lines; on a tie the one with more
         * fields, being more specific. [Raw] if none matches at least 5% of the lines that do not
         * look like continuations (a single record may be followed by a huge stack trace or dump).
         */
        fun detect(path: Path, candidates: List<LogFormat> = BUILT_IN): LogFormat {
            val sample = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val reader = LineReader(channel, 0, minOf(channel.size(), SAMPLE_BYTES.toLong()), SAMPLE_BYTES)
                buildList {
                    while (size < SAMPLE_LINES && reader.next()) {
                        if (reader.lineTo > reader.lineFrom) add(reader.buffer.copyOfRange(reader.lineFrom, reader.lineTo))
                    }
                }
            }
            val realLines = sample.count { !FormatCheck.looksLikeContinuation(it, 0, it.size) }
            if (sample.isEmpty()) return Raw
            val scored = candidates.mapNotNull { candidate ->
                val compiled = runCatching { candidate.compile() }.getOrNull() ?: return@mapNotNull null
                val parser = compiled.newParser()
                Triple(candidate, sample.count { parser.match(it, 0, it.size) }, compiled.schema.fields.size)
            }
            val best = scored.maxWithOrNull(compareBy({ it.second }, { it.third })) ?: return Raw
            return if (best.second > 0 && best.second * 20 >= realLines) best.first else Raw
        }
    }
}

/** Indexes [path] as [format]. */
suspend fun LogFormat.open(path: Path, progress: IndexProgress = IndexProgress()): LogData {
    val compiled = compile() ?: return LineIndex.build(path, progress)
    return ParsedLog.build(path, compiled, progress)
}
