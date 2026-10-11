package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Which top-level keys of a JSON log fill which role; of each list, the first key a record has wins. */
data class JsonKeys(
    val timestamp: List<String> = listOf("@timestamp", "timestamp", "time", "ts"),
    val level: List<String> = listOf("level", "severity", "log.level", "levelname"),
    val message: List<String> = listOf("message", "msg"),
    val logger: List<String> = listOf("logger", "logger_name", "log.logger"),
    val thread: List<String> = listOf("thread", "thread_name", "process.thread.name"),
    val exception: List<String> = listOf("stack_trace", "exception", "error.stack_trace"),
) {
    internal val byRole: List<Pair<Role, List<String>>>
        get() = listOf(
            Role.TIMESTAMP to timestamp, Role.LEVEL to level, Role.THREAD to thread,
            Role.LOGGER to logger, Role.MESSAGE to message, Role.EXCEPTION to exception,
        )
}

/**
 * JSON lines: one object per line. The keys in [keys] fill the usual fields; every other top-level
 * key becomes a field read from the line on demand, so it costs no memory per record. Timestamps are
 * ISO-8601 strings or epoch numbers in seconds, milliseconds, microseconds or nanoseconds (told apart
 * by their size). Nested objects and arrays are shown as written. Lines that are no JSON object become
 * records with only a message.
 */
class JsonFormat(val keys: JsonKeys = JsonKeys(), private val zone: ZoneId = ZoneId.systemDefault()) : RecordFormat() {

    override val schema: Schema

    private val mapField: Int

    /** Candidate keys of all roles, the role's field for each, and the key's rank among its role's keys. */
    private val candidates: Array<ByteArray>
    private val candidateFields: IntArray
    private val candidateRanks: IntArray

    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)

    init {
        val fields = keys.byRole.map { (role, _) ->
            when (role) {
                Role.TIMESTAMP -> Field("Timestamp", ColumnType.TIMESTAMP, role, optional = true)
                Role.LEVEL -> Field("Level", ColumnType.LEVEL, role, optional = true)
                Role.THREAD -> Field("Thread", ColumnType.DICT, role, json = true, optional = true)
                Role.LOGGER -> Field("Logger", ColumnType.DICT, role, json = true, optional = true)
                Role.MESSAGE -> Field("Message", ColumnType.SPAN, role, json = true)
                else -> Field("Exception", ColumnType.SPAN, role, json = true, optional = true)
            }
        }
        val excluded = keys.byRole.flatMap { it.second }.toSet()
        mapField = fields.size
        schema = Schema(fields + Field("JSON", ColumnType.SPAN, map = JsonMap(excluded), json = true))
        val flat = keys.byRole.withIndex().flatMap { (field, role) -> role.second.mapIndexed { rank, key -> Triple(key, field, rank) } }
        candidates = Array(flat.size) { flat[it].first.toByteArray() }
        candidateFields = IntArray(flat.size) { flat[it].second }
        candidateRanks = IntArray(flat.size) { flat[it].third }
    }

    override val multiLine get() = false

    override fun formatTimestamp(field: Int, nanos: Long): String =
        displayFormat.format(Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS), Math.floorMod(nanos, NANOS)))

    override fun detailText(bytes: ByteArray, from: Int, to: Int): String? = Json.pretty(bytes, from, to)

    override fun newParser(): RecordParser = Parser()

    private inner class Parser : RecordParser {
        private var count = 0
        private var keyFrom = IntArray(16)
        private var keyTo = IntArray(16)
        private var valueFrom = IntArray(16)
        private var valueTo = IntArray(16)
        private val chosen = IntArray(schema.fields.size)
        private val chosenRank = IntArray(schema.fields.size)
        private val timestamps = JsonTimestampParser(zone)
        private var scratch = ByteArray(256)
        private var bytes = ByteArray(0)
        private var lineFrom = 0
        private var lineTo = 0

        override fun match(bytes: ByteArray, from: Int, to: Int): Boolean {
            count = 0
            val valid = Json.forEachEntry(bytes, from, to) { kFrom, kTo, vFrom, vTo ->
                if (count == keyFrom.size) grow()
                keyFrom[count] = kFrom
                keyTo[count] = kTo
                valueFrom[count] = vFrom
                valueTo[count] = vTo
                count++
            }
            if (!valid) return false
            this.bytes = bytes
            lineFrom = from
            lineTo = to
            return true
        }

        private fun grow() {
            keyFrom = keyFrom.copyOf(count * 2)
            keyTo = keyTo.copyOf(count * 2)
            valueFrom = valueFrom.copyOf(count * 2)
            valueTo = valueTo.copyOf(count * 2)
        }

        override fun write(out: Columns) {
            chosen.fill(-1)
            for (e in 0..<count) {
                val length = keyTo[e] - keyFrom[e]
                for (c in candidates.indices) {
                    val candidate = candidates[c]
                    if (candidate.size != length || length == 0 || candidate[0] != bytes[keyFrom[e]]) continue
                    if (!java.util.Arrays.equals(bytes, keyFrom[e], keyTo[e], candidate, 0, length)) continue
                    val field = candidateFields[c]
                    if (chosen[field] < 0 || candidateRanks[c] < chosenRank[field]) {
                        chosen[field] = e
                        chosenRank[field] = candidateRanks[c]
                    }
                }
                out.addMapKey(mapField, bytes, keyFrom[e], keyTo[e])
            }
            for (field in 0..<mapField) {
                val e = chosen[field]
                if (e >= 0) writeValue(out, field, valueFrom[e], valueTo[e])
            }
            val start = Json.skipSpace(bytes, lineFrom, lineTo)
            var end = lineTo
            while (end > start && Json.isSpace(bytes[end - 1])) end--
            out.setSpan(mapField, start - lineFrom, end - start)
        }

        private fun writeValue(out: Columns, field: Int, from: Int, to: Int) {
            val isString = Json.isString(bytes, from, to)
            val contentFrom = if (isString) from + 1 else from
            val contentTo = if (isString) to - 1 else to
            when (out.schema.fields[field].type) {
                ColumnType.TIMESTAMP -> if (timestamps.parse(bytes, contentFrom, contentTo)) out.setTimestamp(field, timestamps.nanos)
                ColumnType.LEVEL -> out.setLevel(field, bytes, contentFrom, contentTo)
                ColumnType.DICT -> {
                    if (!isString || Json.isPlain(bytes, contentFrom, contentTo)) {
                        out.setDict(field, bytes, contentFrom, contentTo)
                    } else {
                        if (scratch.size < contentTo - contentFrom) scratch = ByteArray(contentTo - contentFrom)
                        out.setDict(field, scratch, 0, Json.unescape(bytes, contentFrom, contentTo, scratch, 0))
                    }
                }
                ColumnType.SPAN -> out.setSpan(field, from - lineFrom, to - from)
            }
        }
    }

    private companion object {
        const val NANOS = 1_000_000_000L
    }
}

