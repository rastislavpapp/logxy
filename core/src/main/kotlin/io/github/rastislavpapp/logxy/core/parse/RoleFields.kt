package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Encoding
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Role
import java.time.ZoneId

/**
 * The role fields of records made of keys and values (JSON, logfmt): one per role of [keys], filled from the
 * first of the role's keys that a record has. Values are JSON values, or bare text read as written.
 */
internal class RoleFields(keys: RoleKeys) {

    val fields: List<Field> = keys.byRole.map { (role, _) ->
        when (role) {
            Role.TIMESTAMP -> Field("Timestamp", ColumnType.TIMESTAMP, role, optional = true)
            Role.LEVEL -> Field("Level", ColumnType.LEVEL, role, optional = true)
            Role.THREAD -> Field("Thread", ColumnType.DICT, role, encoding = Encoding.JSON, optional = true)
            Role.LOGGER -> Field("Logger", ColumnType.DICT, role, encoding = Encoding.JSON, optional = true)
            Role.MESSAGE -> Field("Message", ColumnType.SPAN, role, encoding = Encoding.JSON)
            else -> Field("Exception", ColumnType.SPAN, role, encoding = Encoding.JSON, optional = true)
        }
    }

    /** Keys shown through the role fields, not as fields of their own. */
    val excluded: Set<String> = keys.byRole.flatMap { it.second }.toSet()

    /** Candidate keys of all roles, the role's field for each, and the key's rank among its role's keys. */
    private val candidates: Array<ByteArray>
    private val candidateFields: IntArray
    private val candidateRanks: IntArray

    init {
        val flat = keys.byRole.withIndex().flatMap { (field, role) ->
            role.second.mapIndexed { rank, key -> Triple(key, field, rank) }
        }
        candidates = Array(flat.size) { flat[it].first.toByteArray() }
        candidateFields = IntArray(flat.size) { flat[it].second }
        candidateRanks = IntArray(flat.size) { flat[it].third }
    }

    /** Writes the role fields of one record at a time. Stateful; one per parser. */
    inner class Writer(zone: ZoneId) {
        private val chosen = IntArray(fields.size)
        private val chosenRank = IntArray(fields.size)
        private val timestamps = JsonTimestampParser(zone)
        private var scratch = ByteArray(256)

        /** Starts a record; then [key] each of its keys, then [write] the chosen values. */
        fun begin() {
            chosen.fill(-1)
        }

        /** Notes the key `bytes[from, to)` of the record's entry [entry]. */
        fun key(bytes: ByteArray, from: Int, to: Int, entry: Int) {
            val length = to - from
            if (length == 0) return
            for (c in candidates.indices) {
                val candidate = candidates[c]
                if (candidate.size != length || candidate[0] != bytes[from]) continue
                if (!java.util.Arrays.equals(bytes, from, to, candidate, 0, length)) continue
                val field = candidateFields[c]
                if (chosen[field] < 0 || candidateRanks[c] < chosenRank[field]) {
                    chosen[field] = entry
                    chosenRank[field] = candidateRanks[c]
                }
            }
        }

        /** Writes the value of each role's chosen entry, whose ranges are in [valueFrom] and [valueTo]. */
        fun write(out: Columns, bytes: ByteArray, lineFrom: Int, valueFrom: IntArray, valueTo: IntArray) {
            for (field in fields.indices) {
                val e = chosen[field]
                if (e >= 0) writeValue(out, field, bytes, lineFrom, valueFrom[e], valueTo[e])
            }
        }

        private fun writeValue(out: Columns, field: Int, bytes: ByteArray, lineFrom: Int, from: Int, to: Int) {
            val isString = Json.isString(bytes, from, to)
            val contentFrom = if (isString) from + 1 else from
            val contentTo = if (isString) to - 1 else to
            when (out.schema.fields[field].type) {
                ColumnType.TIMESTAMP -> {
                    if (timestamps.parse(bytes, contentFrom, contentTo)) out.setTimestamp(field, timestamps.nanos)
                }
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
}
