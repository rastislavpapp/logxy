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
sealed interface Parser {
    val name: String

    val kind: Kind

    /** Compiled form, or null for [Raw]. Throws IllegalArgumentException for an invalid layout. */
    fun compile(): CompiledParser?

    /** This parser under another name; [Raw] keeps its own. */
    fun withName(name: String): Parser

    /** Whether [other] reads files the same way, whatever its name. */
    fun sameDefinition(other: Parser): Boolean = other.withName(name) == this

    /** The definition as settings properties, without the name and the kind. */
    fun properties(): Map<String, String> = emptyMap()

    /**
     * A kind of parser: what its settings store as the type, and how to read one back.
     *
     * A new kind needs: a variant here whose companion is its Kind, listed in [KINDS]; its [CompiledParser];
     * a line in the Parsers page's `kindChoices`; a [BUILT_IN] entry if it is detected by default (then the
     * built-in names in `SettingsTest` and a detection test in `ParserTest`); a `tools generate` flag for
     * test files and the benchmark.
     */
    abstract class Kind(val type: String) {
        /** The parser named [name] whose other [properties] are given; null if they are incomplete. */
        abstract fun read(name: String, properties: (String) -> String?): Parser?
    }

    /** No parser: one record per line, the line its only field. */
    data object Raw : Parser {
        override val name = "Raw text"
        override val kind = object : Kind("raw") {
            override fun read(name: String, properties: (String) -> String?) = Raw
        }

        override fun compile(): CompiledParser? = null
        override fun withName(name: String) = this
    }

    /** A log4j / logback pattern layout, e.g. `%d [%t] %-5p %c - %m%n`; see [CompiledPattern]. */
    data class Pattern(override val name: String, val pattern: String) : Parser {
        override val kind get() = Companion
        override fun compile() = CompiledPattern(pattern)
        override fun withName(name: String) = copy(name = name)
        override fun properties() = mapOf("layout" to pattern)

        /** Stored as type `pattern`, with the layout under `layout`. */
        companion object : Kind("pattern") {
            override fun read(name: String, properties: (String) -> String?) =
                properties("layout")?.let { Pattern(name, it) }
        }
    }

    /**
     * A regex whose named groups are the fields, with [timestampPattern] for the timestamp group; see
     * [CompiledRegex].
     */
    data class Regex(override val name: String, val regex: String, val timestampPattern: String) : Parser {
        override val kind get() = Companion
        override fun compile() = CompiledRegex(regex, timestampPattern)
        override fun withName(name: String) = copy(name = name)
        override fun properties() = mapOf("layout" to regex, "timestamp" to timestampPattern)

        /** Stored as type `regex`, with the regex under `layout` and the timestamp pattern under `timestamp`. */
        companion object : Kind("regex") {
            /** The timestamp pattern of a regex parser that does not name one. */
            const val DEFAULT_TIMESTAMP = "yyyy-MM-dd HH:mm:ss.SSS"

            override fun read(name: String, properties: (String) -> String?) =
                properties("layout")?.let { Regex(name, it, properties("timestamp") ?: DEFAULT_TIMESTAMP) }
        }
    }

    /** JSON lines, with [keys] naming the keys that fill the usual fields; see [CompiledJson]. */
    data class Json(override val name: String, val keys: RoleKeys = RoleKeys()) : Parser {
        override val kind get() = Companion
        override fun compile() = CompiledJson(keys)
        override fun withName(name: String) = copy(name = name)
        override fun properties() = keys.properties("keys.")

        /** Stored as type `json`, with the keys of each role under `keys.<role>`; missing roles keep the defaults. */
        companion object : Kind("json") {
            override fun read(name: String, properties: (String) -> String?) =
                Json(name, RoleKeys.read(properties, "keys.", RoleKeys()))
        }
    }

    /**
     * logfmt lines (`key=value` pairs), with [keys] naming the keys that fill the usual fields; see
     * [CompiledLogfmt].
     */
    data class Logfmt(override val name: String, val keys: RoleKeys = RoleKeys.LOGFMT) : Parser {
        override val kind get() = Companion
        override fun compile() = CompiledLogfmt(keys)
        override fun withName(name: String) = copy(name = name)
        override fun properties() = keys.properties("keys.")

        /**
         * Stored as type `logfmt`, with the keys of each role under `keys.<role>`; missing roles keep the logfmt
         * defaults.
         */
        companion object : Kind("logfmt") {
            override fun read(name: String, properties: (String) -> String?) =
                Logfmt(name, RoleKeys.read(properties, "keys.", RoleKeys.LOGFMT))
        }
    }

    /** Web server access logs, common or combined layout; nothing to configure. See [CompiledAccessLog]. */
    data class AccessLog(override val name: String) : Parser {
        override val kind get() = Companion
        override fun compile() = CompiledAccessLog()
        override fun withName(name: String) = copy(name = name)

