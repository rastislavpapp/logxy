package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryParser
import io.github.rastislavpapp.logxy.core.query.QueryPrinter
import io.github.rastislavpapp.logxy.core.query.hasTimeRange
import io.github.rastislavpapp.logxy.core.query.withField
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Insets
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
import javax.swing.border.AbstractBorder

/**
 * The filter of one tab: the minimum level, the filter itself (visual or as text), Apply, the mode toggle,
 * saved filters and Clear. The text form (Advanced) is offered only if the [host] has an editor for it.
 *
 * [onApply] is called whenever the filter is to be applied anew; [minimumLevel] and [query] then tell what to apply.
 * Enter in any input applies. The visual filter offers the fields by the names their columns show ([names]), the
 * text form by their own names.
 */
class FilterBar(private val onApply: () -> Unit, names: ColumnNames, private val host: LogViewHost) :
    JPanel(MigLayout("insets 6 8 6 8, fillx, gapy 3, hidemode 3", "[grow, fill]0[]")) {

    private val levelButton = dropDownButton(
        "Show only records of this level or more severe",
        sizedFor = listOf(ALL) + LEVELS.map { "≥ $it" },
    ) { showLevels() }.apply { text = ALL }
    private var minimum: Level? = null
        set(value) {
            field = value
            levelButton.text = value?.let { "≥ $it" } ?: ALL
        }

    // Any change to the filter resumes a suspended one.
    private val applyFilter: () -> Unit = {
        pauseButton.isSelected = false
        onApply()
    }
    private val disabledBySuspend = ArrayList<java.awt.Component>()
    private val pauseButton = accentToggle(
        Icons.pause,
        "Suspend the filter: show all records, keeping the filter to resume it",
    ).apply {
        addActionListener { onApply() }
        addItemListener { updateInputsEnabled() }
    }
    private val editor = QueryEditor(applyFilter, names)
    private val queryText: QueryText? = host.queryText(applyFilter)

    /** The text form's editor; null if the host has none, and then [textMode] is never selected. */
    private val textArea = queryText?.component
    private val textMode = accentToggle(
        Icons.advanced,
        "Advanced: write the filter as an expression, e.g. level >= WARN AND message == \"*timeout*\" " +
            "(Ctrl+Enter applies)",
    ).apply {
        addActionListener { switchMode(isSelected) }
    }
    private val actions = JPanel(MigLayout("insets 0, gap 2, hidemode 3")).apply {
        isOpaque = false
        add(levelButton, "gapright 6")
        add(toolBarButton(Icons.run, "Apply the filter (Enter, or Ctrl+Enter in Advanced)") { applyFilter() })
        add(pauseButton)
        if (textArea != null) add(textMode)
        add(toolBarButton(DropDownIcon(Icons.star), "Saved filters") { showSavedMenu(it) })
        add(toolBarButton(Icons.closeSmall, "Remove all conditions (Esc)") { clear() })
    }

    /** Holds [actions] beside the text; in Basic they sit at the end of the editor's first line instead. */
    private val actionsColumn = JPanel(MigLayout("insets 0")).apply { isOpaque = false }
    private val modes = CardLayout()
    private val modePanel = JPanel(modes)
    private val error = JLabel().apply {
        foreground = look.errorText
        isVisible = false
    }
    private var progress: Double? = null

    init {
        border = Outline
        modePanel.isOpaque = false
        modePanel.add(editor, "basic")
        if (textArea != null) modePanel.add(JScrollPane(textArea), "text")
        add(modePanel, "growx")
        add(actionsColumn, "aligny top, gapleft 8, wrap")
        add(error, "span")
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "logxyApply")
        actionMap.put("logxyApply", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = applyFilter()
        })
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "logxyClear")
        actionMap.put("logxyClear", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = clear()
        })
        showMode(text = false)
        if (textArea != null && host.state[UiState.FILTER_MODE] == "advanced") {
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

    /** Gives the values of a template's variable to pick from; see [QueryEditor.variableValues]. */
    var variableValues by editor::variableValues

    /**
     * Sets the fields of the log being filtered, which the editors offer; the level choice shows only if one is a
     * level field.
     */
    fun setSchema(schema: Schema) {
        val hasLevel = schema.indexOf(Role.LEVEL) >= 0
        levelButton.isVisible = hasLevel
        val text = if (textMode.isSelected) runCatching { QueryParser.parse(typed) }.getOrNull() else null
        text?.let(editor::setQuery)
        val fields = schema.fields.filter { !it.hidden }
        editor.templates = schema.hasTemplates()
        editor.setFields(fields)
        queryText?.setFields(fields.map { it.name } + listOfNotNull(Query.TEMPLATE_FIELD.takeIf { editor.templates }))
        // The editor starts placeholder lines over on new fields; text without real rules follows it.
        if (text != null && editor.query().items.isEmpty()) {
            textArea?.text = QueryPrinter.print(editor.query(includeEmpty = true))
        }
        if (suspended) updateInputsEnabled()
    }

    /** Moves the conditions on the field [from] to [to], e.g. when the records are read anew; does not apply them. */
    fun renameField(from: String, to: String) {
        val query = runCatching { query() }.getOrNull() ?: return
        val renamed = query.withField(from, to)
        if (renamed == query) return
        editor.setQuery(renamed)
        if (textMode.isSelected) textArea?.text = QueryPrinter.print(renamed)
    }

    /** Offers the fields by their names again, e.g. after a rename; the conditions stay. */
    fun columnNamesChanged() {
        editor.setQuery(editor.query(includeEmpty = true))
        if (suspended) updateInputsEnabled()
    }

    /**
     * Everything but the suspend toggle is disabled while suspended. Resuming enables only what suspending
     * disabled, as some inputs are off for reasons of their own, e.g. the case option of a regex rule.
     */
    private fun updateInputsEnabled() {
        if (!pauseButton.isSelected) {
            disabledBySuspend.forEach { it.isEnabled = true }
            disabledBySuspend.clear()
            return
        }
        fun disable(component: java.awt.Component) {
            if (component === pauseButton) return
            if (component.isEnabled) {
                component.isEnabled = false
                disabledBySuspend += component
            }
            if (component is java.awt.Container) component.components.forEach(::disable)
        }
        disable(editor)
        disable(modePanel)
        disable(actions)
    }

    /** True while the filter is kept but not applied. */
    val suspended: Boolean get() = pauseButton.isSelected

    /** Whether there is a filter to suspend; always true while suspended, so it can be resumed. */
    val canSuspend: Boolean
        get() = suspended || minimumLevel != null || runCatching { query().items.isNotEmpty() }.getOrDefault(true)

    fun toggleSuspended() {
        pauseButton.isSelected = !pauseButton.isSelected
        onApply()
    }

    val minimumLevel: Level? get() = if (levelButton.isVisible) minimum else null

    /** The filter as edited; throws [QueryException] if the text has a syntax error. */
    fun query(): Query.Group = if (textMode.isSelected) QueryParser.parse(typed) else editor.query()

    /** The text form as typed; empty without one. */
    private val typed: String get() = textArea?.text.orEmpty()

    /** Called when [edit] changed the filter, e.g. from a cell's menu, so a hidden bar can show what changed. */
    var onRulesAdded: () -> Unit = {}

    /**
     * Changes the filter from outside the bar to what [change] makes of the current query, shown in the mode the
     * bar is in, and applies it. If the text form has a syntax error, shows it instead and changes nothing.
     */
    fun edit(change: (Query.Group) -> Query.Group) {
        val current = try {
            query()
        } catch (e: QueryException) {
            showError("Fix the filter first: ${e.message}")
            return
        }
        val changed = change(current)
        if (textMode.isSelected) textArea?.text = QueryPrinter.print(changed) else editor.setQuery(changed)
        applyFilter()
        onRulesAdded()
    }

    /** Whether the filter has a time range on [field]; false while the text form has a syntax error. */
    fun hasTimeRange(field: String): Boolean {
        val current = runCatching { query() }.getOrNull() ?: return false
        return current.hasTimeRange(field)
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
        g.color = look.accent
        g.fillRect(0, height - PROGRESS_HEIGHT, (width * fraction.coerceIn(0.0, 1.0)).toInt(), PROGRESS_HEIGHT)
    }

    /** Call once this bar is no longer shown, so nothing app-wide keeps it. */
    fun release() {
        queryText?.release()
    }

    fun showError(message: String?) {
        error.text = message
        error.isVisible = message != null
        revalidate()
    }

    /** Puts the keyboard focus where the filter is typed. */
    fun focusFilter() {
        if (textMode.isSelected) textArea?.requestFocusInWindow() else editor.focusFirstValue()
    }

    private fun switchMode(text: Boolean) {
        val area = textArea ?: return
        host.state[UiState.FILTER_MODE] = if (text) "advanced" else "basic"
        if (text) {
            area.text = QueryPrinter.print(editor.query(includeEmpty = true))
            // Put the cursor into the first empty value, ready for typing.
            val empty = area.text.indexOf("\"\"")
            area.caretPosition = if (empty >= 0) empty + 1 else area.text.length
            showMode(text = true)
            // Only a showing component can take the focus; the card becomes visible on the next layout.
            SwingUtilities.invokeLater { area.requestFocusInWindow() }
            showError(null)
        } else {
            try {
                editor.setQuery(QueryParser.parse(area.text))
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
        textArea?.text = ""
        applyFilter()
    }

    private fun showLevels() {
        fun choose(level: Level?) {
            minimum = level
            applyFilter()
        }
        val items = buildList {
            add(PopupItem(ALL, selected = minimum == null) { choose(null) })
            LEVELS.forEachIndexed { i, level ->
                add(PopupItem("≥ $level", selected = minimum == level, separatorBefore = i == 0) { choose(level) })
            }
            val dialogs = host.settingsDialogs
            if (dialogs != null) {
                add(PopupItem("Log Level Mapping", Icons.levels, separatorBefore = true) {
                    dialogs.editLevelMapping(SwingUtilities.getWindowAncestor(this@FilterBar))
                })
            }
        }
        showPopupList(levelButton, items)
    }

    private fun showSavedMenu(button: JButton) {
        val filters = host.settings.current.filters
        val items = buildList {
            if (filters.isEmpty()) add(PopupItem("No saved filters", enabled = false))
            filters.forEach { filter ->
                val deleteTool = PopupTool(Icons.clear, "Delete this filter") { delete(filter) }
                add(PopupItem(filter.name, tools = listOf(deleteTool)) { load(filter) })
            }
            add(PopupItem("Save Current Filter As", Icons.add, separatorBefore = true) { save() })
        }
        showPopupList(button, items)
    }

    /** The filter as edited, unnamed; Advanced text is kept as typed, even if it does not parse. */
    fun current(): SavedFilter =
        SavedFilter("", minimum, if (textMode.isSelected) typed else QueryPrinter.print(editor.query()))

    /** Shows [filter] and applies it; without the text form, a query that does not parse only shows its error. */
    fun load(filter: SavedFilter) {
        minimum = filter.minimumLevel
        textArea?.text = filter.query
        try {
            editor.setQuery(QueryParser.parse(filter.query))
        } catch (e: QueryException) {
            if (textArea == null) return showError("Cannot show the filter: ${e.message}")
            textMode.isSelected = true
            showMode(text = true)
        }
        applyFilter()
    }

    private fun save() {
        val text = try {
            QueryPrinter.print(query())
        } catch (e: QueryException) {
            showError(e.message)
            return
        }
        val name = JOptionPane.showInputDialog(this, "Name of the filter:", "Save Filter", JOptionPane.PLAIN_MESSAGE)
            ?.trim()
        if (name.isNullOrEmpty()) return
        val filters = host.settings.current.filters
        val exists = filters.any { it.name == name }
        if (exists && !confirm("Replace the saved filter “$name”?", "Save Filter")) return
        val filter = SavedFilter(name, minimumLevel, text)
        persist(if (exists) filters.map { if (it.name == name) filter else it } else filters + filter)
    }

    private fun delete(filter: SavedFilter) {
        if (!confirm("Delete the saved filter “${filter.name}”?", "Delete Filter")) return
        persist(host.settings.current.filters.filter { it.name != filter.name })
    }

    private fun confirm(message: String, title: String) =
        JOptionPane.showConfirmDialog(this, message, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION

    private fun persist(filters: List<SavedFilter>) {
        host.settings.updateOrShowError(this, host.settings.current.copy(filters = filters))
    }

    /** A line around the bar in the look's border colour, read when painted; inside it, a gap at the bottom only. */
    private object Outline : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val thickness = look.scale(1)
            g.color = look.border
            g.fillRect(x, y, width, thickness)
            g.fillRect(x, y + height - thickness, width, thickness)
            g.fillRect(x, y, thickness, height)
            g.fillRect(x + width - thickness, y, thickness, height)
        }

        override fun getBorderInsets(c: Component, insets: Insets): Insets {
            insets.set(0, 0, look.scale(1), 0)
            return insets
        }
    }

    private companion object {
        const val ALL = "All levels"
        const val PROGRESS_HEIGHT = 3
        val LEVELS = Level.entries.filter { it != Level.UNKNOWN }
    }
}
