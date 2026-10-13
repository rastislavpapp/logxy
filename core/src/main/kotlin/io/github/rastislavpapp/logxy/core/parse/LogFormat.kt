package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A saved description of how to read a log: raw lines, a pattern layout, a regex, or JSON lines. */
sealed interface LogFormat {
    val name: String

    val kind: Kind

    /** Compiled form, or null for [Raw]. Throws IllegalArgumentException for an invalid layout. */
    fun compile(): RecordFormat?

    /** This parser under another name; [Raw] keeps its own. */
    fun withName(name: String): LogFormat

    /** Whether [other] reads files the same way, whatever its name. */
    fun sameDefinition(other: LogFormat): Boolean = other.withName(name) == this

    /** The definition as settings properties, without the name and the kind. */
    fun properties(): Map<String, String> = emptyMap()

    /** A kind of parser: what its settings store as the type, and how to read one back. */
    abstract class Kind(val type: String) {
        /** The parser named [name] whose other [properties] are given; null if they are incomplete. */
        abstract fun read(name: String, properties: (String) -> String?): LogFormat?
    }

    data object Raw : LogFormat {
        override val name = "Raw text"
        override val kind = object : Kind("raw") {
            override fun read(name: String, properties: (String) -> String?) = Raw
        }

        override fun compile(): RecordFormat? = null
        override fun withName(name: String) = this
    }

    data class Pattern(override val name: String, val pattern: String) : LogFormat {
        override val kind get() = Companion
        override fun compile() = PatternFormat(pattern)
        override fun withName(name: String) = copy(name = name)
        override fun properties() = mapOf("layout" to pattern)

        companion object : Kind("pattern") {
            override fun read(name: String, properties: (String) -> String?) = properties("layout")?.let { Pattern(name, it) }
        }
    }

    data class Regex(override val name: String, val regex: String, val timestampPattern: String) : LogFormat {
        override val kind get() = Companion
        override fun compile() = RegexFormat(regex, timestampPattern)
        override fun withName(name: String) = copy(name = name)
        override fun properties() = mapOf("layout" to regex, "timestamp" to timestampPattern)

        companion object : Kind("regex") {
            const val DEFAULT_TIMESTAMP = "yyyy-MM-dd HH:mm:ss.SSS"

            override fun read(name: String, properties: (String) -> String?) =
                properties("layout")?.let { Regex(name, it, properties("timestamp") ?: DEFAULT_TIMESTAMP) }
        }
    }

    data class Json(override val name: String, val keys: RoleKeys = RoleKeys()) : LogFormat {
        override val kind get() = Companion
        override fun compile() = JsonFormat(keys)
        override fun withName(name: String) = copy(name = name)
        override fun properties() = keys.properties("keys.")

        companion object : Kind("json") {
            override fun read(name: String, properties: (String) -> String?) = Json(name, RoleKeys.read(properties, "keys.", RoleKeys()))
        }
    }

    companion object {
        /** Every kind of parser, as settings store them. Lazy, as [Raw] may be what is initializing this companion. */
        val KINDS: List<Kind> by lazy { listOf(Raw.kind, Pattern, Regex, Json) }

        /** The most common layouts; others are guessed from the file. */
        val BUILT_IN: List<LogFormat> = listOf(
            Pattern("date [thread] LEVEL logger - message", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n"),
            Pattern("time [thread] LEVEL logger - message", "%d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n"),
            Pattern("Spring Boot 3", "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p %pid --- [%t] %-40.40logger{39} : %m%n"),
            Json("JSON lines"),
        )

        private class Scored(val format: LogFormat, val matches: Int, val preferred: Boolean, val fields: Int, val guessed: Boolean)

        private const val SAMPLE_LINES = 1000

        private data class Detection(
            val path: Path, val size: Long, val modified: Long, val preferred: List<LogFormat>, val builtIn: List<LogFormat>,
        )

        /** Recent results, so the open dialog's checks are not repeated when the files open. */
        private val detected = object : LinkedHashMap<Detection, LogFormat>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Detection, LogFormat>) = size > 512
        }

        /**
         * The candidate that matches most of the file's first lines: the user's own formats ([preferred]),
         * the [builtIn] ones the user kept, and one guessed from the lines. On a tie one of [preferred], then
         * the one with more fields, being more specific, then a built-in one over the guess. [Raw] if none
         * matches at least 5% of the lines that do not look like continuations (a single record may be
         * followed by a huge stack trace or dump).
         */
        fun detect(path: Path, preferred: List<LogFormat> = emptyList(), builtIn: List<LogFormat> = BUILT_IN): LogFormat {
            val key = Detection(
                path.toAbsolutePath().normalize(), Files.size(path), Files.getLastModifiedTime(path).toMillis(), preferred, builtIn,
            )
            synchronized(detected) { detected[key] }?.let { return it }
            return detectFresh(path, preferred, builtIn).also { synchronized(detected) { detected[key] = it } }
        }

        private fun detectFresh(path: Path, preferred: List<LogFormat>, builtIn: List<LogFormat>): LogFormat {
            val sample = FormatPreview.sample(path, SAMPLE_LINES, skipEmpty = true)
            if (sample.isEmpty()) return Raw
            val realLines = sample.count { !FormatCheck.looksLikeContinuation(it, 0, it.size) }
            val guess = FormatInference.infer(sample)
            // Guessed JSON keys refine the built-in JSON format, which reads the same lines.
            val kept = if (guess is Json) builtIn.filter { it !is Json } else builtIn
            val scored = (preferred + kept + listOfNotNull(guess)).mapNotNull { candidate ->
                val compiled = runCatching { candidate.compile() }.getOrNull() ?: return@mapNotNull null
                val parser = compiled.newParser()
                val matches = sample.count { parser.match(it, 0, it.size) }
                Scored(candidate, matches, candidate in preferred, compiled.schema.fields.size, guessed = candidate === guess)
            }
            val best = scored.maxWithOrNull(compareBy({ it.matches }, { it.preferred }, { it.fields }, { !it.guessed })) ?: return Raw
            return if (best.matches > 0 && best.matches * 20 >= realLines) best.format else Raw
        }
    }
}

/** Bytes read by [mismatch]: enough for the first entries, but bounded for a file that starts with a huge dump. */
private const val MISMATCH_CHECK_BYTES = 4L * 1024 * 1024

/**
 * Why this format cannot read [path], judged by the start of the file as opening it does; null if it can, if
 * it is raw text, or if the first few MB do not tell.
 */
fun LogFormat.mismatch(path: Path): String? {
    val compiled = try {
        compile() ?: return null
    } catch (e: IllegalArgumentException) {
        return e.message ?: "Invalid parser"
    }
    return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        try {
            FormatCheck.check(channel, channel.size(), compiled.newParser(), MISMATCH_CHECK_BYTES)
            null
        } catch (e: FormatMismatchException) {
            e.message
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
