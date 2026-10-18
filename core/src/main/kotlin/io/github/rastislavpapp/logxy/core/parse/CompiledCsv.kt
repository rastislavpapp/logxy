package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Encoding
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Schema

/**
 * CSV lines, see [Csv]: a header row naming the [headers], then one record per line with exactly that many cells.
 * Every column is plain text. A line that is no valid row is a record of its own, shown whole in the first column
 * so that it stays visible and can be found.
 */
class CompiledCsv(private val separator: Char, private val headers: List<String>) : CompiledParser() {

    init {
        require(separator == ',' || separator == ';') { "CSV separator must be ',' or ';'" }
        require(headers.size in 2..Csv.MAX_COLUMNS) { "CSV needs 2 to ${Csv.MAX_COLUMNS} columns" }
    }

    override val schema: Schema = Schema(headers.map { Field(it, ColumnType.SPAN, encoding = Encoding.CSV) })

    override val multiLine get() = false

    override val headerLines get() = 1

    override fun formatTimestamp(field: Int, nanos: Long) = ""

    override fun newMatcher(): LineMatcher = Matcher()

    private inner class Matcher : LineMatcher {
        private val separatorByte = separator.code.toByte()
        private val starts = IntArray(headers.size)
        private val ends = IntArray(headers.size)
        private var lineFrom = 0
        private var valid = false

        // No byte order mark to skip: it can only start the header row, which is never matched.
        override fun match(bytes: ByteArray, from: Int, to: Int): Boolean {
            lineFrom = from
            valid = Csv.cells(bytes, from, to, separatorByte, starts, ends) == headers.size
            return valid || !Bytes.isBlank(bytes, from, to)
        }

        override fun write(out: Columns) {
            if (!valid) {
                out.setSpan(0, 0, Columns.TO_RECORD_END)
                return
            }
            for (field in starts.indices) out.setSpan(field, starts[field] - lineFrom, ends[field] - starts[field])
        }
    }
}
