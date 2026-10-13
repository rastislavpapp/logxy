package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.log.Level
import java.time.ZoneOffset

/**
 * Guesses the layout of a log from its first lines, as a format the usual parsers then read: the keys of
 * JSON lines, or a pattern layout of a timestamp, a level, the columns between and the message.
 */
internal object FormatInference {

    /** Share of lines a guess has to agree with. */
    private const val AGREEMENT = 0.9

    /** Tokens of a line looked at for its columns; the message comes before this many. */
    private const val MAX_TOKENS = 32

    fun infer(sample: List<ByteArray>): LogFormat? {
        val lines = sample.filter { !FormatCheck.looksLikeContinuation(it, 0, it.size) }
        if (lines.isEmpty()) return null
        return inferJson(lines) ?: inferText(lines)
    }

    // JSON lines

    private class KeyStats {
        var count = 0
        var timestamps = 0
        var levels = 0
        var dotted = 0
        var strings = 0
        var stringLength = 0L
        var traces = 0
    }

    private fun inferJson(lines: List<ByteArray>): LogFormat? {
        val keys = LinkedHashMap<String, KeyStats>()
        val timestamps = JsonTimestampParser(ZoneOffset.UTC)
        var objects = 0
        for (line in lines) {
            if (Json.skipSpace(line, 0, line.size).let { it >= line.size || line[it] != Json.OPEN_OBJECT }) continue
            val valid = Json.forEachEntry(line, 0, line.size) { keyFrom, keyTo, valueFrom, valueTo ->
                val stats = keys.getOrPut(String(line, keyFrom, keyTo - keyFrom, Charsets.UTF_8)) { KeyStats() }
                stats.count++
                val isString = Json.isString(line, valueFrom, valueTo)
                val from = if (isString) valueFrom + 1 else valueFrom
                val to = if (isString) valueTo - 1 else valueTo
                // Numbers count as epoch times only from 2001 on in seconds, so counts and ids do not.
                val timeLike = if (isString) to - from >= 16 && line[from + 4] == '-'.code.toByte() else to - from >= 10
                if (timeLike && timestamps.parse(line, from, to)) stats.timestamps++
                if (to - from in 1..12 && Level.BUILT_IN_NAMES.containsKey(text(line, from, to).uppercase())) stats.levels++
                if (isString) {
                    stats.strings++
                    stats.stringLength += to - from
                    if (isDotted(line, from, to)) stats.dotted++
                    if (Bytes.indexOf(line, TRACE, from, to) >= 0) stats.traces++
                }
            }
            if (valid) objects++
        }
        if (objects == 0 || objects < lines.size * AGREEMENT) return null

        val defaults = RoleKeys()
        val known = defaults.byRole.flatMap { it.second }.toSet()
        val used = HashSet<String>()
        fun pick(defaultKeys: List<String>, minCount: Int, test: (String, KeyStats) -> Boolean): String? {
            if (defaultKeys.any { (keys[it]?.count ?: 0) >= minCount }) return null
            return keys.entries
                .filter { (key, stats) -> key !in used && key !in known && stats.count >= minCount && test(key, stats) }
                .maxByOrNull { it.value.count }?.key?.also { used += it }
        }
        val half = (objects + 1) / 2
        val timestamp = pick(defaults.timestamp, half) { _, s -> s.timestamps >= s.count * AGREEMENT }
        val level = pick(defaults.level, half) { _, s -> s.levels >= s.count * AGREEMENT }
        // Exceptions are rare, so any key that holds a stack trace once.
        val exception = pick(defaults.exception, 1) { _, s -> s.traces > 0 }
        val logger = pick(defaults.logger, half) { _, s -> s.dotted >= s.count * AGREEMENT }
        val thread = pick(defaults.thread, half) { key, _ -> key.contains("thread", ignoreCase = true) }
        val message = keys.entries
            .filter { (key, stats) -> key !in used && key !in known && stats.count >= half && stats.strings >= stats.count * AGREEMENT }
            .takeIf { defaults.message.none { (keys[it]?.count ?: 0) >= half } }
            ?.maxByOrNull { it.value.stringLength / it.value.strings }?.key
        val found = listOfNotNull(timestamp, level, message, logger, thread, exception)
        if (found.isEmpty()) return LogFormat.Json("JSON lines")
        fun first(key: String?, rest: List<String>) = listOfNotNull(key) + rest
        return LogFormat.Json(
            "JSON lines: ${found.joinToString(", ")}",
            RoleKeys(
                first(timestamp, defaults.timestamp), first(level, defaults.level), first(message, defaults.message),
                first(logger, defaults.logger), first(thread, defaults.thread), first(exception, defaults.exception),
            ),
        )
    }

