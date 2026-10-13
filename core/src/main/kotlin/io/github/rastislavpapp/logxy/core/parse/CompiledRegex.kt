package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.ZoneId
import java.util.regex.Pattern

/**
 * Fallback for layouts the pattern syntax cannot express: a regex matched at the start of each line,
 * whose named groups become fields. Group names pick the role, e.g. `timestamp`, `level`, `thread`,
 * `logger`, `message`. Slower than [CompiledPattern], since every line runs through the regex engine.
 */
class CompiledRegex(
    val regex: String,
    /** Layout of the `timestamp` group, in the same syntax as `%d{...}`. */
    val timestampPattern: String = "yyyy-MM-dd HH:mm:ss.SSS",
    zone: ZoneId = ZoneId.systemDefault(),
) : CompiledParser() {

    private val compiled = Pattern.compile(regex)
    private val groups: List<Pair<String, Int>> = compiled.namedGroups().entries
        .sortedBy { it.value }
        .map { it.key to it.value }
    private val timestampFormat = TimestampFormat(timestampPattern, zone)
    private val messageField: Int

    override val schema: Schema

    init {
        require(groups.isNotEmpty()) { "The regex has no named groups, e.g. (?<message>.*)" }
        schema = Schema(groups.map { (name, _) -> fieldFor(name) })
        messageField = schema.fields.indexOfFirst { it.role == Role.MESSAGE }
    }

    override fun formatTimestamp(field: Int, nanos: Long) = timestampFormat.format(nanos)

    override fun newMatcher(): LineMatcher = Matcher()

    private inner class Matcher : LineMatcher {
        private val matcher: java.util.regex.Matcher = compiled.matcher("")
        private val timestampParser = timestampFormat.newParser()
        private var bytes = ByteArray(0)
        private var lineFrom = 0
        private var lineTo = 0

        override fun match(bytes: ByteArray, from: Int, to: Int): Boolean {
            matcher.reset(Latin1Chars(bytes, from, to))
            if (!matcher.lookingAt()) return false
            this.bytes = bytes
            lineFrom = from
            lineTo = to
            return true
        }

        override fun write(out: Columns) {
            for ((field, group) in groups.withIndex()) {
                val start = matcher.start(group.second)
                if (start < 0) continue
                val end = matcher.end(group.second)
                val from = lineFrom + start
                val to = lineFrom + end
                when (schema.fields[field].type) {
                    ColumnType.TIMESTAMP ->
                        if (timestampParser.parse(bytes, from, to) == to) out.setTimestamp(field, timestampParser.nanos)
                    ColumnType.LEVEL -> out.setLevel(field, bytes, from, to)
                    ColumnType.DICT -> out.setText(field, bytes, lineFrom, from, to)
                    ColumnType.SPAN -> {
                        val toEnd = field == messageField && to == lineTo
                        out.setSpan(field, start, if (toEnd) Columns.TO_RECORD_END else end - start)
                    }
                }
            }
        }
    }

    private companion object {
        fun fieldFor(group: String): Field = when (group.lowercase()) {
            "timestamp", "time", "date", "datetime" -> Field("Timestamp", ColumnType.TIMESTAMP, Role.TIMESTAMP)
            "level", "severity", "priority" -> Field("Level", ColumnType.LEVEL, Role.LEVEL)
            "thread" -> Field("Thread", ColumnType.DICT, Role.THREAD)
            "logger", "category", "source" -> Field("Logger", ColumnType.DICT, Role.LOGGER)
            "message", "msg" -> Field("Message", ColumnType.SPAN, Role.MESSAGE)
            else -> Field(group, ColumnType.SPAN)
        }
    }
}
