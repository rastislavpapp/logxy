package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Web server access logs in the common log format,
 * `host ident user [13/Oct/2026:10:00:00 +0200] "GET /path HTTP/1.1" 200 512`, or the combined one, which
 * adds a quoted referer and user agent; anything after those is ignored. `-` means no value, except in the
 * request, which is shown as written. There is no level: the status says more, and is a field of its own.
 */
class CompiledAccessLog(
    private val zone: ZoneId = ZoneId.systemDefault(),
) : CompiledParser() {

    override val schema = Schema(
        listOf(
            Field("Host", ColumnType.DICT),
            Field("Ident", ColumnType.DICT, optional = true),
            Field("User", ColumnType.DICT, optional = true),
            Field("Timestamp", ColumnType.TIMESTAMP, Role.TIMESTAMP),
            Field("Method", ColumnType.DICT, optional = true),
            Field("Request", ColumnType.SPAN, Role.MESSAGE),
            Field("Status", ColumnType.DICT),
            Field("Bytes", ColumnType.SPAN, optional = true),
            Field("Referer", ColumnType.DICT, optional = true),
            Field("User agent", ColumnType.DICT, optional = true),
        ),
    )

    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

    override val multiLine get() = false

    override fun formatTimestamp(field: Int, nanos: Long): String =
        displayFormat.format(Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS), Math.floorMod(nanos, NANOS)))

    override fun newMatcher(): LineMatcher = Matcher()

    private class Matcher : LineMatcher {
        private val timestamps = AccessLogTimestamp()

        /** Start and end of each field's value in [bytes], quotes left out; start -1 for none. */
        private val starts = IntArray(FIELDS)
        private val ends = IntArray(FIELDS)
        private var bytes = ByteArray(0)
        private var lineFrom = 0

        override fun match(bytes: ByteArray, from: Int, to: Int): Boolean {
            this.bytes = bytes
            lineFrom = from
            starts.fill(-1)
            var end = to
            while (end > from && bytes[end - 1] == SPACE) end--
            return matchFields(bytes, from, end)
        }

        private fun matchFields(bytes: ByteArray, from: Int, to: Int): Boolean {
            var i = token(bytes, from, to, HOST)
            if (!at(i, to, SPACE)) return false
            i = token(bytes, i + 1, to, IDENT)
            if (!at(i, to, SPACE)) return false
            i = token(bytes, i + 1, to, USER)
            if (!at(i, to, SPACE) || !at(i + 1, to, OPEN) || !at(i + 2 + TIMESTAMP_LENGTH, to, CLOSE)) return false
            if (!timestamps.parse(bytes, i + 2, i + 2 + TIMESTAMP_LENGTH)) return false
            i += 3 + TIMESTAMP_LENGTH
            if (!at(i, to, SPACE) || !at(i + 1, to, QUOTE)) return false
            i = quoted(bytes, i + 1, to, REQUEST)
            if (!at(i, to, SPACE) || !at(i + 4, to, SPACE)) return false
            if (!isDigit(bytes[i + 1]) || !isDigit(bytes[i + 2]) || !isDigit(bytes[i + 3])) return false
            set(STATUS, i + 1, i + 4)
            i = token(bytes, i + 5, to, BYTES)
            if (i < 0) return false
            if (i < to) {
                if (!at(i, to, SPACE) || !at(i + 1, to, QUOTE)) return false
                i = quoted(bytes, i + 1, to, REFERER)
                if (!at(i, to, SPACE) || !at(i + 1, to, QUOTE)) return false
                if (quoted(bytes, i + 1, to, USER_AGENT) < 0) return false
            }
            method()
            return true
        }

        /** Reads a value up to the next space or [to] into [field]; returns where it ends, -1 if it is empty. */
        private fun token(bytes: ByteArray, from: Int, to: Int, field: Int): Int {
            var i = from
            while (i < to && bytes[i] != SPACE) i++
            if (i == from) return -1
            set(field, from, i)
            return i
        }

        /** Reads the quoted value at `bytes[from]` into [field]; returns the index after the closing quote, or -1. */
        private fun quoted(bytes: ByteArray, from: Int, to: Int, field: Int): Int {
            val end = Logfmt.quotedEnd(bytes, from, to)
            if (end < 0) return -1
            if (field == REQUEST) {
                starts[field] = from + 1
                ends[field] = end - 1
            } else {
                set(field, from + 1, end - 1)
            }
            return end
        }

        /** Notes a value, unless it is `-`. */
        private fun set(field: Int, from: Int, to: Int) {
            if (to - from == 1 && bytes[from] == DASH) return
            starts[field] = from
            ends[field] = to
        }

        /** The method that starts the request, if it is one: uppercase letters and a space. */
        private fun method() {
            val from = starts[REQUEST]
            val to = ends[REQUEST]
            var i = from
            while (i < to && i - from <= MAX_METHOD && bytes[i] in UPPER_A..UPPER_Z) i++
            if (i > from && i < to && bytes[i] == SPACE) {
                starts[METHOD] = from
                ends[METHOD] = i
            }
        }

        override fun write(out: Columns) {
            for (field in 0..<FIELDS) {
                val from = starts[field]
                if (from < 0) continue
                if (field == REQUEST || field == BYTES) {
                    out.setSpan(field, from - lineFrom, ends[field] - from)
                } else {
                    out.setText(field, bytes, lineFrom, from, ends[field])
                }
            }
            // The timestamp has no range of its own; its value was parsed while matching.
            out.setTimestamp(TIMESTAMP, timestamps.nanos)
        }

        /** Whether [i], a result of [token] or [quoted] (-1 if none), is before [to] and holds [b]. */
        private fun at(i: Int, to: Int, b: Byte) = i in 0..<to && bytes[i] == b

        private fun isDigit(b: Byte) = b in ZERO..NINE
    }

    private companion object {
        const val NANOS = 1_000_000_000L
        const val FIELDS = 10
        const val HOST = 0
        const val IDENT = 1
        const val USER = 2
        const val TIMESTAMP = 3
        const val METHOD = 4
        const val REQUEST = 5
        const val STATUS = 6
        const val BYTES = 7
        const val REFERER = 8
        const val USER_AGENT = 9
        const val TIMESTAMP_LENGTH = 26
        const val MAX_METHOD = 20
        const val SPACE = ' '.code.toByte()
        const val QUOTE = '"'.code.toByte()
        const val OPEN = '['.code.toByte()
        const val CLOSE = ']'.code.toByte()
        const val DASH = '-'.code.toByte()
        const val ZERO = '0'.code.toByte()
        const val NINE = '9'.code.toByte()
        const val UPPER_A = 'A'.code.toByte()
        const val UPPER_Z = 'Z'.code.toByte()
    }
}
