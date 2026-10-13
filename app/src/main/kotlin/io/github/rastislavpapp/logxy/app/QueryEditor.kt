package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryOp
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
import javax.swing.UIManager

/** Operators as the visual editor offers them; several map to the same [QueryOp] with a wildcard shape. */
private enum class RuleOp(val label: String) {
    CONTAINS("contains"),
    NOT_CONTAINS("does not contain"),
    STARTS_WITH("starts with"),
    ENDS_WITH("ends with"),
    IS("is"),
    IS_NOT("is not"),
    MATCHES("matches regex"),
    NOT_MATCHES("does not match regex"),
    LEVEL_AT_LEAST("at least"),
    LEVEL_ABOVE("above"),
    LEVEL_AT_MOST("at most"),
    LEVEL_BELOW("below"),
    LEVEL_IS("is level"),
    LEVEL_IS_NOT("is not level"),
    TIME_FROM("at or after"),
    TIME_AFTER("after"),
    TIME_TO("at or before"),
    TIME_BEFORE("before"),
    NUMBER_AT_LEAST("≥"),
    NUMBER_ABOVE(">"),
    NUMBER_AT_MOST("≤"),
    NUMBER_BELOW("<"),
    ;

    val usesLevel get() = name.startsWith("LEVEL_")
    val usesCase get() = this in setOf(CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, IS, IS_NOT)

    override fun toString() = label

    companion object {
        val TEXT = listOf(CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, IS, IS_NOT, MATCHES, NOT_MATCHES)
        val LEVEL = listOf(LEVEL_AT_LEAST, LEVEL_ABOVE, LEVEL_AT_MOST, LEVEL_BELOW, LEVEL_IS, LEVEL_IS_NOT)
        val TIME = listOf(TIME_FROM, TIME_AFTER, TIME_TO, TIME_BEFORE)
        val NUMBER = listOf(NUMBER_AT_LEAST, NUMBER_ABOVE, NUMBER_AT_MOST, NUMBER_BELOW)
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
 * Visual editor of a [Query]: rows of conditions, optionally in nested groups. Rows negate through
 * their operator ("does not contain"), groups through "none" / "not all". Rules with an empty value
 * are ignored.
 */
class QueryEditor(private val onApply: () -> Unit) : JPanel(MigLayout("insets 0, fillx", "[grow, fill]")) {

    private var fields: List<Field> = emptyList()

    /** Whether rules on the message template, [Query.PATTERN_FIELD], are offered; set before [setFields]. */
    var patterns = false
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
        // Rows without values only stand in for conditions: start them over on the new fields (Line, not Message, for raw text).
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

    private fun isPattern(name: String) = name.equals(Query.PATTERN_FIELD, ignoreCase = true) && fieldType(name) == null

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
        // The editor grows with its rows, so everything up to the window must lay out again.
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
            add(toolBarButton(Icons.add, "Add a condition${if (isRoot) "" else " to this group"}", focusable = true) { addItem(group = false) }, "wmin pref")
            add(toolBarButton(null, "Add a group of conditions, matching all, any or none of them", "( )", focusable = true) { addItem(group = true) }, "wmin pref")
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
                g2.color = UIManager.getColor("Component.borderColor")
                // Strokes are centred on the path: without the half pixel, the left edge at x = 0 is half outside and fades or vanishes when scaled.
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

        // The top level keeps one condition, so its only one has no ×; hidden still takes its space, keeping rows aligned.
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
                // An empty group has no use: remove it too (which may empty its parent). The top level keeps one row.
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
        private val op = JComboBox<RuleOp>()
        private val case = fieldToggle("inline/matchCase", "Match exact case")
        private val text = JTextField(12).apply {
            addActionListener { onApply() }
            toolTipText = "* matches any text, ? one character"
            withToggles(case)
            limitPaste()
        }
        private val level = JComboBox(Level.entries.filter { it != Level.UNKNOWN }.toTypedArray())
        private var valueSlot: JComponent = text
        val remove = toolBarButton(Icons.closeSmall, "Remove this condition", focusable = true) {
            // Only a condition with a value filtered anything.
            val counted = rule(includeEmpty = false) != null
            group.removeItem(this)
            if (counted) onApply()
        }

        init {
            isOpaque = false
            val names = fields.map { it.name } + listOfNotNull(Query.PATTERN_FIELD.takeIf { patterns })
            val known = names.firstOrNull { it.equals(rule.field, ignoreCase = true) }
            names.forEach(field::addItem)
            if (known == null) field.addItem(rule.field)
            field.selectedItem = known ?: rule.field
            field.addActionListener { fieldChanged() }
            op.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean): Component =
                    super.getListCellRendererComponent(list, (value as? RuleOp)?.label, index, isSelected, hasFocus)
            }
            op.addActionListener { opChanged() }
            listOf(field, op, level).forEach { applyOnEnter(it, onApply) }
            add(field)
            add(op)
            add(text, "wmin 60")
            add(toolBarButton(null, "Create a group from this condition", "( )", focusable = true) { group.wrapInGroup(this) })
            add(remove)
            load(rule)
        }