/**
 * Parses JSON timestamps: ISO-8601 text (`2026-10-01T08:00:00.123Z`, a space for the `T`, any number
 * of fraction digits, an offset or none, then [zone] applies), or epoch numbers. Caches the minute,
 * which consecutive records usually share.
 */
internal class JsonTimestampParser(private val zone: ZoneId) {
    var nanos = 0L
        private set

    private val cachedPrefix = ByteArray(PREFIX)
    private var cachedUtcMinute = Long.MIN_VALUE
    private var cachedZonedMinute = Long.MIN_VALUE

    /** Parses all of `bytes[from, to)` (a string's content or a number); returns false if it is no timestamp. */
    fun parse(bytes: ByteArray, from: Int, to: Int): Boolean {
        if (to - from >= PREFIX && bytes[from + 4] == '-'.code.toByte()) return parseIso(bytes, from, to)
        return parseEpoch(bytes, from, to)
    }

    private fun parseIso(bytes: ByteArray, from: Int, to: Int): Boolean {
        val cached = cachedUtcMinute != Long.MIN_VALUE && java.util.Arrays.equals(bytes, from, from + PREFIX, cachedPrefix, 0, PREFIX)
        if (!cached && !cacheMinute(bytes, from)) return false
        var i = from + PREFIX
        var second = 0
        var fraction = 0L
        if (i < to && bytes[i] == ':'.code.toByte()) {
            second = digits(bytes, i + 1, 2, to)
            if (second !in 0..60) return false
            i += 3
            if (i < to && (bytes[i] == '.'.code.toByte() || bytes[i] == ','.code.toByte())) {
                i++
                var scale = 100_000_000L
                val start = i
                while (i < to && bytes[i] in ZERO..NINE) {
                    fraction += (bytes[i] - ZERO) * scale
                    scale /= 10
                    i++
                }
                if (i == start) return false
            }
        }
        val minute = if (i == to) {
            zonedMinute()
        } else {
            val offset = offsetSeconds(bytes, i, to)
            if (offset == NO_OFFSET) return false
            cachedUtcMinute - offset
        }
        nanos = (minute + second) * NANOS + fraction
        return true
    }

