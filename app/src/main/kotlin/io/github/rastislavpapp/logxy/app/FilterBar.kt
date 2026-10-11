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
import java.awt.Graphics
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * The filter of one tab: the minimum level, the filter itself (visual or as text), Apply, the mode toggle,
 * saved filters and Clear.
 * Enter in any input applies. [onApply] is called to (re)apply; [minimumLevel] and [query] give the state.
 */
class FilterBar(private val onApply: () -> Unit) : JPanel(MigLayout("insets 6 8 6 8, fillx, gapy 3, hidemode 3", "[grow, fill]0[]")) {

    private val levelButton = dropDownButton(
        "Show only records of this level or more severe",
        sizedFor = listOf(ALL) + LEVELS.map { "≥ $it" },
    ) { showLevels() }.apply { text = ALL }
    private var minimum: Level? = null
        set(value) {
            field = value
            levelButton.text = value?.let { "≥ $it" } ?: ALL
        }
    private val editor = QueryEditor(onApply)
    private val textArea = QueryTextArea(onApply)
    private val textMode = accentToggle(
        Icons.advanced,
        "Advanced: write the filter as an expression, e.g. level >= WARN AND message == \"*timeout*\" (Ctrl+Enter applies)",
    ).apply {
        addActionListener { switchMode(isSelected) }
    }
    private val actions = JPanel(MigLayout("insets 0, gap 2, hidemode 3")).apply {
        isOpaque = false
        add(levelButton, "gapright 6")
        add(toolBarButton(Icons.run, "Apply the filter (Enter, or Ctrl+Enter in Advanced)") { onApply() })
        add(textMode)
        add(toolBarButton(DropDownIcon(Icons.star), "Saved filters") { showSavedMenu(it) })
        add(toolBarButton(Icons.closeSmall, "Remove all conditions (Esc)") { clear() })
    }

    /** Holds [actions] beside the text; in Basic they sit at the end of the editor's first line instead. */
    private val actionsColumn = JPanel(MigLayout("insets 0")).apply { isOpaque = false }
    private val modes = CardLayout()
    private val modePanel = JPanel(modes)
    private val error = JLabel().apply {
        foreground = UIManager.getColor("Component.error.focusedBorderColor")
        isVisible = false
    }
    private var progress: Double? = null

