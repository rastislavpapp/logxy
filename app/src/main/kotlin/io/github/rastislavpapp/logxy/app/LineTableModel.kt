package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.index.LineIndex
import javax.swing.table.AbstractTableModel

/** Longest line prefix shown in the table; the rest is cut off. */
const val MAX_DISPLAY_BYTES = 4096

private const val TAB_WIDTH = 4
private const val CACHE_SIZE = 1024

class LineTableModel(private val index: LineIndex) : AbstractTableModel(), RowWindow {

    // Direct-mapped cache: repaints ask for the same few visible rows over and over.
    private val cachedRows = IntArray(CACHE_SIZE) { -1 }
    private val cachedTexts = arrayOfNulls<String>(CACHE_SIZE)

    override val totalRowCount get() = index.lineCount

    override var windowStart = 0
        set(value) {
            field = value
            if (rowCount > 0) fireTableRowsUpdated(0, rowCount - 1)
        }

    override fun getRowCount() = minOf(index.lineCount, WINDOW_ROWS)

    /** Call after lines were appended to the index, which had [oldLineCount] lines before. */
    fun linesAppended(oldLineCount: Int) {
        // The previous last line may have been incomplete and now has more text.
        val lastLine = oldLineCount - 1
        if (lastLine >= 0) {
            val slot = lastLine and (CACHE_SIZE - 1)
            if (cachedRows[slot] == lastLine) cachedRows[slot] = -1
        }
        val oldRows = minOf(oldLineCount, WINDOW_ROWS)
        if (rowCount > oldRows) fireTableRowsInserted(oldRows, rowCount - 1)
        val lastRow = lastLine - windowStart
        if (lastRow in 0..<rowCount) fireTableRowsUpdated(lastRow, lastRow)
    }

    override fun getColumnCount() = 2

    override fun getColumnName(column: Int) = if (column == 0) "#" else "Line"

    override fun getColumnClass(column: Int) = if (column == 0) Int::class.javaObjectType else String::class.java

    override fun getValueAt(row: Int, column: Int): Any {
        val line = windowStart + row
        return if (column == 0) line + 1 else lineText(line)
    }

    private fun lineText(row: Int): String {
        val slot = row and (CACHE_SIZE - 1)
        if (cachedRows[slot] == row) return cachedTexts[slot]!!
        val text = expandTabs(index.lineText(row, MAX_DISPLAY_BYTES))
        cachedRows[slot] = row
        cachedTexts[slot] = text
        return text
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
