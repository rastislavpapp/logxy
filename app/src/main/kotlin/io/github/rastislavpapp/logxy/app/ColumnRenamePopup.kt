package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Field
import net.miginfocom.swing.MigLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Asks under [header], at [x], for the name [field] shows among [fields]; [onRename] gets it, or the field's own
 * name for an empty one. A name another column has or shows is refused.
 */
fun showColumnRename(
    header: JComponent,
    x: Int,
    field: Field,
    fields: List<Field>,
    names: ColumnNames,
    onRename: (String) -> Unit,
) {
    val popup = JPopupMenu()
    val textField = JTextField(names.of(field), 20)
    val problem = JLabel(" ").apply { foreground = UIManager.getColor("Component.error.focusedBorderColor") }
    val ok = JButton("OK")

    fun name() = textField.text.trim().ifEmpty { field.name }

    fun check(): Boolean {
        val clash = names.clash(field, name(), fields)
        problem.text = if (clash == null) " " else "“${names.of(clash)}” already names a column."
        textField.putClientProperty("JComponent.outline", if (clash == null) null else "error")
        ok.isEnabled = clash == null
        return clash == null
    }

    fun apply() {
        if (!check()) return
        popup.isVisible = false
        onRename(name())
    }

    textField.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) {
            check()
        }
        override fun removeUpdate(e: DocumentEvent) {
            check()
        }
        override fun changedUpdate(e: DocumentEvent) {
            check()
        }
    })
    textField.addActionListener { apply() }
    ok.addActionListener { apply() }
    popup.add(JPanel(MigLayout("insets 6, wrap 1", "[grow, fill]")).apply {
        isOpaque = false
        add(JLabel("Name of “${field.name}” (empty for its own):"))
        add(textField)
        add(problem)
        add(ok, "split 2, align right, growx 0")
        add(JButton("Cancel").apply { addActionListener { popup.isVisible = false } }, "growx 0")
    })
    textField.selectAll()
    popup.show(header, x, header.height)
    textField.requestFocusInWindow()
}
