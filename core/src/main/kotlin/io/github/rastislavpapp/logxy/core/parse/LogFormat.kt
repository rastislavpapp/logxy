package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import java.nio.file.Path

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
            Pattern("Spring Boot 2", "%d{yyyy-MM-dd HH:mm:ss.SSS} %5p %pid --- [%t] %-40.40logger{39} : %m%n"),
            Pattern("Spring Boot 3", "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p %pid --- [%t] %-40.40logger{39} : %m%n"),
            Pattern(
                "Spring Boot 3 with application name",
                "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p %pid --- [%X{application}] [%t] %-40.40logger{39} : %m%n",
            ),
        )

        private class Scored(val format: LogFormat, val matches: Int, val preferred: Boolean, val fields: Int)

        private const val SAMPLE_LINES = 1000

        /**
         * The candidate that matches most of the file's first lines; on a tie one of [preferred]
         * (the user's own formats), then the one with more fields, being more specific. [Raw] if
         * none matches at least 5% of the lines that do not look like continuations (a single
         * record may be followed by a huge stack trace or dump).
         */
        fun detect(path: Path, preferred: List<LogFormat> = emptyList()): LogFormat {
            val candidates = preferred + BUILT_IN
            val sample = FormatPreview.sample(path, SAMPLE_LINES, skipEmpty = true)
            val realLines = sample.count { !FormatCheck.looksLikeContinuation(it, 0, it.size) }
            if (sample.isEmpty()) return Raw
            val scored = candidates.mapNotNull { candidate ->
                val compiled = runCatching { candidate.compile() }.getOrNull() ?: return@mapNotNull null
                val parser = compiled.newParser()
                Scored(candidate, sample.count { parser.match(it, 0, it.size) }, candidate in preferred, compiled.schema.fields.size)
            }
            val best = scored.maxWithOrNull(compareBy({ it.matches }, { it.preferred }, { it.fields })) ?: return Raw
            return if (best.matches > 0 && best.matches * 20 >= realLines) best.format else Raw
        }
    }
}

/** Indexes [path] as [format]. */
suspend fun LogFormat.open(
    path: Path,
    progress: IndexProgress = IndexProgress(),
    levels: LevelMapping = LevelMapping.DEFAULT,
    options: IndexOptions = IndexOptions(),
): LogData {
    val compiled = compile() ?: return LineIndex.build(path, progress, options)
    return ParsedLog.build(path, compiled, progress, options).also { it.levelMapping = levels }
}
