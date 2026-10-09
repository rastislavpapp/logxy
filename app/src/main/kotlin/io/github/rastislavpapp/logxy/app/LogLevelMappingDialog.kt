package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import net.miginfocom.swing.MigLayout
import java.awt.Window
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel

/** Edits which level texts in logs mean which [Level]; starts from the current table, built-in names included. */
class LogLevelMappingDialog(owner: Window?) : JDialog(owner, "Log Level Mapping", ModalityType.APPLICATION_MODAL) {

    private class Row(var name: String, var level: Level)

    private val rows = ArrayList<Row>()
    private val model = RowsModel()
    private val table = JTable(model)

    init {
        show(AppSettings.current.levels)
        table.columnModel.getColumn(1).cellEditor = DefaultCellEditor(JComboBox(Level.entries.toTypedArray()))
        table.rowHeight = maxOf(table.rowHeight, JComboBox<Level>().preferredSize.height)
        table.autoCreateRowSorter = true

        val hint = "Texts are matched ignoring case. Texts not listed here are shown as UNKNOWN."
        contentPane = JPanel(MigLayout("fill, insets dialog", "[grow]", "[][grow][]")).apply {
            add(JLabel(hint).apply { foreground = UIManager.getColor("Label.disabledForeground") }, "wrap")
            add(JScrollPane(table), "grow, wrap")
            add(JButton("Add").apply { addActionListener { addRow() } }, "split 6")
            add(JButton("Remove").apply { addActionListener { removeSelected() } })
            add(JButton("Reset to Defaults").apply { addActionListener { show(LevelMapping.DEFAULT) } })
            add(JLabel(), "pushx, growx")
            add(JButton("Save").apply {
                addActionListener { if (save()) dispose() }
                this@LogLevelMappingDialog.rootPane.defaultButton = this
            })
            add(JButton("Cancel").apply { addActionListener { dispose() } })
        }
        fitToScreen(0.4)
        WindowState.install(this, "log-level-mapping")
    }

    private fun show(mapping: LevelMapping) {
        if (table.isEditing) table.cellEditor.cancelCellEditing()
        rows.clear()
        mapping.names.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .forEach { rows += Row(it.key, it.value) }
        model.fireTableDataChanged()
    }

    private fun addRow() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        table.rowSorter?.sortKeys = null
        rows += Row("", Level.INFO)
        model.fireTableRowsInserted(rows.size - 1, rows.size - 1)
        val row = table.convertRowIndexToView(rows.size - 1)
        table.changeSelection(row, 0, false, false)
        table.editCellAt(row, 0)
        table.editorComponent?.requestFocusInWindow()
    }

    private fun removeSelected() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        table.selectedRows.map { table.convertRowIndexToModel(it) }.sortedDescending().forEach { rows.removeAt(it) }
        model.fireTableDataChanged()
    }

    private fun save(): Boolean {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val named = rows.filter { it.name.isNotBlank() }
        val duplicate = named.groupBy { LevelMapping.normalize(it.name) }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            JOptionPane.showMessageDialog(this, "“${duplicate.key}” is listed more than once.", "Cannot Save", JOptionPane.ERROR_MESSAGE)
            return false
        }
        return try {
            AppSettings.update(AppSettings.current.copy(levels = LevelMapping(named.associate { it.name.trim() to it.level })))
            true
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Cannot write ${AppSettings.location}:\n${e.message}", "Cannot Save", JOptionPane.ERROR_MESSAGE)
            false
        }
    }

    private inner class RowsModel : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Text in the log" else "Level"
        override fun getColumnClass(column: Int) = if (column == 0) String::class.java else Level::class.java
        override fun isCellEditable(row: Int, column: Int) = true
        override fun getValueAt(row: Int, column: Int): Any = if (column == 0) rows[row].name else rows[row].level

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column == 0) rows[row].name = (value as String?).orEmpty() else rows[row].level = value as? Level ?: return
            fireTableCellUpdated(row, column)
        }
    }
}
