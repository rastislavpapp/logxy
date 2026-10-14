package io.github.rastislavpapp.logxy.app

import net.miginfocom.swing.MigLayout
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.table.DefaultTableModel

/**
 * A modal, resizable notice listing [rows] under [columns] in a sortable, scrollable table, for lists
 * that can be too long for a message dialog. Selected rows copy as tab-separated text with Ctrl+C.
 * [stateKey] is the [WindowState] key under which the user's chosen size is remembered.
 */
class TableNoticeDialog(
    owner: Window,
    title: String,
    icon: Icon,
    header: String,
    columns: List<String>,
    rows: List<List<String>>,
    stateKey: String,
) : JDialog(owner, title, ModalityType.APPLICATION_MODAL) {

    init {
        val model = object : DefaultTableModel(rows.map { it.toTypedArray() }.toTypedArray(), columns.toTypedArray()) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        val table = JTable(model).apply { autoCreateRowSorter = true }
        val close = JButton("Close").apply { addActionListener { dispose() } }
        contentPane = JPanel(MigLayout("fill, insets dialog, wrap 1", "[grow, fill]", "[][grow][]")).apply {
            add(JLabel(header, icon, JLabel.LEADING).apply { iconTextGap = 12 })
            add(JScrollPane(table), "grow")
            add(dialogButtons(close), "gaptop para")
        }
        rootPane.defaultButton = close
        val escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        rootPane.registerKeyboardAction({ dispose() }, escape, JComponent.WHEN_IN_FOCUSED_WINDOW)
        fitToScreen(SCREEN_FRACTION)
        WindowState.install(this, stateKey)
    }

    private companion object {
        const val SCREEN_FRACTION = 0.5
    }
}
