package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.session.FilteredRows
import javax.swing.table.AbstractTableModel

/** Longest value prefix shown in the table; the rest is cut off. */
const val MAX_DISPLAY_BYTES = 4096

private const val TAB_WIDTH = 4
private const val CACHE_SIZE = 1024

/**
 * Rows of [Records], all records or only those a filter let through. Raw logs get a line number
 * column; for parsed logs the message column is moved last, so it takes the remaining width.
 */
class LogTableModel(val records: Records) : AbstractTableModel(), RowWindow {

    val showsLineNumbers = records.isRaw

    /** Field index of each column after the line number column. */
    private val columnFields: List<Int> = records.schema.fields.indices.sortedBy { records.schema.fields[it].role == Role.MESSAGE }

    // Direct-mapped cache: repaints ask for the same few visible rows over and over.
    private val cachedRows = IntArray(CACHE_SIZE) { -1 }
    private val cachedValues = arrayOfNulls<Array<String>>(CACHE_SIZE)

    /** Records shown, in order; null shows all. Owned by the session, which changes it and then tells the model. */
    private var filtered: FilteredRows? = null

    val isFiltered get() = filtered != null

    override val totalRowCount get() = filtered?.size ?: records.recordCount

    override var windowStart = 0
        set(value) {
            field = value
            if (rowCount > 0) fireTableRowsUpdated(0, rowCount - 1)
        }

    override fun getRowCount() = minOf(totalRowCount, WINDOW_ROWS)

    /** Shows only [rows], or all records if null. */
    fun showRows(rows: FilteredRows?) {
        filtered = rows
        windowStart = 0
        fireTableDataChanged()
    }

    /** The record shown at [row] of the current window. */
    fun recordAt(row: Int): Int = recordAtIndex(windowStart + row)

    /** The record at [index] of all shown rows. */
    fun recordAtIndex(index: Int): Int = filtered?.get(index) ?: index

    /** Position of [record] among the shown rows, or of the first shown record after it; -1 if none. */
    fun indexOfRecord(record: Int): Int {
        val rows = filtered ?: return if (record < records.recordCount) record else -1
        return rows.indexOf(record)
    }

    /** Call after the filtered rows changed from position [firstChanged] on; there were [oldSize] before. */
    fun rowsChangedFrom(firstChanged: Int, oldSize: Int) {
        val newSize = filtered?.size ?: return
        // Only the part of the window from the first change on is affected.
        val oldRows = (oldSize - windowStart).coerceIn(0, WINDOW_ROWS)
        val newRows = (newSize - windowStart).coerceIn(0, WINDOW_ROWS)
        if (newRows > oldRows) fireTableRowsInserted(oldRows, newRows - 1)
        if (newRows < oldRows) fireTableRowsDeleted(newRows, oldRows - 1)
        val first = (firstChanged - windowStart).coerceAtLeast(0)
        val last = minOf(oldRows, newRows) - 1
        if (first <= last) fireTableRowsUpdated(first, last)
    }

    override fun getColumnCount() = columnFields.size + if (showsLineNumbers) 1 else 0

    /** The schema field shown in [column], or null for the line number column. */
    fun fieldAt(column: Int): Field? = fieldIndex(column)?.let { records.schema.fields[it] }

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
    fun level(row: Int): Level? = records.level(recordAt(row))

    private fun values(record: Int): Array<String> {
        val slot = record and (CACHE_SIZE - 1)
        if (cachedRows[slot] == record) return cachedValues[slot]!!
        val values = records.rowValues(record, MAX_DISPLAY_BYTES)
        for (i in values.indices) values[i] = expandTabs(values[i])
        cachedRows[slot] = record
        cachedValues[slot] = values
        return values
    }

    /**
     * Call after records were appended; there were [oldRecordCount] before, and the records at [changed]
     * may have more text now. With a filter, call [rowsChangedFrom] once the filtered rows for the new
     * records are there.
     */
    fun recordsAppended(oldRecordCount: Int, changed: IntArray) {
        for (record in changed) {
            val slot = record and (CACHE_SIZE - 1)
            if (cachedRows[slot] == record) cachedRows[slot] = -1
        }
        if (filtered != null) return
        val oldRows = minOf(oldRecordCount, WINDOW_ROWS)
        if (rowCount > oldRows) fireTableRowsInserted(oldRows, rowCount - 1)
        for (record in changed) {
            val row = record - windowStart
            if (row in 0..<rowCount) fireTableRowsUpdated(row, row)
        }
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