    // Text lines

    /** A timestamp at the start of lines, after [prefix]. */
    private class Stamp(val prefix: String, val layout: String) {
        private val parser = TimestampFormat(layout, ZoneOffset.UTC).newParser()
        private val unixDigits = when (layout) {
            "UNIX" -> 10
            "UNIX_MILLIS" -> 13
            else -> 0
        }

        /** Where the timestamp of [line] ends, its closing `]` included; -1 if it has none. */
        fun end(line: ByteArray): Int {
            if (!Bytes.startsWith(line, 0, line.size, prefix)) return -1
            val end = parser.parse(line, prefix.length, line.size)
            if (end < 0 || end < line.size && isDigit(line[end])) return -1
            if (unixDigits > 0 && end - prefix.length != unixDigits) return -1
            if (prefix.isEmpty()) return end
            return if (end < line.size && line[end] == ']'.code.toByte()) end + 1 else -1
        }

        val conversion get() = "$prefix%d{$layout}" + if (prefix.isEmpty()) "" else "]"
        val label get() = prefix + (if ('y' in layout || 'd' in layout) "date" else "time") + if (prefix.isEmpty()) "" else "]"
    }

    private fun inferText(lines: List<ByteArray>): LogFormat? {
        val stamp = findTimestamp(lines)
        val records = if (stamp == null) lines else lines.filter { stamp.end(it) >= 0 }
        val tokens = records.map { line -> tokenize(line, stamp?.end(line) ?: 0) }
        val signatures = tokens.map { line -> line.map { it.signature } }

        // The longest run of tokens that nearly all lines share; the message varies, so it ends there or before.
        var agreeing = records.indices.toList()
        var length = 0
        while (true) {
            val (signature, count) = agreeing.mapNotNull { signatures[it].getOrNull(length) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value } ?: break
            if (count < records.size * AGREEMENT) break
            agreeing = agreeing.filter { signatures[it].getOrNull(length) == signature }
            length++
        }
        val shape = signatures[agreeing.first()].take(length)
        val columns = (0..<length).map { k -> Column(shape[k], agreeing.map { tokens[it][k] }, agreeing.map { records[it] }) }
        val levelColumn = columns.indexOfFirst { it.isValue && it.share(::isLevel) >= AGREEMENT }.takeIf { it >= 0 }
        // Without a timestamp only a leading level shows that lines start records, as in `[INFO] message`.
        if (stamp == null && (levelColumn == null || columns.take(levelColumn).any { it.isValue })) return null

        val header = headerLength(columns, levelColumn)
        // A guess that does not read the lines is cut short, the message taking more, down to timestamp and message.
        for (end in header downTo 0) {
            if (stamp == null && (levelColumn == null || end <= levelColumn)) break
            val (pattern, name) = layout(stamp, columns, levelColumn, end) ?: continue
            val format = try {
                PatternFormat(pattern)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val reader = format.newParser()
            if (records.count { reader.match(it, 0, it.size) } >= records.size * AGREEMENT) return LogFormat.Pattern(name, pattern)
        }
        return null
    }

    private fun findTimestamp(lines: List<ByteArray>): Stamp? {
        class Score(val stamp: Stamp, val count: Int, val length: Long)
        val scores = STAMP_LAYOUTS.flatMap { layout -> listOf("", "[").map { Stamp(it, layout) } }.map { stamp ->
            var count = 0
            var length = 0L
            for (line in lines) {
                val end = stamp.end(line)
                if (end >= 0) {
                    count++
                    length += end
                }
            }
            Score(stamp, count, length)
        }
        // The longest layout of those that match most: one without the fraction also matches, up to it.
        val best = scores.maxWithOrNull(compareBy({ it.count }, { it.length })) ?: return null
        if (best.count == 0 || best.count * 20 < lines.size) return null
        val first = lines.first { best.stamp.end(it) >= 0 }
        val stamp = withSeparatorsOf(best.stamp, first)
        return withZone(stamp, lines) ?: stamp
    }

    /** [stamp] with the separators of [line]: 'T' or space between date and time, ',' or '.' before the fraction. */
    private fun withSeparatorsOf(stamp: Stamp, line: ByteArray): Stamp {
        if ("MMM" in stamp.layout || stamp.layout.startsWith("UNIX")) return stamp
        val layout = StringBuilder()
        for ((i, c) in stamp.layout.withIndex()) {
            val actual = line.getOrNull(stamp.prefix.length + i)?.toInt()?.toChar()
            layout.append(
                when {
                    c == ' ' && actual == 'T' -> "'T'"
                    c == '.' && actual == ',' -> ","
                    else -> c
                },
            )
        }
        return Stamp(stamp.prefix, layout.toString())
    }

    /** [stamp] with the zone offset nearly all lines have after it, or null if they have none. */
    private fun withZone(stamp: Stamp, lines: List<ByteArray>): Stamp? {
        if (stamp.layout.startsWith("UNIX") || stamp.prefix.isNotEmpty()) return null
        val matching = lines.filter { stamp.end(it) >= 0 }
        val zoned = Stamp(stamp.prefix, stamp.layout + "XXX")
        return zoned.takeIf { matching.count { zoned.end(it) >= 0 } >= matching.size * AGREEMENT }
            ?: Stamp(stamp.prefix, stamp.layout + "XX").takeIf { candidate -> matching.count { candidate.end(it) >= 0 } >= matching.size * AGREEMENT }
    }

    /** Tokens that end the header so the message follows: separators such as ` - `, ` : ` or ` | `. */
    private fun headerLength(columns: List<Column>, levelColumn: Int?): Int {
        val after = (levelColumn ?: -1) + 1
        val separators = (after..<columns.size).filter { columns[it].kind == Kind.SEPARATOR && columns[it].signature != "---" }
        if (separators.isNotEmpty()) {
            // A separator that repeats, as in `a | b | message`, ends the header at its last one.
            val first = columns[separators.first()].signature
            return separators.last { columns[it].signature == first } + 1
        }
        // Without one, the columns that look like fields: brackets, loggers, `key=value`.
        var end = after
        var k = after
        while (k < columns.size) {
            val column = columns[k]
            when {
                column.kind == Kind.SPACE -> {}
                column.kind == Kind.BRACKET -> end = k + 1
                column.kind == Kind.WORD && (column.share(::isDotted) >= AGREEMENT || column.key() != null) -> end = k + 1
                else -> break
            }
            k++
        }
        return end
    }

    /** The pattern layout and name of the timestamp, the first [end] columns as fields and literals, then the message. */
    private fun layout(stamp: Stamp?, columns: List<Column>, levelColumn: Int?, end: Int): Pair<String, String>? {
        val pattern = StringBuilder()
        val name = StringBuilder()
        var fieldLast = false
        stamp?.let {
            pattern.append(it.conversion)
            name.append(it.label)
            fieldLast = it.prefix.isEmpty()
        }
        val unnamed = (0..<end).filter {
            it != levelColumn && columns[it].isValue && columns[it].role() == null && columns[it].key() == null &&
                columns[it].share(::isTraceId) < AGREEMENT
        }
        val threadColumn = unnamed.filter { columns[it].kind == Kind.BRACKET }
            .let { brackets -> brackets.firstOrNull { columns[it].distinct > 1 } ?: brackets.firstOrNull() }
            ?: unnamed.firstOrNull { columns[it].share(::isThreadName) >= 0.5 }
        var logger = false
        var pid = false
        var trace = false
        var generic = 0
        fun field(conversion: String, label: String) {
            pattern.append(conversion)
            name.append(label)
            fieldLast = true
        }
        // A field directly before `{` or `(` would read it as its options; and the message needs text before it.
        fun literal(text: String): Boolean {
            if (fieldLast && text[0] in "{(") return false
            pattern.append(text.replace("%", "%%"))
            name.append(text)
            fieldLast = false
            return true
        }
        for (k in 0..<end) {
            val column = columns[k]
            when (column.kind) {
                Kind.SPACE -> if (!literal(" ")) return null
                Kind.SEPARATOR -> if (!literal(column.signature)) return null
                Kind.WORD, Kind.BRACKET -> {
                    // log4j2's `%X` writes the braces itself.
                    if (column.role() == Role.MDC) {
                        field("%X", "{MDC}")
                        continue
                    }
                    if (column.kind == Kind.BRACKET && !literal(column.signature)) return null
                    val key = column.key()
                    // A field followed by a space alone ends at the first space, so it has to start past its padding.
                    val spaceAfter = k + 1 < columns.size && columns[k + 1].kind == Kind.SPACE &&
                        (k + 2 >= end || columns[k + 2].kind == Kind.WORD || columns[k + 2].role() == Role.MDC)
                    val width = column.width().takeIf { spaceAfter && it > 0 }?.toString().orEmpty()
                    when {
                        k == levelColumn -> field("%${width}p", "LEVEL")
                        key != null -> {
                            // Words start after a space or separator, never right after a field.
                            pattern.append("$key=")
                            field("%X{$key}", key)
                        }
                        column.role() == Role.LOGGER && !logger -> {
                            logger = true
                            field("%${width}c", "logger")
                        }
                        column.role() == Role.NUMBER && !pid && column.distinct <= 3 -> {
                            pid = true
                            field("%${width}pid", "pid")
                        }
                        k == threadColumn -> field("%t", "thread")
                        column.share(::isTraceId) >= AGREEMENT && !trace -> {
                            trace = true
                            field("%${width}X{Trace}", "trace")
                        }
                        else -> field("%${width}X{Field ${++generic}}", "field")
                    }
                    if (column.kind == Kind.BRACKET) literal(CLOSING.getValue(column.signature[0]).toString())
                }
            }
        }
        if (end < columns.size && columns[end].kind == Kind.SPACE) literal(" ")
        if (fieldLast) return null
        pattern.append("%m%n")
        name.append("message")
        return pattern.toString() to name.toString().replace(Regex("\\s+"), " ").trim()
    }

    // Tokens

    private enum class Kind { SPACE, WORD, BRACKET, SEPARATOR }

    private enum class Role { MDC, LOGGER, NUMBER }

    private class Token(val kind: Kind, val from: Int, val to: Int, val signature: String)

    /** The same token of each line that has the shared shape; its values are inside brackets, untrimmed. */
    private class Column(val signature: String, val tokens: List<Token>, val lines: List<ByteArray>) {
        val kind = tokens.first().kind
        val isValue get() = kind == Kind.WORD || kind == Kind.BRACKET

        val values: List<String> = tokens.zip(lines) { token, line ->
            if (kind == Kind.BRACKET) text(line, token.from + 1, token.to - 1) else text(line, token.from, token.to)
        }
        val distinct = values.map { it.trim() }.toSet().size

        fun share(test: (String) -> Boolean) = values.count { test(it.trim()) }.toDouble() / values.size

        fun role(): Role? = when {
            kind == Kind.BRACKET && signature == "{" && share { it.isEmpty() || '=' in it } >= AGREEMENT -> Role.MDC
            share(::isDotted) >= AGREEMENT -> Role.LOGGER
            share { it.isNotEmpty() && it.all(Char::isDigit) } >= AGREEMENT -> Role.NUMBER
            else -> null
        }

        /** The key of `key=value` words, if all have the same one. */
        fun key(): String? {
            if (kind != Kind.WORD) return null
            val key = values.first().substringBefore('=', "")
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it in "_.-" }) return null
            return key.takeIf { values.all { it.startsWith("$key=") } }
        }