        fun focusValue() {
            text.requestFocusInWindow()
        }

        private fun fieldType() = fieldType(field.selectedItem as String)

        private fun isPattern() = isPattern(field.selectedItem as String)

        // Templates match exactly.
        private fun ops(): List<RuleOp> = if (isPattern()) {
            listOf(RuleOp.IS, RuleOp.IS_NOT)
        } else {
            when (fieldType()) {
                ColumnType.LEVEL -> RuleOp.LEVEL + RuleOp.TEXT
                ColumnType.TIMESTAMP -> RuleOp.TIME + RuleOp.TEXT
                else -> RuleOp.TEXT + RuleOp.NUMBER
            }
        }

        private fun fillOps(selected: RuleOp) {
            val listeners = op.actionListeners
            listeners.forEach(op::removeActionListener)
            op.removeAllItems()
            ops().forEach(op::addItem)
            op.selectedItem = if (selected in ops()) selected else ops().first()
            listeners.forEach(op::addActionListener)
            opChanged()
        }

        private fun fieldChanged() = fillOps(op.selectedItem as RuleOp? ?: RuleOp.CONTAINS)

        private fun opChanged() {
            val selected = op.selectedItem as RuleOp? ?: return
            val wanted: JComponent = if (selected.usesLevel) level else text
            if (wanted !== valueSlot) {
                val index = components.indexOf(valueSlot)
                remove(valueSlot)
                add(wanted, "growx, wmin 60", index)
                valueSlot = wanted
                changed()
            }
            case.isEnabled = selected.usesCase && !isPattern()
            text.toolTipText = if (isPattern()) {
                "The message with its variable parts (numbers, ids, …) written as {}"
            } else {
                when (selected) {
                    RuleOp.MATCHES, RuleOp.NOT_MATCHES -> "Regular expression, found anywhere; ignores case unless it starts with (?-i)"
                    in RuleOp.TIME -> "e.g. 2026-10-09 08:30:00.000, 2026-10-09 or 08:30"
                    in RuleOp.NUMBER -> "A number, e.g. 500, -1.5 or 2e3; records whose value is no number are left out"
                    else -> "* matches any text, ? one character"
                }
            }
        }

