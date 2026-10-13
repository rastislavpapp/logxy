package io.github.rastislavpapp.logxy.app

import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.TransferHandler

/** Copies the selected rows of a log table as their records are written in the file, so pasted text reads like the log. */
class RecordCopyHandler(private val model: LogTableModel) : TransferHandler() {

    override fun getSourceActions(c: JComponent) = COPY

    override fun createTransferable(c: JComponent): Transferable? {
        val rows = (c as JTable).selectedRows.takeIf { it.isNotEmpty() } ?: return null
        val text = StringBuilder()
        for (row in rows) {
            text.append(model.shown.sourceText(model.recordAt(row)))
            // The file's last line may have no line end; the next record must still start on a line of its own.
            if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
        }
        return StringSelection(text.toString())
    }
}
