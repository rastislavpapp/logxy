package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.index.LineIndex
import javax.swing.table.AbstractTableModel

/** Longest value prefix shown in the table; the rest is cut off. */
const val MAX_DISPLAY_BYTES = 4096

private const val TAB_WIDTH = 4
private const val CACHE_SIZE = 1024

/**
 * Rows of a [LogData], all records or only those a filter let through. Raw logs get a line number
 * column; for parsed logs the message column is moved last, so it takes the remaining width.
 */
class LogTableModel(val log: LogData) : AbstractTableModel(), RowWindow {

    val showsLineNumbers = log is LineIndex

    /** Field index of each column after the line number column. */
    private val columnFields: List<Int> = log.schema.fields.indices.sortedBy { log.schema.fields[it].role == Role.MESSAGE }

    // Direct-mapped cache: repaints ask for the same few visible rows over and over.
    private val cachedRows = IntArray(CACHE_SIZE) { -1 }
    private val cachedValues = arrayOfNulls<Array<String>>(CACHE_SIZE)

    /** Records shown, in order; null shows all. */
    var filteredRows: IntArray? = null
        private set

    override val totalRowCount get() = filteredRows?.size ?: log.recordCount

    override var windowStart = 0
        set(value) {
            field = value
            if (rowCount > 0) fireTableRowsUpdated(0, rowCount - 1)
        }

    override fun getRowCount() = minOf(totalRowCount, WINDOW_ROWS)

    /** Shows only [rows] (sorted record numbers), or all records if null. */
    fun showRows(rows: IntArray?) {
        filteredRows = rows
        windowStart = 0
        fireTableDataChanged()
    }

    /** The record shown at [row] of the current window. */
    fun recordAt(row: Int): Int = recordAtIndex(windowStart + row)

    /** The record at [index] of all shown rows. */
    fun recordAtIndex(index: Int): Int = filteredRows?.get(index) ?: index

    /** Position of [record] among the shown rows, or of the first shown record after it; -1 if none. */
    fun indexOfRecord(record: Int): Int {
        val rows = filteredRows ?: return if (record < log.recordCount) record else -1
        val found = rows.binarySearch(record)
        val index = if (found >= 0) found else -found - 1
        return if (index < rows.size) index else -1
    }

    override fun getColumnCount() = columnFields.size + if (showsLineNumbers) 1 else 0

    /** The schema field shown in [column], or null for the line number column. */
    fun fieldAt(column: Int): Field? = fieldIndex(column)?.let { log.schema.fields[it] }

    private fun fieldIndex(column: Int): Int? = if (showsLineNumbers) {
        if (column == 0) null else columnFields[column - 1]
    } else {
        columnFields[column]
    }

    override fun getColumnName(column: Int) = fieldAt(column)?.name ?: "#"

    override fun getColumnClass(column: Int) = if (fieldAt(column) == null) Int::class.javaObjectType else String::class.java

    override fun getValueAt(row: Int, column: Int): Any {
        val record = recordAt(row)
        val field = fieldIndex(column) ?: return record + 1
        return values(record)[field]
    }

    /** Canonical level of the record shown in [row], if the format has levels. */
    fun level(row: Int): Level? = (log as? ParsedLog)?.level(recordAt(row))

    private fun values(record: Int): Array<String> {
        val slot = record and (CACHE_SIZE - 1)
        if (cachedRows[slot] == record) return cachedValues[slot]!!
        val values = log.rowValues(record, MAX_DISPLAY_BYTES)
        for (i in values.indices) values[i] = expandTabs(values[i])
        cachedRows[slot] = record
        cachedValues[slot] = values
        return values
    }

    /**
     * Call after records were appended to the log, which had [oldRecordCount] records before. With
     * a filter, call [showRows] with the new result instead.
     */
    fun recordsAppended(oldRecordCount: Int) {
        // The previous last record may have been incomplete and now has more text.
        val lastRecord = oldRecordCount - 1
        if (lastRecord >= 0) {
            val slot = lastRecord and (CACHE_SIZE - 1)
            if (cachedRows[slot] == lastRecord) cachedRows[slot] = -1
        }
        if (filteredRows != null) return
        val oldRows = minOf(oldRecordCount, WINDOW_ROWS)
        if (rowCount > oldRows) fireTableRowsInserted(oldRows, rowCount - 1)
        val lastRow = lastRecord - windowStart
        if (lastRow in 0..<rowCount) fireTableRowsUpdated(lastRow, lastRow)
    }

    // Labels do not render tabs, so expand them to tab stops.
    private fun expandTabs(text: String): String {
        if (text.indexOf('\t') < 0) return text
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            if (c == '\t') {
                do sb.append(' ') while (sb.length % TAB_WIDTH != 0)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