    init {
        putClientProperty("FlatLaf.style", "border: 0,0,1,0,\$Component.borderColor")
        modePanel.isOpaque = false
        modePanel.add(editor, "basic")
        modePanel.add(JScrollPane(textArea), "text")
        add(modePanel, "growx")
        add(actionsColumn, "aligny top, gapleft 8, wrap")
        add(error, "span")
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "logxyApply")
        actionMap.put("logxyApply", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onApply()
        })
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "logxyClear")
        actionMap.put("logxyClear", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = clear()
        })
        showMode(text = false)
        if (AppState[AppState.FILTER_MODE] == "advanced") {
            textMode.isSelected = true
            showMode(text = true)
        }
    }

    private fun showMode(text: Boolean) {
        modes.show(modePanel, if (text) "text" else "basic")
        if (text) {
            editor.trailing = null
            actionsColumn.add(actions)
        } else {
            actionsColumn.remove(actions)
            editor.trailing = actions
        }
        // Hidden when empty, so its gap does not widen the space right of the editor.
        actionsColumn.isVisible = text
    }

    /** Fields of the log being filtered: offered in the editors; the level choice needs a level field. */
    fun setSchema(schema: Schema) {
        val hasLevel = schema.indexOf(Role.LEVEL) >= 0
        levelButton.isVisible = hasLevel
        val text = if (textMode.isSelected) runCatching { QueryParser.parse(textArea.text) }.getOrNull() else null
        text?.let(editor::setQuery)
        val fields = schema.fields.filter { !it.hidden }
        editor.setFields(fields)
        textArea.setFields(fields.map { it.name })
        // The editor starts placeholder rows over on new fields; text without real conditions follows it.
        if (text != null && editor.query().items.isEmpty()) textArea.text = QueryPrinter.print(editor.query(includeEmpty = true))
    }

    val minimumLevel: Level? get() = if (levelButton.isVisible) minimum else null

    /** The filter as edited; throws [QueryException] if the text has a syntax error. */
    fun query(): Query.Group = if (textMode.isSelected) QueryParser.parse(textArea.text) else editor.query()

    /** Adds [rule] to the filter, joined with AND, and applies it. */
    fun addRule(rule: Query.Rule) {
        val current = try {
            query()
        } catch (e: QueryException) {
            showError("Fix the filter first: ${e.message}")
            return
        }
        val combined = when {
            current.items.isEmpty() -> Query.Group(all = true, items = listOf(rule))
            current.all && !current.negated -> current.copy(items = current.items + rule)
            else -> Query.Group(all = true, items = listOf(current, rule))
        }
        if (textMode.isSelected) textArea.text = QueryPrinter.print(combined) else editor.setQuery(combined)
        onApply()
    }

    /** Shows how far a slow filter got, from 0 to 1; null hides it. */
    fun showProgress(fraction: Double?) {
        if (fraction == progress) return
        progress = fraction
        repaint(0, height - PROGRESS_HEIGHT, width, PROGRESS_HEIGHT)
    }

    // Painted over the bottom inset and border rather than laid out, so showing it moves nothing.
    override fun paint(g: Graphics) {
        super.paint(g)
        val fraction = progress ?: return
        g.color = UIManager.getColor("Component.accentColor")
        g.fillRect(0, height - PROGRESS_HEIGHT, (width * fraction.coerceIn(0.0, 1.0)).toInt(), PROGRESS_HEIGHT)
    }

    fun showError(message: String?) {
        error.text = message
        error.isVisible = message != null
        revalidate()
    }

    /** Puts the keyboard focus where the filter is typed. */
    fun focusFilter() {
        if (textMode.isSelected) textArea.requestFocusInWindow() else editor.focusFirstValue()
    }

    private fun switchMode(text: Boolean) {
        AppState[AppState.FILTER_MODE] = if (text) "advanced" else "basic"
        if (text) {
            textArea.text = QueryPrinter.print(editor.query(includeEmpty = true))
            // Put the cursor into the first empty value, ready for typing.
            val empty = textArea.text.indexOf("\"\"")
            textArea.caretPosition = if (empty >= 0) empty + 1 else textArea.text.length
            showMode(text = true)
            // Only a showing component can take the focus; the card becomes visible on the next layout.
            SwingUtilities.invokeLater { textArea.requestFocusInWindow() }
            showError(null)
        } else {
            try {
                editor.setQuery(QueryParser.parse(textArea.text))
                showMode(text = false)
                showError(null)
            } catch (e: QueryException) {
                textMode.isSelected = true
                showError("Fix the filter before switching: ${e.message}")
            }
        }
        revalidate()
    }

    fun clear() {
        minimum = null
        editor.clear()
        textArea.text = ""
        onApply()
    }

    private fun showLevels() {
        fun choose(level: Level?) {
            minimum = level
            onApply()
        }
        val items = buildList {
            add(PopupItem(ALL, selected = minimum == null) { choose(null) })
            LEVELS.forEachIndexed { i, level ->
                add(PopupItem("≥ $level", selected = minimum == level, separatorBefore = i == 0) { choose(level) })
            }
            add(PopupItem("Log Level Mapping", Icons.levels, separatorBefore = true) {
                PreferencesDialog(SwingUtilities.getWindowAncestor(this@FilterBar), PreferencesDialog.Section.LEVELS).isVisible = true
            })
        }
        showPopupList(levelButton, items)
    }

    private fun showSavedMenu(button: JButton) {
        val filters = AppSettings.current.filters
        val items = buildList {
            if (filters.isEmpty()) add(PopupItem("No saved filters", enabled = false))
            filters.forEach { filter ->
                add(PopupItem(filter.name, tools = listOf(PopupTool(Icons.clear, "Delete this filter") { delete(filter) })) { load(filter) })
            }
            add(PopupItem("Save Current Filter As", Icons.add, separatorBefore = true) { save() })
        }
        showPopupList(button, items)
    }

    /** The filter as edited, unnamed; Advanced text is kept as typed, even if it does not parse. */
    fun current(): SavedFilter =
        SavedFilter("", minimum, if (textMode.isSelected) textArea.text else QueryPrinter.print(editor.query()))

    /** Shows [filter] and applies it. */
    fun load(filter: SavedFilter) {
        minimum = filter.minimumLevel
        textArea.text = filter.query
        try {
            editor.setQuery(QueryParser.parse(filter.query))
        } catch (_: QueryException) {
            textMode.isSelected = true
            showMode(text = true)
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
        const val ALL = "All levels"
        const val PROGRESS_HEIGHT = 3
        val LEVELS = Level.entries.filter { it != Level.UNKNOWN }
    }
}
