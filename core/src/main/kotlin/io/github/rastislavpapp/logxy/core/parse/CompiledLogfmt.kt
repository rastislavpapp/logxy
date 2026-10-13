package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Schema
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * logfmt lines, see [Logfmt]. The keys in [keys] fill the usual fields; every other key becomes a field read
 * from the line on demand. Timestamps as in [CompiledJson]. Lines that are no logfmt (a panic, a stack trace)
 * continue the record before them.
 */
class CompiledLogfmt(
    val keys: RoleKeys = RoleKeys.LOGFMT,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : CompiledParser() {

    private val roles = RoleFields(keys)

    private val mapField = roles.fields.size

    override val schema =
        Schema(roles.fields + Field("logfmt", ColumnType.SPAN, map = LogfmtMap(roles.excluded), json = true))

    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)

    override fun formatTimestamp(field: Int, nanos: Long): String =
        displayFormat.format(Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS), Math.floorMod(nanos, NANOS)))

    override fun newMatcher(): LineMatcher = Matcher()

    private inner class Matcher : LineMatcher {
        private var count = 0
        private var keyFrom = IntArray(16)
        private var keyTo = IntArray(16)
        private var valueFrom = IntArray(16)
        private var valueTo = IntArray(16)
        private val writer = roles.Writer(zone)
        private var bytes = ByteArray(0)
        private var lineFrom = 0
        private var lineTo = 0

        /**
         * A record starts with `key=` and holds at least two pairs with a value, so that prose is not taken for
         * one.
         */
        override fun match(bytes: ByteArray, from: Int, to: Int): Boolean {
            if (from == to || bytes[from] == Logfmt.SPACE || bytes[from] == Logfmt.TAB) return false
            count = 0
            var withValue = 0
            val valid = Logfmt.forEachPair(bytes, from, to) { kFrom, kTo, vFrom, vTo ->
                if (count == keyFrom.size) grow()
                keyFrom[count] = kFrom
                keyTo[count] = kTo
                valueFrom[count] = vFrom
                valueTo[count] = vTo
                count++
                if (vFrom > kTo) {
                    withValue++
                } else if (count == 1) {
                    return false
                }
            }
            if (!valid || withValue < 2) return false
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
            writer.begin()
            for (e in 0..<count) {
                writer.key(bytes, keyFrom[e], keyTo[e], e)
                out.addMapKey(mapField, bytes, keyFrom[e], keyTo[e])
            }
            writer.write(out, bytes, lineFrom, valueFrom, valueTo)
            var end = lineTo
            while (end > lineFrom && Logfmt.isSpace(bytes[end - 1])) end--
            out.setSpan(mapField, 0, end - lineFrom)
        }
    }

    private companion object {
        const val NANOS = 1_000_000_000L
    }
}
