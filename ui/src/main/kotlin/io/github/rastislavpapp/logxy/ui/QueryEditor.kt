package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.query.EditorOp
import io.github.rastislavpapp.logxy.core.query.EditorRule
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.query.escapeWildcards
import io.github.rastislavpapp.logxy.core.template.Templates
import io.github.rastislavpapp.logxy.core.template.VariableValues
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.geom.Path2D
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/** An operator in a line's combo box, shown by its label; typing picks the item whose [toString] starts so. */
private data class OpChoice(val op: EditorOp) {
    override fun toString() = when (op) {
        EditorOp.CONTAINS -> "contains"
        EditorOp.NOT_CONTAINS -> "does not contain"
        EditorOp.STARTS_WITH -> "starts with"
        EditorOp.ENDS_WITH -> "ends with"
        EditorOp.IS -> "is"
        EditorOp.IS_NOT -> "is not"
        EditorOp.MATCHES -> "matches regex"
        EditorOp.NOT_MATCHES -> "does not match regex"
        EditorOp.LEVEL_AT_LEAST -> "at least"
        EditorOp.LEVEL_ABOVE -> "above"
        EditorOp.LEVEL_AT_MOST -> "at most"
        EditorOp.LEVEL_BELOW -> "below"
        EditorOp.LEVEL_IS -> "is level"
        EditorOp.LEVEL_IS_NOT -> "is not level"
        EditorOp.TIME_FROM -> "at or after"
        EditorOp.TIME_AFTER -> "after"
        EditorOp.TIME_TO -> "at or before"
        EditorOp.TIME_BEFORE -> "before"
        EditorOp.NUMBER_AT_LEAST -> "≥"
        EditorOp.NUMBER_ABOVE -> ">"
        EditorOp.NUMBER_AT_MOST -> "≤"
        EditorOp.NUMBER_BELOW -> "<"
    }
}

/** How a group combines its items; "none of" and "not all of" are the negated forms. */
private enum class Match(val label: String, val all: Boolean, val negated: Boolean) {
    ALL("AND", true, false),
    ANY("OR", false, false),
    NONE("NONE OF", false, true),
    NOT_ALL("NOT ALL OF", true, true),
    ;

    override fun toString() = label

    companion object {
        fun of(group: Query.Group) = entries.first { it.all == group.all && it.negated == group.negated }
    }
}

/** Lets Enter in a combo box with a closed popup run [action]; combo boxes otherwise ignore it. */
fun applyOnEnter(combo: JComboBox<*>, action: () -> Unit) {
    val original = combo.actionMap.get("enterPressed")
    combo.actionMap.put("enterPressed", object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) {
            if (combo.isPopupVisible) original?.actionPerformed(e) else action()
        }
    })
    combo.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enterPressed")
}

/**
 * Visual editor of a [Query]: lines of rules, optionally in nested groups. Lines negate through
 * their operator ("does not contain"), groups through "none" / "not all". Rules with an empty value
 * are ignored. Rules hold the fields' own names; the field choice shows their [columnNames].
 */