        /**
         * The least width of the padding before a word and the word, if some are padded (as by `%5p`):
         * the field must start past the padding, or the space that follows the field ends it at once.
         */
        fun width(): Int {
            if (kind != Kind.WORD) return 0
            var padded = false
            var width = Int.MAX_VALUE
            for ((token, line) in tokens.zip(lines)) {
                var from = token.from
                while (from > 0 && line[from - 1] == SPACE) from--
                val padding = maxOf(0, token.from - from - 1)
                if (padding > 0) padded = true
                width = minOf(width, padding + token.to - token.from)
            }
            return if (padded) width else 0
        }
    }

    private fun tokenize(line: ByteArray, from: Int): List<Token> {
        val tokens = ArrayList<Token>()
        var i = from
        var to = line.size
        while (to > i && line[to - 1] == '\r'.code.toByte()) to--
        while (i < to && tokens.size < MAX_TOKENS) {
            val c = line[i]
            val start = i
            when {
                c == SPACE || c == TAB -> {
                    while (i < to && (line[i] == SPACE || line[i] == TAB)) i++
                    tokens += Token(Kind.SPACE, start, i, " ")
                }
                c.toInt().toChar() in OPENING && closingIndex(line, i, to) >= 0 -> {
                    i = closingIndex(line, i, to) + 1
                    tokens += Token(Kind.BRACKET, start, i, c.toInt().toChar().toString())
                }
                c == PIPE -> {
                    i++
                    tokens += Token(Kind.SEPARATOR, start, i, "|")
                }
                else -> {
                    while (i < to && line[i] != SPACE && line[i] != TAB && line[i] != PIPE && line[i].toInt().toChar() !in OPENING) i++
                    val word = text(line, start, i)
                    when {
                        word.all { it in PUNCTUATION } -> tokens += Token(Kind.SEPARATOR, start, i, word)
                        word.length > 1 && word.endsWith(':') -> {
                            tokens += Token(Kind.WORD, start, i - 1, "w")
                            tokens += Token(Kind.SEPARATOR, i - 1, i, ":")
                        }
                        else -> tokens += Token(Kind.WORD, start, i, "w")
                    }
                }
            }
        }
        return tokens
    }

    /** The index of the bracket that closes the one at [from], or -1 if it does not close before [to]. */
    private fun closingIndex(line: ByteArray, from: Int, to: Int): Int {
        val open = line[from]
        val close = CLOSING.getValue(open.toInt().toChar()).code.toByte()
        var depth = 0
        for (i in from..<to) {
            if (line[i] == open) depth++
            if (line[i] == close && --depth == 0) return i
        }
        return -1
    }

    private fun isLevel(value: String) = value.length in 1..12 && value.all(Char::isLetter) && Level.BUILT_IN_NAMES.containsKey(value.uppercase())

    /** A trace id, or trace and span ids joined by `-` or `,`: long hex numbers. */
    private fun isTraceId(value: String) = TRACE_ID.matches(value)

    /** Names threads usually have, such as `main`, `worker-1` or `pool-1-thread-2`. */
    private fun isThreadName(value: String) = value == "main" || THREAD_NAME.matches(value)

    /** A Java-style qualified name such as `com.example.App` or `c.e.App$Inner`. */
    private fun isDotted(value: String) = DOTTED.matches(value)

    private fun isDotted(bytes: ByteArray, from: Int, to: Int) = to - from in 3..200 && isDotted(text(bytes, from, to))

    private fun text(bytes: ByteArray, from: Int, to: Int) = String(bytes, from, to - from, Charsets.UTF_8)

    private fun isDigit(b: Byte) = b in '0'.code.toByte()..'9'.code.toByte()

    private const val SPACE = ' '.code.toByte()
    private const val TAB = '\t'.code.toByte()
    private const val PIPE = '|'.code.toByte()
    private const val OPENING = "[({"
    private const val PUNCTUATION = "-:>=*#~"
    private val CLOSING = mapOf('[' to ']', '(' to ')', '{' to '}')
    private val TRACE_ID = Regex("[0-9a-f]{16,32}([-,][0-9a-f]{16,32})*")
    private val THREAD_NAME =Regex("[\\w.-]*(-\\d+|[Tt]hread[\\w-]*|exec[\\w-]*)")
    private val DOTTED =Regex("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")

    /** A JSON-escaped line break and the start of a stack frame. */
    private val TRACE = "\\n\\tat ".toByteArray()

    private val STAMP_LAYOUTS: List<String> = buildList {
        val fractions = listOf("", ".SSS", ".SSSSSS", ".SSSSSSSSS")
        for (date in listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "dd.MM.yyyy HH:mm:ss", "HH:mm:ss")) {
            for (fraction in fractions) add(date + fraction)
        }
        for (fraction in listOf("", ".SSS", ",SSS")) add("dd MMM yyyy HH:mm:ss$fraction")
        add("UNIX_MILLIS")
        add("UNIX")
    }
}