        /** Stored as type `access-log`, with no other properties. */
        companion object : Kind("access-log") {
            override fun read(name: String, properties: (String) -> String?) = AccessLog(name)
        }
    }

    companion object {
        /** Every kind of parser, as settings store them. Lazy, as [Raw] may be what is initializing this companion. */
        val KINDS: List<Kind> by lazy { listOf(Raw.kind, Pattern, Regex, Json, Logfmt, AccessLog) }

        /** The most common layouts; others are guessed from the file. */
        val BUILT_IN: List<Parser> = listOf(
            Pattern("date [thread] LEVEL logger - message", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n"),
            Pattern("time [thread] LEVEL logger - message", "%d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n"),
            Pattern("Spring Boot 3", "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p %pid --- [%t] %-40.40logger{39} : %m%n"),
            Pattern("Python logging", "%d{yyyy-MM-dd HH:mm:ss,SSS} - %c - %p - %m%n"),
            Logfmt("logfmt"),
            AccessLog("Access log"),
            Json("JSON lines"),
        )

        private class Scored(
            val parser: Parser,
            val matches: Int,
            val preferred: Boolean,
            val fields: Int,
            val guessed: Boolean,
        )

        private const val SAMPLE_LINES = 1000

        private data class Detection(
            val path: Path, val size: Long, val modified: Long, val preferred: List<Parser>, val builtIn: List<Parser>,
        )

        /** Recent results, so the open dialog's checks are not repeated when the files open. */
        private val detected = object : LinkedHashMap<Detection, Parser>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Detection, Parser>) = size > 512
        }

        /**
         * The candidate that matches most of the file's first lines: the user's own parsers ([preferred]),
         * the [builtIn] ones the user kept, and one guessed from the lines. On a tie one of [preferred], then
         * the one with more fields, being more specific, then a built-in one over the guess. [Raw] if none
         * matches at least 5% of the lines that do not look like continuations (a single record may be
         * followed by a huge stack trace or dump).
         */
        fun detect(path: Path, preferred: List<Parser> = emptyList(), builtIn: List<Parser> = BUILT_IN): Parser {
            val modified = Files.getLastModifiedTime(path).toMillis()
            val key = Detection(path.toAbsolutePath().normalize(), Files.size(path), modified, preferred, builtIn)
            synchronized(detected) { detected[key] }?.let { return it }
            return detectFresh(path, preferred, builtIn).also { synchronized(detected) { detected[key] = it } }
        }

        private fun detectFresh(path: Path, preferred: List<Parser>, builtIn: List<Parser>): Parser {
            val sample = ParserPreview.sample(path, SAMPLE_LINES, skipEmpty = true)
            if (sample.isEmpty()) return Raw
            val realLines = sample.count { !ParserCheck.looksLikeContinuation(it, 0, it.size) }
            val guess = ParserInference.infer(sample)
            // Guessed JSON keys refine the built-in JSON parser, which reads the same lines.
            val kept = if (guess is Json) builtIn.filter { it !is Json } else builtIn
            val scored = (preferred + kept + listOfNotNull(guess)).mapNotNull { candidate ->
                val compiled = runCatching { candidate.compile() }.getOrNull() ?: return@mapNotNull null
                val matcher = compiled.newMatcher()
                val matches = sample.count { matcher.match(it, 0, it.size) }
                val fields = compiled.schema.fields.size
                Scored(candidate, matches, candidate in preferred, fields, guessed = candidate === guess)
            }
            val ranking = compareBy<Scored>({ it.matches }, { it.preferred }, { it.fields }, { !it.guessed })
            val best = scored.maxWithOrNull(ranking) ?: return Raw
            return if (best.matches > 0 && best.matches * 20 >= realLines) best.parser else Raw
        }
    }
}

/** Bytes read by [mismatch]: enough for the first entries, but bounded for a file that starts with a huge dump. */
private const val MISMATCH_CHECK_BYTES = 4L * 1024 * 1024

/**
 * Why this parser cannot read [path], judged by the start of the file as opening it does; null if it can, if
 * it is raw text, or if the first few MB do not tell.
 */
fun Parser.mismatch(path: Path): String? {
    val compiled = try {
        compile() ?: return null
    } catch (e: IllegalArgumentException) {
        return e.message ?: "Invalid parser"
    }
    return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        try {
            ParserCheck.check(channel, channel.size(), compiled.newMatcher(), MISMATCH_CHECK_BYTES)
            null
        } catch (e: ParserMismatchException) {
            e.message
        }
    }
}

/** Indexes [path] with this parser. */
suspend fun Parser.open(
    path: Path,
    progress: IndexProgress = IndexProgress(),
    levels: LevelMapping = LevelMapping.DEFAULT,
    options: IndexOptions = IndexOptions(),
): LogData {
    val compiled = compile() ?: return LineIndex.build(path, progress, options)
    return ParsedLog.build(path, compiled, progress, options).also { it.levelMapping = levels }
}
