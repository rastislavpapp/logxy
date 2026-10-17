package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Field
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import kotlin.collections.set

class ColumnRenameDialog(private val columnAliases: MutableMap<String, String>) {

    /** Opens a text field popup to rename [field]; */
    fun open(header: JComponent, field: Field, storeFn: (String) -> Unit) {
        val currentName = columnAliases[field.name] ?: field.name
        val textField = JTextField(currentName, 15)
        textField.selectAll()
        val popup = JPopupMenu()
        val applyRename = {
            val newName = textField.text.trim()
            if (newName.isNotEmpty() && newName != field.name) {
                columnAliases[field.name] = newName
            } else {
                columnAliases.remove(field.name)
            }
            storeFn(newName)
            popup.setVisible(false)
        }
        val inputPanel = JPanel(BorderLayout(4, 4)).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(JLabel("New name:"), BorderLayout.NORTH)
            add(textField, BorderLayout.CENTER)
        }
        popup.add(inputPanel)
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(JButton("OK").apply {
                addActionListener { applyRename() }
            })
            add(Box.createHorizontalStrut(4))
            add(JButton("Cancel").apply {
                addActionListener { popup.setVisible(false) }
            })
        }
        popup.add(buttonPanel)
        textField.addActionListener { applyRename() }
        popup.show(header, 0, header.height)
        textField.requestFocusInWindow()
    }
//
//    /** Persists an alias rename back to the parser. */
//    fun persist(parser: Parser, originalName: String, newName: String) {
//        val parser = session.parser
//        val updated = when (parser) {
//            is Parser.Pattern -> parser.copy(aliases = parser.aliases.toMutableMap().apply {
//                if (newName != originalName) put(originalName, newName) else remove(originalName)
//            })
//            is Parser.Regex -> parser.copy(aliases = parser.aliases.toMutableMap().apply {
//                if (newName != originalName) put(originalName, newName) else remove(originalName)
//            })
//            is Parser.Json -> parser.copy(aliases = parser.aliases.toMutableMap().apply {
//                if (newName != originalName) put(originalName, newName) else remove(originalName)
//            })
//            is Parser.Logfmt -> parser.copy(aliases = parser.aliases.toMutableMap().apply {
//                if (newName != originalName) put(originalName, newName) else remove(originalName)
//            })
//            is Parser.AccessLog -> parser.copy(aliases = parser.aliases.toMutableMap().apply {
//                if (newName != originalName) put(originalName, newName) else remove(originalName)
//            })
//            Parser.Raw -> return
//        }
//        val settings = AppSettings.current.copy(
//            parsers = AppSettings.current.parsers.map { if (it.name == parser.name) updated else it },
//        )
//        AppSettings.updateOrShowError(parent, settings)
//    }

}