package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import net.miginfocom.swing.MigLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/** Edits which level texts in logs mean which [Level]; starts from the current table, built-in names included. */
class LevelsPage : PreferencesPage("Log Levels", Icons.levels) {

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
        layout = MigLayout("fill, insets 0", "[grow]", "[][grow][]")
        add(Note(hint), "growx, wmin 0, wrap")
        add(JScrollPane(table), "grow, wrap")
        add(JButton("Add").apply { addActionListener { addRow() } }, "split 3")
        add(JButton("Remove").apply { addActionListener { removeSelected() } })
        add(JButton("Reset to Defaults").apply { addActionListener { show(LevelMapping.DEFAULT) } })
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

    private fun named(): List<Row> {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        return rows.filter { it.name.isNotBlank() }
    }

    override fun problem(): String? {
        val duplicate = named().groupBy { LevelMapping.normalize(it.name) }.entries.firstOrNull { it.value.size > 1 }
        return duplicate?.let { "Log Levels: “${it.key}” is listed more than once." }
    }

    override fun applyTo(settings: Settings) =
        settings.copy(levels = LevelMapping(named().associate { it.name.trim() to it.level }))

    private inner class RowsModel : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Text in the log" else "Level"
        override fun getColumnClass(column: Int) = if (column == 0) String::class.java else Level::class.java
        override fun isCellEditable(row: Int, column: Int) = true
        override fun getValueAt(row: Int, column: Int): Any = if (column == 0) rows[row].name else rows[row].level

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column == 0) {
                rows[row].name = (value as String?).orEmpty()
            } else {
                rows[row].level = value as? Level ?: return
            }
            fireTableCellUpdated(row, column)
        }
    }
}