        private fun load(original: Query.Rule) {
            // Rows have no NOT of their own; a negated rule from the text form takes the opposite operator.
            val rule = if (original.negated) original.copy(op = opposite(original.op), negated = false) else original
            val type = fieldType()
            val levelName = Level.entries.firstOrNull { it.name.equals(rule.value, ignoreCase = true) }
            val (ruleOp, value) = when {
                rule.op.comparison && type == ColumnType.LEVEL -> when (rule.op) {
                    QueryOp.AT_LEAST -> RuleOp.LEVEL_AT_LEAST
                    QueryOp.ABOVE -> RuleOp.LEVEL_ABOVE
                    QueryOp.AT_MOST -> RuleOp.LEVEL_AT_MOST
                    else -> RuleOp.LEVEL_BELOW
                } to rule.value
                rule.op.comparison && type != ColumnType.TIMESTAMP -> when (rule.op) {
                    QueryOp.AT_LEAST -> RuleOp.NUMBER_AT_LEAST
                    QueryOp.ABOVE -> RuleOp.NUMBER_ABOVE
                    QueryOp.AT_MOST -> RuleOp.NUMBER_AT_MOST
                    else -> RuleOp.NUMBER_BELOW
                } to rule.value
                rule.op.comparison -> when (rule.op) {
                    QueryOp.AT_LEAST -> RuleOp.TIME_FROM
                    QueryOp.ABOVE -> RuleOp.TIME_AFTER
                    QueryOp.AT_MOST -> RuleOp.TIME_TO
                    else -> RuleOp.TIME_BEFORE
                } to rule.value
                type == ColumnType.LEVEL && rule.bare && levelName != null && rule.op == QueryOp.EQUALS -> RuleOp.LEVEL_IS to rule.value
                type == ColumnType.LEVEL && rule.bare && levelName != null && rule.op == QueryOp.NOT_EQUALS -> RuleOp.LEVEL_IS_NOT to rule.value
                isPattern() -> (if (rule.op == QueryOp.NOT_EQUALS || rule.op == QueryOp.NOT_EQUALS_CASE) RuleOp.IS_NOT else RuleOp.IS) to rule.value
                rule.op == QueryOp.MATCHES -> RuleOp.MATCHES to rule.value
                rule.op == QueryOp.NOT_MATCHES -> RuleOp.NOT_MATCHES to rule.value
                // An empty value is a placeholder; show it as the default "contains".
                rule.value.isEmpty() && rule.op == QueryOp.EQUALS -> RuleOp.CONTAINS to ""
                else -> globShape(rule.value, negated = rule.op == QueryOp.NOT_EQUALS || rule.op == QueryOp.NOT_EQUALS_CASE)
            }
            case.isSelected = rule.op == QueryOp.EQUALS_CASE || rule.op == QueryOp.NOT_EQUALS_CASE
            if (levelName != null) level.selectedItem = levelName
            text.text = if (ruleOp.usesLevel) "" else value
            fillOps(ruleOp)
        }

        private fun opposite(op: QueryOp) = when (op) {
            QueryOp.EQUALS -> QueryOp.NOT_EQUALS
            QueryOp.NOT_EQUALS -> QueryOp.EQUALS
            QueryOp.EQUALS_CASE -> QueryOp.NOT_EQUALS_CASE
            QueryOp.NOT_EQUALS_CASE -> QueryOp.EQUALS_CASE
            QueryOp.MATCHES -> QueryOp.NOT_MATCHES
            QueryOp.NOT_MATCHES -> QueryOp.MATCHES
            QueryOp.AT_LEAST -> QueryOp.BELOW
            QueryOp.BELOW -> QueryOp.AT_LEAST
            QueryOp.ABOVE -> QueryOp.AT_MOST
            QueryOp.AT_MOST -> QueryOp.ABOVE
        }

        /** The plainest operator showing [pattern]: "*x*" is "contains x", "x*" is "starts with x". */
        private fun globShape(pattern: String, negated: Boolean): Pair<RuleOp, String> {
            fun starAt(i: Int) = pattern[i] == '*' && (i == 0 || pattern[i - 1] != '\\')
            fun noStars(from: Int, to: Int) = (from..<to).none { starAt(it) }
            val n = pattern.length
            return when {
                n >= 2 && starAt(0) && starAt(n - 1) && noStars(1, n - 1) ->
                    (if (negated) RuleOp.NOT_CONTAINS else RuleOp.CONTAINS) to pattern.substring(1, n - 1)
                !negated && n >= 1 && starAt(n - 1) && noStars(0, n - 1) -> RuleOp.STARTS_WITH to pattern.substring(0, n - 1)
                !negated && n >= 1 && starAt(0) && noStars(1, n) -> RuleOp.ENDS_WITH to pattern.substring(1)
                else -> (if (negated) RuleOp.IS_NOT else RuleOp.IS) to pattern
            }
        }

