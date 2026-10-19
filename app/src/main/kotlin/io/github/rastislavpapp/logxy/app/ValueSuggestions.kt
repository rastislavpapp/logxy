package io.github.rastislavpapp.logxy.app

import java.awt.Component
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Values listed at most, so the list stays short. */
private const val MAX_SUGGESTIONS = 10

/**
 * Values to pick from below [field] as one types, like an editable combo box: the most frequent while [field] is
 * empty, else those that contain its text. [load] gives the values with their counts, most frequent first, or null
 * if there are none to offer; it may answer later, on the UI thread. A picked value goes into [field] as [toText]
 * writes it. Up and Down move through the list, Enter picks, Esc closes it.
 */
class ValueSuggestions(
    private val field: JTextField,
    private val load: (onValues: (List<Pair<String, Int>>?) -> Unit) -> Unit,
    private val toText: (String) -> String = { it },
) {
    private val model = DefaultListModel<Pair<String, Int>>()
    private val list = JList(model).apply {
        isFocusable = false
        cellRenderer = ValueRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                val index = locationToIndex(e.point)
                if (index >= 0) pick(model.getElementAt(index).first)
            }
        })
    }
    private val popup = JPopupMenu().apply {
        isFocusable = false
        add(JScrollPane(list).apply { border = null })
    }
    private var values: List<Pair<String, Int>>? = null
    private var picking = false

    /** Whether values are offered; when false the list stays closed. */
    var enabled = false
        set(value) {
            field = value
            if (!value) popup.isVisible = false
        }

    init {
        field.addFocusListener(object : FocusAdapter() {
            // Read again each time, as the records may have changed since.
            override fun focusGained(e: FocusEvent) = reload()
            override fun focusLost(e: FocusEvent) {
                popup.isVisible = false
            }
        })
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = typed()
            override fun removeUpdate(e: DocumentEvent) = typed()
            override fun changedUpdate(e: DocumentEvent) = typed()
        })
        // Before the field's own keys, and the filter bar's Enter and Esc, while the list is open.
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!popup.isVisible) {
                    if (e.keyCode == KeyEvent.VK_DOWN && enabled) {
                        reload()
                        e.consume()
                    }
                    return
                }
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> list.selectedIndex = minOf(list.selectedIndex + 1, model.size() - 1)
                    KeyEvent.VK_UP -> list.selectedIndex = maxOf(list.selectedIndex - 1, -1)
                    KeyEvent.VK_ENTER -> if (list.selectedIndex >= 0) pick(list.selectedValue.first) else return
                    KeyEvent.VK_ESCAPE -> popup.isVisible = false
                    else -> return
                }
                list.ensureIndexIsVisible(maxOf(0, list.selectedIndex))
                e.consume()
            }
        })
    }

    private fun typed() {
        if (field.hasFocus() && !picking) SwingUtilities.invokeLater(::update)
    }

    private fun reload() {
        if (!enabled) return
        load { loaded ->
            values = loaded
            update()
        }
    }

    private fun update() {
        val all = values
        if (!enabled || all == null || !field.isShowing || !field.hasFocus()) {
            popup.isVisible = false
            return
        }
        val text = field.text
        val shown = (if (text.isEmpty()) all else all.filter { toText(it.first).contains(text, ignoreCase = true) })
            .take(MAX_SUGGESTIONS)
        if (shown.isEmpty() || shown.singleOrNull()?.let { toText(it.first) == text } == true) {
            popup.isVisible = false
            return
        }
        model.clear()
        shown.forEach(model::addElement)
        list.visibleRowCount = shown.size
        list.clearSelection()
        popup.isVisible = false
        popup.pack()
        popup.show(field, 0, field.height)
    }

    private fun pick(value: String) {
        popup.isVisible = false
        // Not typing: the list stays closed until the user types or presses Down.
        picking = true
        try {
            field.text = toText(value)
        } finally {
            picking = false
        }
        field.requestFocusInWindow()
    }

    /** A value, with its count dimmed after it. */
    private class ValueRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            @Suppress("UNCHECKED_CAST")
                val (text, count) = value as Pair<String, Int>
            val dim = UIManager.getColor("Label.disabledForeground")
            val hex = "#%02x%02x%02x".format(dim.red, dim.green, dim.blue)
            val countText = if (isSelected) "%,d".format(count) else "<font color='$hex'>%,d</font>".format(count)
            val html = "<html>${escapeHtml(text)}&nbsp;&nbsp;$countText</html>"
            return super.getListCellRendererComponent(list, html, index, isSelected, cellHasFocus)
        }
    }
}
