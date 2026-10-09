package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryParser
import io.github.rastislavpapp.logxy.core.query.QueryPrinter
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Filter controls above the table: a minimum level, the filter itself (visual or as text), Apply,
 * Clear, and saved filters in a menu. Enter in any of its inputs applies. [onApply] is called to
 * (re)apply; [minimumLevel] and [query] give the state.
 */
class FilterBar(private val onApply: () -> Unit) : JPanel(MigLayout("insets 4 6 4 6, fillx, wrap 1, gapy 3, hidemode 3", "[grow, fill]")) {

    private val levels = JComboBox<Any>((listOf<Any>(ALL) + Level.entries.filter { it != Level.UNKNOWN }).toTypedArray()).apply {
        toolTipText = "Show only records of this level or more severe"
        addActionListener { onApply() }
        applyOnEnter(this, onApply)
    }
    private val levelLabel = JLabel("Level:")
    private val levelSuffix = JLabel("and higher")
    private val editor = QueryEditor(onApply)
    private val textArea = QueryTextArea(onApply)
    private val basicMode = accentToggle("Basic").apply { isSelected = true }
    private val textMode = accentToggle("Advanced")
    private val modes = CardLayout()
    private val modePanel = JPanel(modes)
    private val error = JLabel().apply {
        foreground = UIManager.getColor("Component.error.focusedBorderColor")
        isVisible = false
    }

    init {
        ButtonGroup().apply {
            add(basicMode)
            add(textMode)
        }
        basicMode.toolTipText = "Build the filter from conditions"
        textMode.toolTipText = "Write the filter as an expression, e.g. level >= WARN AND message == \"*timeout*\" (Ctrl+Enter applies)"
        basicMode.addActionListener { switchMode(text = false) }
        textMode.addActionListener { switchMode(text = true) }
        modePanel.isOpaque = false
        modePanel.add(editor, "basic")
        modePanel.add(JScrollPane(textArea), "text")

        add(JPanel(MigLayout("insets 0, hidemode 3", "[][][]16[]push[][]16[]")).apply {
            isOpaque = false
            add(levelLabel)
            add(levels)
            add(levelSuffix)
            add(basicMode, "split 2, gap 0")
            add(textMode, "gap 0")
            add(JButton("Apply").apply {
                toolTipText = "Apply the filter (Enter, or Ctrl+Enter in Advanced)"
                addActionListener { onApply() }
            })
            add(JButton("Clear").apply { addActionListener { clear() } })
            add(JButton("Saved filters ▾").apply { addActionListener { showSavedMenu(this) } })
        })
        add(modePanel)
        add(error)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "logxyApply")
        actionMap.put("logxyApply", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onApply()
        })
    }

    /** Fields of the log being filtered: offered in the editors; the level choice needs a level field. */
    fun setSchema(schema: Schema) {
        val hasLevel = schema.indexOf(Role.LEVEL) >= 0
        listOf(levelLabel, levels, levelSuffix).forEach { it.isVisible = hasLevel }
        editor.setFields(schema.fields)
        textArea.setFields(schema.fields.map { it.name })
    }

    val minimumLevel: Level? get() = if (levels.isVisible) levels.selectedItem as? Level else null

    /** The filter as edited; throws [QueryException] if the text has a syntax error. */
    fun query(): Query.Group = if (textMode.isSelected) QueryParser.parse(textArea.text) else editor.query()

    fun showError(message: String?) {
        error.text = message
        error.isVisible = message != null
        revalidate()
    }

    private fun switchMode(text: Boolean) {
        if (text) {
            textArea.text = QueryPrinter.print(editor.query(includeEmpty = true))
            // Put the cursor into the first empty value, ready for typing.
            val empty = textArea.text.indexOf("\"\"")
            textArea.caretPosition = if (empty >= 0) empty + 1 else textArea.text.length
            modes.show(modePanel, "text")
            // Only a showing component can take the focus; the card becomes visible on the next layout.
            SwingUtilities.invokeLater { textArea.requestFocusInWindow() }
            showError(null)
        } else {
            try {
                editor.setQuery(QueryParser.parse(textArea.text))
                modes.show(modePanel, "basic")
                showError(null)
            } catch (e: QueryException) {
                textMode.isSelected = true
                showError("Fix the filter before switching: ${e.message}")
            }
        }
        revalidate()
    }

    private fun clear() {
        levels.selectedItem = ALL
        editor.clear()
        textArea.text = ""
        onApply()
    }

    private fun showSavedMenu(button: JButton) {
        val filters = AppSettings.current.filters
        JPopupMenu().apply {
            if (filters.isEmpty()) add(JMenuItem("No saved filters").apply { isEnabled = false })
            filters.forEach { filter -> add(JMenuItem(filter.name).apply { addActionListener { load(filter) } }) }
            addSeparator()
            add(JMenuItem("Save current filter as…").apply { addActionListener { save() } })
            add(JMenu("Delete").apply {
                isEnabled = filters.isNotEmpty()
                filters.forEach { filter -> add(JMenuItem(filter.name).apply { addActionListener { delete(filter) } }) }
            })
        }.show(button, 0, button.height)
    }

    private fun load(filter: SavedFilter) {
        levels.selectedItem = filter.minimumLevel ?: ALL
        textArea.text = filter.query
        try {
            editor.setQuery(QueryParser.parse(filter.query))
        } catch (_: QueryException) {
            textMode.isSelected = true
            modes.show(modePanel, "text")
        }
        onApply()
    }

    private fun save() {
        val text = try {
            QueryPrinter.print(query())
        } catch (e: QueryException) {
            showError(e.message)
            return
        }
        val name = JOptionPane.showInputDialog(this, "Name of the filter:", "Save Filter", JOptionPane.PLAIN_MESSAGE)?.trim()
        if (name.isNullOrEmpty()) return
        val filters = AppSettings.current.filters
        if (filters.any { it.name == name } &&
            JOptionPane.showConfirmDialog(this, "Replace the saved filter “$name”?", "Save Filter", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION
        ) {
            return
        }
        val filter = SavedFilter(name, minimumLevel, text)
        persist(if (filters.any { it.name == name }) filters.map { if (it.name == name) filter else it } else filters + filter)
    }

    private fun delete(filter: SavedFilter) {
        if (JOptionPane.showConfirmDialog(this, "Delete the saved filter “${filter.name}”?", "Delete Filter", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        persist(AppSettings.current.filters.filter { it.name != filter.name })
    }

    private fun persist(filters: List<SavedFilter>) {
        try {
            AppSettings.update(AppSettings.current.copy(filters = filters))
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Cannot write ${AppSettings.location}:\n${e.message}", "Cannot Save", JOptionPane.ERROR_MESSAGE)
        }
    }

    private companion object {
        const val ALL = "All"
    }
}