        fun rule(includeEmpty: Boolean): Query.Rule? {
            val selected = op.selectedItem as RuleOp? ?: return null
            val name = field.selectedItem as String
            val value = text.text
            // "is not" nothing keeps the records that have a value.
            val notEmpty = selected == RuleOp.IS_NOT && value.isEmpty()
            if (!selected.usesLevel && value.isEmpty() && !includeEmpty && !notEmpty) return null
            val exact = case.isSelected && !isPattern()
            val equals = if (exact) QueryOp.EQUALS_CASE else QueryOp.EQUALS
            val notEquals = if (exact) QueryOp.NOT_EQUALS_CASE else QueryOp.NOT_EQUALS
            val levelName = (level.selectedItem as Level).name
            // An empty row reads as `field == ""`, a placeholder that filters nothing.
            if (notEmpty) return Query.Rule(name, notEquals, "")
            if (value.isEmpty() && !selected.usesLevel) {
                val placeholderOp = if (selected == RuleOp.MATCHES || selected == RuleOp.NOT_MATCHES) QueryOp.MATCHES else equals
                return Query.Rule(name, placeholderOp, "")
            }
            val (queryOp, queryValue, bare) = when (selected) {
                RuleOp.CONTAINS -> Triple(equals, "*$value*", false)
                RuleOp.NOT_CONTAINS -> Triple(notEquals, "*$value*", false)
                RuleOp.STARTS_WITH -> Triple(equals, "$value*", false)
                RuleOp.ENDS_WITH -> Triple(equals, "*$value", false)
                RuleOp.IS -> Triple(equals, value, false)
                RuleOp.IS_NOT -> Triple(notEquals, value, false)
                RuleOp.MATCHES -> Triple(QueryOp.MATCHES, value, false)
                RuleOp.NOT_MATCHES -> Triple(QueryOp.NOT_MATCHES, value, false)
                RuleOp.LEVEL_AT_LEAST -> Triple(QueryOp.AT_LEAST, levelName, true)
                RuleOp.LEVEL_ABOVE -> Triple(QueryOp.ABOVE, levelName, true)
                RuleOp.LEVEL_AT_MOST -> Triple(QueryOp.AT_MOST, levelName, true)
                RuleOp.LEVEL_BELOW -> Triple(QueryOp.BELOW, levelName, true)
                RuleOp.LEVEL_IS -> Triple(QueryOp.EQUALS, levelName, true)
                RuleOp.LEVEL_IS_NOT -> Triple(QueryOp.NOT_EQUALS, levelName, true)
                RuleOp.TIME_FROM -> Triple(QueryOp.AT_LEAST, value, false)
                RuleOp.TIME_AFTER -> Triple(QueryOp.ABOVE, value, false)
                RuleOp.TIME_TO -> Triple(QueryOp.AT_MOST, value, false)
                RuleOp.TIME_BEFORE -> Triple(QueryOp.BELOW, value, false)
                RuleOp.NUMBER_AT_LEAST -> Triple(QueryOp.AT_LEAST, value, true)
                RuleOp.NUMBER_ABOVE -> Triple(QueryOp.ABOVE, value, true)
                RuleOp.NUMBER_AT_MOST -> Triple(QueryOp.AT_MOST, value, true)
                RuleOp.NUMBER_BELOW -> Triple(QueryOp.BELOW, value, true)
            }
            return Query.Rule(name, queryOp, queryValue, bare)
        }
    }
}