    /** Caches the epoch second of `yyyy-MM-ddTHH:mm` at [from], read as UTC. */
    private fun cacheMinute(bytes: ByteArray, from: Int): Boolean {
        val separator = bytes[from + 10]
        if (bytes[from + 7] != '-'.code.toByte() || bytes[from + 13] != ':'.code.toByte()) return false
        if (separator != 'T'.code.toByte() && separator != ' '.code.toByte()) return false
        val year = digits(bytes, from, 4, from + 4)
        val month = digits(bytes, from + 5, 2, from + 7)
        val day = digits(bytes, from + 8, 2, from + 10)
        val hour = digits(bytes, from + 11, 2, from + 13)
        val minute = digits(bytes, from + 14, 2, from + 16)
        if (year < 0 || month < 0 || day < 0 || hour !in 0..23 || minute !in 0..59) return false
        cachedUtcMinute = try {
            LocalDate.of(year, month, day).toEpochDay() * 86_400 + hour * 3600 + minute * 60
        } catch (_: DateTimeException) {
            return false
        }
        cachedZonedMinute = Long.MIN_VALUE
        System.arraycopy(bytes, from, cachedPrefix, 0, PREFIX)
        return true
    }

    private fun zonedMinute(): Long {
        if (cachedZonedMinute == Long.MIN_VALUE) {
            cachedZonedMinute = LocalDateTime.ofEpochSecond(cachedUtcMinute, 0, ZoneOffset.UTC).atZone(zone).toEpochSecond()
        }
        return cachedZonedMinute
    }

    /** `Z`, `+HH`, `+HHMM` or `+HH:MM` making up all of `bytes[from, to)`; [NO_OFFSET] if it is none. */
    private fun offsetSeconds(bytes: ByteArray, from: Int, to: Int): Long {
        val sign = bytes[from].toInt().toChar()
        if ((sign == 'Z' || sign == 'z') && to == from + 1) return 0
        if (sign != '+' && sign != '-') return NO_OFFSET
        val hours = digits(bytes, from + 1, 2, to)
        var i = from + 3
        if (i < to && bytes[i] == ':'.code.toByte()) i++
        val minutes = if (i == to) 0 else digits(bytes, i, 2, to).also { i += 2 }
        if (hours !in 0..18 || minutes !in 0..59 || i != to) return NO_OFFSET
        val seconds = hours * 3600L + minutes * 60L
        return if (sign == '-') -seconds else seconds
    }

    /** A whole number of seconds, milliseconds, microseconds or nanoseconds, told apart by its digits; seconds may have a fraction. */
    private fun parseEpoch(bytes: ByteArray, from: Int, to: Int): Boolean {
        var i = from
        var value = 0L
        while (i < to && bytes[i] in ZERO..NINE && i - from < 19) {
            value = value * 10 + (bytes[i] - ZERO)
            i++
        }
        val integerDigits = i - from
        if (integerDigits == 0) return false
        val unit = when {
            integerDigits <= 11 -> NANOS
            integerDigits <= 14 -> 1_000_000L
            integerDigits <= 17 -> 1_000L
            else -> 1L
        }
        var fraction = 0L
        if (i < to && bytes[i] == '.'.code.toByte()) {
            i++
            var scale = unit / 10
            while (i < to && bytes[i] in ZERO..NINE) {
                fraction += (bytes[i] - ZERO) * scale
                scale /= 10
                i++
            }
        }
        if (i != to) return false
        nanos = value * unit + fraction
        return true
    }

    private fun digits(bytes: ByteArray, from: Int, count: Int, to: Int): Int {
        if (from + count > to) return -1
        var value = 0
        for (i in from..<from + count) {
            val digit = bytes[i] - ZERO
            if (digit !in 0..9) return -1
            value = value * 10 + digit
        }
        return value
    }

    private companion object {
        const val NANOS = 1_000_000_000L
        const val PREFIX = 16
        const val NO_OFFSET = Long.MIN_VALUE
        const val ZERO = '0'.code.toByte()
        const val NINE = '9'.code.toByte()
    }
}
