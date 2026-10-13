package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.session.ShownLog
import javax.swing.table.AbstractTableModel

/** Longest value prefix shown in the table; the rest is cut off. */
const val MAX_DISPLAY_BYTES = 4096

private const val TAB_WIDTH = 4
private const val CACHE_SIZE = 1024

/**
 * The rows of a [ShownLog]. Raw logs get a line number column; for parsed logs the message column is
 * moved last, so it takes the remaining width. Fields named in [hiddenColumns] are left out.
 */
class LogTableModel(val shown: ShownLog) : AbstractTableModel(), RowWindow {

    val showsLineNumbers = shown.isRaw

    /** Fields that can be shown as columns, in column order. */
    var columnChoices: List<Field> = choices()
        private set

    private fun choices() = shown.schema.fields.filter { !it.hidden }.sortedBy { it.role == Role.MESSAGE }

    /** Shows the fields the records' schema shows now, e.g. one that appended records are the first to have. */
    fun schemaChanged() {
        columnChoices = choices()
        columnFields = fieldsShown()
        fireTableStructureChanged()
    }

    var hiddenColumns: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            columnFields = fieldsShown()
            fireTableStructureChanged()
        }

    /** Field index of each column after the line number column. */
    private var columnFields: List<Int> = fieldsShown()

    private fun fieldsShown() = columnChoices.filter { it.name !in hiddenColumns }.map { shown.schema.fields.indexOf(it) }

    // Direct-mapped cache: repaints ask for the same few visible rows over and over.
    private val cachedRows = IntArray(CACHE_SIZE) { -1 }
    private val cachedValues = arrayOfNulls<Array<String>>(CACHE_SIZE)

    override val totalRowCount get() = shown.size

    override var windowStart = 0
        set(value) {
            field = value
            if (rowCount > 0) fireTableRowsUpdated(0, rowCount - 1)
        }

    override fun getRowCount() = minOf(totalRowCount, WINDOW_ROWS)

    /** Call after all shown rows changed, e.g. to a filter's result. */
    fun rowsReplaced() {
        windowStart = 0
        fireTableDataChanged()
    }

    /** The record shown at [row] of the current window. */
    fun recordAt(row: Int): Int = recordAtIndex(windowStart + row)

    /** The record at [index] of all shown rows. */
    fun recordAtIndex(index: Int): Int = shown.recordAt(index)

    /** Position of [record] among the shown rows, or of the first shown record after it; -1 if none. */
    fun indexOfRecord(record: Int): Int = shown.rowOf(record)

    /** Call after the shown rows changed from position [firstChanged] on; there were [oldSize] before. */
    fun rowsChangedFrom(firstChanged: Int, oldSize: Int) {
        val newSize = totalRowCount
        // Only the part of the window from the first change on is affected.
        val oldRows = (oldSize - windowStart).coerceIn(0, WINDOW_ROWS)
        val newRows = (newSize - windowStart).coerceIn(0, WINDOW_ROWS)
        if (newRows > oldRows) fireTableRowsInserted(oldRows, newRows - 1)
        if (newRows < oldRows) fireTableRowsDeleted(newRows, oldRows - 1)
        val first = (firstChanged - windowStart).coerceAtLeast(0)
        val last = minOf(oldRows, newRows) - 1
        if (first <= last) fireTableRowsUpdated(first, last)
    }

    /** Call after the records at [changed] got more text, e.g. a stack trace written after them. */
    fun recordsGrew(changed: IntArray) {
        for (record in changed) {
            val slot = record and (CACHE_SIZE - 1)
            if (cachedRows[slot] == record) cachedRows[slot] = -1
            val index = shown.rowOf(record)
            val row = index - windowStart
            if (index >= 0 && shown.recordAt(index) == record && row in 0..<rowCount) fireTableRowsUpdated(row, row)
        }
    }

    override fun getColumnCount() = columnFields.size + if (showsLineNumbers) 1 else 0

    /** The schema field shown in [column], or null for the line number column. */
    fun fieldAt(column: Int): Field? = fieldIndex(column)?.let { shown.schema.fields[it] }

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

    /** Canonical level of the record shown in [row], if the parser has levels. */
    fun level(row: Int): Level? = shown.level(recordAt(row))

    private fun values(record: Int): Array<String> {
        val slot = record and (CACHE_SIZE - 1)
        if (cachedRows[slot] == record) return cachedValues[slot]!!
        val values = shown.rowValues(record, MAX_DISPLAY_BYTES)
        for (i in values.indices) values[i] = expandTabs(values[i])
        cachedRows[slot] = record
        cachedValues[slot] = values
        return values
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