class QueryEditor(private val onApply: () -> Unit, private val columnNames: ColumnNames) :
    JPanel(MigLayout("insets 0, fillx", "[grow, fill]")) {

    private var fields: List<Field> = emptyList()

    /** Whether rules on the message template, [Query.TEMPLATE_FIELD], are offered; set before [setFields]. */
    var templates = false

    /**
     * Gets the variable values of a template variable, counted from 0, in the log, to pick from in a `{n}` rule.
     * Calls `onValues` back on the UI thread, perhaps later, with null if there are none.
     */
    var variableValues: (template: String, variable: Int, onValues: (VariableValues?) -> Unit) -> Unit =
        { _, _, onValues -> onValues(null) }

    /**
     * Gives what the time picker opens on, offered in the value of a rule comparing a timestamp with a time; null
     * while there is nothing to pick from.
     */
    var timePickerLog: () -> TimePickerLog? = { null }
    private var root = GroupView(Query.EMPTY, isRoot = true)

    init {
        isOpaque = false
    }

    /** Shown at the end of the top group's header. */
    var trailing: JComponent? = null
        set(value) {
            field?.let { it.parent?.remove(it) }
            field = value
            changed()
        }

    fun setFields(fields: List<Field>) {
        val query = query(includeEmpty = true)
        // Lines without values only stand in for rules: start them over on the new fields (Line, not Message, for
        // raw text).
        val blank = query(includeEmpty = false).items.isEmpty()
        this.fields = fields
        setQuery(if (blank) Query.EMPTY else query)
    }

    fun setQuery(query: Query.Group) {
        root = GroupView(query, isRoot = true)
        if (root.isEmpty()) root.addItem(group = false)
        removeAll()
        add(root)
        changed()
    }

    fun clear() = setQuery(Query.EMPTY)

    /** The edited query; rules without a value are left out. */
    fun query(includeEmpty: Boolean = false): Query.Group = root.query(includeEmpty) ?: Query.EMPTY

    private fun defaultField(): String =
        fields.firstOrNull { it.role == Role.MESSAGE }?.name ?: fields.firstOrNull()?.name ?: "Message"

    private fun defaultRule() = Query.Rule(defaultField(), QueryOp.EQUALS, "")

    private fun fieldType(name: String) = fields.firstOrNull { it.name.equals(name, ignoreCase = true) }?.type

    private fun isTemplate(name: String) =
        name.equals(Query.TEMPLATE_FIELD, ignoreCase = true) && fieldType(name) == null

    /** The number of `{n}`, a variable of the template its group belongs to; null if [name] is no such rule. */
    private fun variableNumber(name: String) =
        if (fieldType(name) == null) VARIABLE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() else null

    /** Puts the keyboard focus into the first condition's value. */
    fun focusFirstValue() {
        fun first(container: java.awt.Container): JTextField? = container.components.firstNotNullOfOrNull { child ->
            when {
                child is JTextField && child.isShowing -> child
                child is java.awt.Container -> first(child)
                else -> null
            }
        }
        first(root)?.requestFocusInWindow()
    }

    private fun changed() {
        root.updateRemovable()
        trailing?.let { trailing ->
            if (trailing.parent !== root.header) {
                trailing.parent?.remove(trailing)
                root.header.add(trailing, "cell 1 0")
            }
        }
        // The editor grows with its lines, so everything up to the window must lay out again.
        (topLevelAncestor as? JComponent ?: this).revalidate()
        topLevelAncestor?.validate()
        repaint()
    }

    /**
     * A group drawn like an HTML fieldset: a rounded frame whose top edge runs through the header,
     * so the AND/OR combo sits where a fieldset's legend would.
     */
    private inner class GroupView(group: Query.Group, private val isRoot: Boolean) :
        JPanel(MigLayout("insets 0 10 8 10, wrap 1, gapy 2, fillx", "[grow, fill]")) {

        private val match = JComboBox(Match.entries.toTypedArray()).apply {
            selectedItem = Match.of(group)
            addActionListener { changed() }
            applyOnEnter(this, onApply)
        }
        val header = JPanel(MigLayout("insets 0", "[]push[]")).apply {
            isOpaque = false
            add(match)
            if (!isRoot) {
                add(toolBarButton(Icons.closeSmall, "Remove this group", focusable = true) {
                    val counted = query(includeEmpty = false) != null
                    removeFromParent(this@GroupView)
                    if (counted) onApply()
                }, "cell 1 0")
            }
        }

        // Sits right after the last item, where new ones are added.
        private val footer = JPanel(MigLayout("insets 0", "[][]push")).apply {
            isOpaque = false
            // wmin pref: the buttons must never shrink into "Conditi…".
            val addCondition = if (isRoot) "Add a condition" else "Add a condition to this group"
            add(toolBarButton(Icons.add, addCondition, focusable = true) { addItem(group = false) }, "wmin pref")
            val addGroup = "Add a group of conditions, matching all, any or none of them"
            add(toolBarButton(null, addGroup, "( )", focusable = true) { addItem(group = true) }, "wmin pref")
        }
        private val items = JPanel(MigLayout("insets 0, wrap 1, gapy 2, fillx", "[grow, fill]"))

        init {
            isOpaque = false
            items.isOpaque = false
            add(header)
            add(items)
            add(footer)
            group.items.forEach { item ->
                when (item) {
                    is Query.Rule -> items.add(RuleView(item, this))
                    is Query.Group -> items.add(GroupView(item, isRoot = false))
                }
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = look.border
                // Strokes are centred on the path: without the half pixel, the left edge at x = 0 is half outside and
                // fades or vanishes when scaled.
                g2.translate(0.5, 0.5)
                val top = header.y + header.height / 2
                val right = width - 1
                val bottom = height - 1
                val arc = 8
                g2.draw(Path2D.Float().apply {
                    moveTo(arc.toFloat(), top.toFloat())
                    quadTo(0f, top.toFloat(), 0f, (top + arc).toFloat())
                    lineTo(0f, (bottom - arc).toFloat())
                    quadTo(0f, bottom.toFloat(), arc.toFloat(), bottom.toFloat())
                    lineTo((right - arc).toFloat(), bottom.toFloat())
                    quadTo(right.toFloat(), bottom.toFloat(), right.toFloat(), (bottom - arc).toFloat())
                    lineTo(right.toFloat(), (top + arc).toFloat())
                    quadTo(right.toFloat(), top.toFloat(), (right - arc).toFloat(), top.toFloat())
                })
                // The top edge, broken where the header's components sit.
                var x = arc
                header.components.sortedBy { it.x }.forEach { c ->
                    val gapStart = header.x + c.x - 4
                    if (gapStart > x) g2.drawLine(x, top, gapStart, top)
                    x = maxOf(x, header.x + c.x + c.width + 4)
                }
                if (x < right - arc) g2.drawLine(x, top, right - arc, top)
            } finally {
                g2.dispose()
            }
        }

        fun isEmpty() = items.componentCount == 0

        // The top level keeps at least one condition, so a lone condition there has no ×. A hidden × still takes its
        // space, so the lines stay aligned.
        fun updateRemovable() {
            val single = isRoot && items.componentCount == 1
            items.components.forEach { (it as? RuleView)?.remove?.isVisible = !single }
        }

        fun addItem(group: Boolean) {
            if (group) {
                items.add(GroupView(Query.Group(all = false, items = listOf(defaultRule())), isRoot = false))
            } else {
                val rule = RuleView(defaultRule(), this)
                items.add(rule)
                rule.focusValue()
            }
            changed()
        }

        /** Replaces [rule] with a new OR group containing it, ready for alternatives. */
        fun wrapInGroup(rule: RuleView) {
            val index = items.components.indexOf(rule)
            val query = rule.rule(includeEmpty = true) ?: return
            items.remove(rule)
            items.add(GroupView(Query.Group(all = false, items = listOf(query)), isRoot = false), index)
            changed()
        }

        fun removeItem(view: JComponent) {
            items.remove(view)
            if (isEmpty()) {
                // An empty group has no use: remove it too (which may empty its parent). The top level keeps one line.
                if (!isRoot) return removeFromParent(this)
                items.add(RuleView(defaultRule(), this))
            }
            changed()
        }

        private fun removeFromParent(view: JComponent) {
            var parent = view.parent
            while (parent != null && parent !is GroupView) parent = parent.parent
            if (parent is GroupView) parent.removeItem(view)
        }

        /**
         * The template, as stored with `{}`, that the `{n}` rules in this group belong to, found as the query
         * compiler finds it. If this group matches all of its items and has "Template is" lines, that is the template
         * of its one such line, or null if it has several. Otherwise it is the template of the group around it, or
         * null at the top.
         */
        fun template(): String? {
            val selected = match.selectedItem as Match
            if (selected.all) {
                val own = items.components.mapNotNull { (it as? RuleView)?.templateIs() }
                if (own.isNotEmpty()) return own.singleOrNull()?.let(Templates::unnumbered)
            }
            var parent = parent
            while (parent != null && parent !is GroupView) parent = parent.parent
            return (parent as GroupView?)?.template()
        }

        fun query(includeEmpty: Boolean): Query.Group? {
            val parts = items.components.mapNotNull { child ->
                when (child) {
                    is RuleView -> child.rule(includeEmpty)
                    is GroupView -> child.query(includeEmpty)
                    else -> null
                }
            }
            if (parts.isEmpty() && !isRoot && !includeEmpty) return null
            val selected = match.selectedItem as Match
            return Query.Group(all = selected.all, items = parts, negated = selected.negated)
        }
    }

    private inner class RuleView(rule: Query.Rule, private val group: GroupView) :
        JPanel(MigLayout("insets 0", "[][][grow, fill][][]")) {

        private val field = JComboBox<String>()
        private val op = JComboBox<OpChoice>()
        private val case = fieldToggle("inline/matchCase", "Match exact case")
        private val text = JTextField(12)

        // Shown instead of the case toggle while the rule compares a timestamp with a time.
        private val picker = timePickerButton(text) { timePickerLog() }

        init {
            text.apply {
                addActionListener { onApply() }
                toolTipText = "* matches any text, ? one character"
                withToggles(case, picker)
                limitPaste()
            }
        }
        private val level = JComboBox(Level.entries.filter { it != Level.UNKNOWN }.toTypedArray())
        private var valueSlot: JComponent = text

        // A picked value is matched as it is, not as a wildcard pattern.
        private val suggestions = ValueSuggestions(text, ::loadValues) { escapeWildcards(it) }
        val remove = toolBarButton(Icons.closeSmall, "Remove this condition", focusable = true) {
            // Only a condition with a value filtered anything.
            val counted = rule(includeEmpty = false) != null
            group.removeItem(this)
            if (counted) onApply()
        }

        init {
            isOpaque = false
            val names = fields.map { it.name } + listOfNotNull(Query.TEMPLATE_FIELD.takeIf { templates })
            val known = names.firstOrNull { it.equals(rule.field, ignoreCase = true) }
            names.forEach(field::addItem)
            if (known == null) field.addItem(rule.field)
            field.selectedItem = known ?: rule.field
            field.addActionListener { fieldChanged() }
            // The template the lines belong to may have changed since: its variables are offered as the list opens.
            field.addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = offerVariables()
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
                override fun popupMenuCanceled(e: PopupMenuEvent) {}
            })
            field.renderer = FormattingListCellRenderer { value ->
                val name = value as? String
                if (name == null) null else columnNames.of(name)
            }
            op.renderer = FormattingListCellRenderer { it?.toString() }
            op.addActionListener { opChanged() }
            listOf(field, op, level).forEach { applyOnEnter(it, onApply) }
            add(field)
            add(op)
            add(text, "wmin 60")
            add(toolBarButton(null, "Create a group from this condition", "( )", focusable = true) {
                group.wrapInGroup(this)
            })
            add(remove)
            load(rule)
        }

        fun focusValue() {
            text.requestFocusInWindow()
        }

        private fun fieldType() = fieldType(field.selectedItem as String)

        private fun isTemplate() = isTemplate(field.selectedItem as String)

        private fun variableNumber() = variableNumber(field.selectedItem as String)

        private fun selectedOp() = (op.selectedItem as OpChoice?)?.op

        /** The template this line names, if it is a "Template is" line with one. */
        fun templateIs(): String? =
            text.text.takeIf { isTemplate() && selectedOp() == EditorOp.IS && it.isNotEmpty() }

        /** Puts `{1}` to `{n}` in the field list, for the variables of the template the group belongs to. */
        private fun offerVariables() {
            val count = group.template()?.let(Templates::variableCount) ?: 0
            val selected = field.selectedItem as String
            val listeners = field.actionListeners
            listeners.forEach(field::removeActionListener)
            for (i in field.itemCount - 1 downTo 0) {
                val name = field.getItemAt(i)
                if (variableNumber(name) != null && name != selected) field.removeItemAt(i)
            }
            for (n in 1..count) {
                val name = "{$n}"
                if (name != selected) field.addItem(name)
            }
            field.selectedItem = selected
            listeners.forEach(field::addActionListener)
        }

        /**
         * Loads the variable values of this line's template variable, most frequent first, if it is a `{n}` line whose
         * group has a template; otherwise [onValues] gets null.
         */
        private fun loadValues(onValues: (List<Pair<String, Int>>?) -> Unit) {
            val number = variableNumber()
            val template = group.template()
            if (number == null || number < 1 || template == null) return onValues(null)
            variableValues(template, number - 1) { values ->
                onValues(values?.let { v -> v.values.indices.map { v.values[it] to v.count(it) } })
            }
        }

        // Templates match exactly.
        private fun ops(): List<EditorOp> = if (isTemplate()) {
            listOf(EditorOp.IS, EditorOp.IS_NOT)
        } else {
            when (fieldType()) {
                ColumnType.LEVEL -> EditorOp.LEVEL + EditorOp.TEXT
                ColumnType.TIMESTAMP -> EditorOp.TIME + EditorOp.TEXT
                else -> EditorOp.TEXT + EditorOp.NUMBER
            }
        }

        private fun fillOps(selected: EditorOp) {
            val listeners = op.actionListeners
            listeners.forEach(op::removeActionListener)
            op.removeAllItems()
            ops().forEach { op.addItem(OpChoice(it)) }
            op.selectedItem = OpChoice(if (selected in ops()) selected else ops().first())
            listeners.forEach(op::addActionListener)
            opChanged()
        }

        // A variable's value is mostly picked whole.
        private fun fieldChanged() {
            val selected = selectedOp() ?: EditorOp.CONTAINS
            fillOps(if (variableNumber() != null && selected == EditorOp.CONTAINS) EditorOp.IS else selected)
        }

        private fun opChanged() {
            val selected = selectedOp() ?: return
            val wanted: JComponent = if (selected.usesLevel) level else text
            if (wanted !== valueSlot) {
                val index = components.indexOf(valueSlot)
                remove(valueSlot)
                add(wanted, "growx, wmin 60", index)
                valueSlot = wanted
                changed()
            }
            case.isEnabled = selected.usesCase && !isTemplate()
            val picksTime = selected in EditorOp.TIME && fieldType() == ColumnType.TIMESTAMP
            case.isVisible = !picksTime
            picker.isVisible = picksTime
            suggestions.enabled = variableNumber() != null && !selected.usesLevel
            text.toolTipText = when {
                isTemplate() -> "The message with its variable parts (numbers, ids, …) written as {1}, {2}, …"
                variableNumber() != null && selected.usesCase ->
                    "The word at this variable of the template; * matches any text, ? one character"
                else -> when (selected) {
                    EditorOp.MATCHES, EditorOp.NOT_MATCHES ->
                        "Regular expression, found anywhere; ignores case unless it starts with (?-i)"
                    in EditorOp.TIME -> "e.g. 2026-10-09 08:30:00.000, 2026-10-09 or 08:30"
                    in EditorOp.NUMBER ->
                        "A number, e.g. 500, -1.5 or 2e3; records whose value is no number are left out"
                    else -> "* matches any text, ? one character"
                }
            }
        }

        private fun load(rule: Query.Rule) {
            val line = EditorRule.of(rule, fieldType(), isTemplate())
            case.isSelected = line.matchCase
            line.level?.let { level.selectedItem = it }
            text.text = line.value
            fillOps(line.op)
        }

        fun rule(includeEmpty: Boolean): Query.Rule? {
            val selected = selectedOp() ?: return null
            val name = field.selectedItem as String
            val line = EditorRule(name, selected, text.text, level.selectedItem as Level, case.isSelected)
            return line.rule(isTemplate(), includeEmpty)
        }
    }

    private companion object {
        val VARIABLE = Regex("""\{(\d+)}""")
    }

    private class FormattingListCellRenderer(private val formatter: (Any?) -> String?) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component? {
            return super.getListCellRendererComponent(list, formatter.invoke(value), index, isSelected, cellHasFocus)
        }
    }
}
