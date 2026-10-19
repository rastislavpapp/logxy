package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.util.UIScale
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.template.TemplateCounts
import io.github.rastislavpapp.logxy.core.template.Templates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicGraphicsUtils
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.PI
import kotlin.math.sin

/** Counting again while a file is tailed waits this long, so a busy file does not keep it counting. */
private const val REFRESH_MILLIS = 1000

/** Counts that take longer show their progress. */
private const val PROGRESS_DELAY_MILLIS = 200

private const val PROGRESS_HEIGHT = 3

/** How long the strip's segment takes to cross while the progress is unknown, and the placeholders to pulse. */
private const val CYCLE_MILLIS = 1200.0

private const val TABLE_CARD = "table"
private const val PLACEHOLDERS_CARD = "placeholders"

private val PADDING = EmptyBorder(0, 0, 0, 12)

private const val OTHER_MESSAGES = "(other messages, over the limit of different templates)"

/**
 * Whether records with [this] schema can be filtered by message template: they have a message, and no field of
 * the log takes the name.
 */
fun Schema.hasTemplates() =
    indexOf(Role.MESSAGE) >= 0 && fields.none { it.name.equals(Query.TEMPLATE_FIELD, ignoreCase = true) }

/**
 * The shown rows grouped by the template of their message (see [Templates]), most frequent first, with
 * a count per level. Counts only while it is showing: [count] does the work and is cancelled when a newer
 * count starts. Filtering by the selected templates goes through [addRules].
 *
 * While [canChart], clicking a template's `{}` charts that slot in [charts] or stops, as does the context menu; a
 * charted slot is drawn bold in its chart's color, and one found to hold no number faint, as it cannot be charted.
 * Hovering a slot tells what its values came to, once read. The columns fit their contents; the template's takes
 * the rest of the width.
 */
class TemplatesPanel(
    private val scope: CoroutineScope,
    private val count: suspend () -> TemplateCounts?,
    private val progress: () -> Double,
    private val addRules: (List<Query>) -> Unit,
    private val charts: SlotCharts,
    private val canChart: () -> Boolean,
) : JPanel(BorderLayout()) {

    private val model = TemplatesModel()

    /** The slot under the mouse, as its template and number, or null. */
    private var hovered: Pair<String, Int>? = null

    private val table = object : JTable(model) {
        override fun getToolTipText(e: MouseEvent): String? {
            val (template, slot) = slotAt(e.point) ?: return super.getToolTipText(e)
            return slotToolTip(template, slot)
        }

        // Selecting a row would bring a long template's whole cell into view, scrolling the counts out of it.
        override fun scrollRectToVisible(rect: Rectangle) {
            val visible = visibleRect
            super.scrollRectToVisible(Rectangle(visible.x, rect.y, visible.width, rect.height))
        }
    }
    private val summary = JLabel(" ")
    private val scrollPane = JScrollPane(table).apply { border = null }
    private val placeholders = Placeholders()
    private val center = JPanel(CardLayout()).apply {
        add(scrollPane, TABLE_CARD)
        add(placeholders, PLACEHOLDERS_CARD)
    }

    /** How far a slow count got, from 0 to 1, or -1 if unknown; null while not counting. */
    private var shownProgress: Double? = null
    private var job: Job? = null
    private val throttle = Timer(REFRESH_MILLIS) { refresh() }.apply { isRepeats = false }

    /** Counted rows changed while hidden, so they are counted once shown. */
    private var stale = true

    /** Whether the records can be filtered by template; see [hasTemplates]. */
    var canFilter = true

    /** Whether the panel shows counts, so the templates of the shown rows have been found. */
    val hasCounts: Boolean get() = model.hasCounts

    init {
        add(JPanel(MigLayout("insets 4 8 2 8", "[grow]")).apply { add(summary) }, BorderLayout.NORTH)
        table.autoCreateRowSorter = true
        // Widths are set to fit the contents; see fitColumns.
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitColumns()
        })
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        (table.tableHeader.defaultRenderer as? JLabel)?.horizontalAlignment = SwingConstants.LEADING
        add(center, BorderLayout.CENTER)
        updateLook()
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && stale) refresh()
        }
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = menu(e)
            override fun mouseReleased(e: MouseEvent) = menu(e)

            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val slot = slotAt(e.point)
                when {
                    slot != null && e.clickCount == 1 -> {
                        if (charts.canChart(slot.first, slot.second)) toggleChart(slot.first, slot.second)
                    }
                    // The first click of a double click on a slot charted it: that is all it does.
                    slot == null && e.clickCount == 2 && table.rowAtPoint(e.point) >= 0 -> showOnly(selectedTemplates())
                }
            }

            override fun mouseMoved(e: MouseEvent) = hover(slotAt(e.point))

            override fun mouseExited(e: MouseEvent) = hover(null)

            // The popup trigger comes with the press on some systems, with the release on others.
            private fun menu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
                showMenu(e.x, e.y)
            }
        }
        table.addMouseListener(mouse)
        table.addMouseMotionListener(mouse)
    }

    private fun toggleChart(template: String, slot: Int) = toggleChart(this, charts, template, slot)

    private fun hover(slot: Pair<String, Int>?) {
        val chartable = slot?.takeIf { charts.canChart(it.first, it.second) }
        table.cursor = if (chartable != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
        if (chartable == hovered) return
        hovered = chartable
        table.repaint()
    }

    /** What [slot] of [template] came to, if read, and what clicking it does. */
    private fun slotToolTip(template: String, slot: Int): String {
        val summary = charts.summary(template, slot)
        val action = if (charts.chart(template, slot) != null) "Click to stop charting" else "Click to chart over time"
        return when {
            summary == null -> "<html>Slot ${slot + 1}<br><small>$action</small></html>"
            summary.numbers == 0 -> "None of its ${count(summary.records, "record")} has a number in slot ${slot + 1}"
            else -> {
                val has = if (summary.numbers == 1) "has" else "have"
                val share = "%,d of ${count(summary.records, "record")} $has a number".format(summary.numbers)
                val values = listOf("min" to summary.min, "avg" to summary.average, "max" to summary.max)
                    .joinToString(" · ") { (name, value) -> "$name ${formatSlotValue(value)}" }
                "<html>$share<br>$values<br><small>$action</small></html>"
            }
        }
    }

    /** The template and slot of the `{}` at [point], or null; only while [canChart]. */
    private fun slotAt(point: Point): Pair<String, Int>? {
        if (!canChart()) return null
        val row = table.rowAtPoint(point)
        val column = table.columnAtPoint(point)
        if (row < 0 || column != table.columnCount - 1) return null
        val template = model.template(table.convertRowIndexToModel(row))?.takeIf { it.isNotEmpty() } ?: return null
        val renderer = table.prepareRenderer(table.getCellRenderer(row, column), row, column) as JComponent
        val metrics = table.getFontMetrics(table.font)
        // Laid out as TemplateRenderer paints it.
        var x = table.getCellRect(row, column, false).x + renderer.insets.left
        val parts = template.split(Templates.PLACEHOLDER)
        for (slot in 0..<parts.lastIndex) {
            x += metrics.stringWidth(parts[slot])
            val width = metrics.stringWidth(Templates.PLACEHOLDER)
            if (point.x in x..<x + width) return template to slot
            x += width
        }
        return null
    }

    override fun updateUI() {
        super.updateUI()
        // Also called from JPanel's constructor, before the table is there.
        @Suppress("SENSELESS_COMPARISON")
        if (table != null) updateLook()
    }

    private fun updateLook() {
        val base = UIManager.getFont("monospaced.font")
            ?: Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Table.font").size)
        table.font = Font(base.name, base.style, base.size)
        table.rowHeight = table.getFontMetrics(table.font).height + 4
        setUpColumns()
    }

    /** Counts again: now if showing, else once shown. */
    fun refresh() {
        throttle.stop()
        job?.cancel()
        stale = true
        if (!isShowing) return
        stale = false
        job = scope.launch {
            val timer = Timer(40) { showProgress() }.apply {
                initialDelay = PROGRESS_DELAY_MILLIS
                start()
            }
            try {
                show(count())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                summary.text = "Cannot find templates:${e.message ?: e.javaClass.simpleName}"
            } finally {
                timer.stop()
                hideProgress()
            }
        }
    }

    /** Counts again within a second, for rows that keep coming while a file is tailed. */
    fun refreshSoon() {
        if (!throttle.isRunning) throttle.start()
    }

    /** Forgets the counts, e.g. while the file is read again. */
    fun reset() {
        throttle.stop()
        job?.cancel()
        stale = true
        summary.text = " "
        setCounts(null)
    }

    private fun showProgress() {
        val fraction = progress()
        // Indexing the templates of new records has a progress; counting the rows after it has none.
        shownProgress = if (fraction >= 1.0) -1.0 else fraction
        (center.layout as CardLayout).show(center, PLACEHOLDERS_CARD)
        placeholders.repaint()
        repaint(0, 0, width, PROGRESS_HEIGHT)
    }

    private fun hideProgress() {
        if (shownProgress == null) return
        shownProgress = null
        (center.layout as CardLayout).show(center, TABLE_CARD)
        repaint(0, 0, width, PROGRESS_HEIGHT)
    }

    // Painted over the top inset, like the filter bar's strip, so showing it moves nothing.
    override fun paint(g: Graphics) {
        super.paint(g)
        val fraction = shownProgress ?: return
        g.color = UIManager.getColor("Component.accentColor")
        if (fraction >= 0) {
            g.fillRect(0, 0, (width * fraction.coerceIn(0.0, 1.0)).toInt(), PROGRESS_HEIGHT)
        } else {
            val segment = width / 4
            val phase = System.currentTimeMillis() % CYCLE_MILLIS.toLong() / CYCLE_MILLIS
            val x = (phase * (width + segment)).toInt() - segment
            g.fillRect(x, 0, segment, PROGRESS_HEIGHT)
        }
    }

    private fun show(counts: TemplateCounts?) {
        if (counts == null) {
            setCounts(null)
            summary.text = "These records have no message to find templates in."
            return
        }
        val selected = selectedTemplates().toSet()
        setCounts(counts)
        summary.text = "${count(counts.size, "template")} in ${count(counts.rows, "row")}"
        for (row in 0..<table.rowCount) {
            if (counts.templates[table.convertRowIndexToModel(row)] in selected) table.addRowSelectionInterval(row, row)
        }
    }

    private fun setCounts(counts: TemplateCounts?) {
        val sortKeys = table.rowSorter?.sortKeys
        if (model.update(counts)) {
            setUpColumns()
            // A new set of columns comes with a new sorter.
            table.rowSorter?.sortKeys = sortKeys?.takeIf { keys -> keys.all { it.column < model.columnCount } }
                ?: listOf(RowSorter.SortKey(0, SortOrder.DESCENDING))
        }
        fitColumns()
    }

    private fun setUpColumns() {
        for (column in 0..<table.columnCount) {
            val tableColumn = table.columnModel.getColumn(column)
            tableColumn.cellRenderer =
                if (column == table.columnCount - 1) TemplateRenderer() else CountRenderer(model.levelAt(column))
        }
        fitColumns()
    }

    /**
     * Makes each count column as wide as its header or its widest value, and the template column as wide as the
     * longest template, or the rest of the view if that is wider.
     */
    private fun fitColumns() {
        val columns = table.columnModel
        if (columns.columnCount != model.columnCount) return
        val header = table.tableHeader
        val widths = IntArray(model.columnCount) { column ->
            val name = model.getColumnName(column)
            val headerWidth = header.defaultRenderer
                .getTableCellRendererComponent(table, name, false, false, -1, column).preferredSize.width
            // In the monospaced font, the longest template and the largest count are the widest.
            val isTemplate = column == model.columnCount - 1
            val widest = (0..<model.rowCount).maxByOrNull { row ->
                if (isTemplate) {
                    model.template(row)?.length ?: OTHER_MESSAGES.length
                } else {
                    model.getValueAt(row, column) as Int
                }
            }
            val viewRow = widest?.let { table.convertRowIndexToView(it) } ?: -1
            val cellWidth = if (viewRow < 0) {
                0
            } else {
                table.prepareRenderer(table.getCellRenderer(viewRow, column), viewRow, column).preferredSize.width
            }
            maxOf(headerWidth, cellWidth) + UIScale.scale(4)
        }
        val last = widths.lastIndex
        widths[last] = maxOf(widths[last], scrollPane.viewport.width - widths.sum() + widths[last])
        for (column in widths.indices) {
            val tableColumn = columns.getColumn(column)
            tableColumn.preferredWidth = widths[column]
            tableColumn.width = widths[column]
        }
    }

    /** The selected templates, without the entries that stand for no single template. */
    private fun selectedTemplates(): List<String> =
        table.selectedRows.toList()
            .mapNotNull { model.template(table.convertRowIndexToModel(it)) }
            .filter { it.isNotEmpty() }

    private fun showMenu(x: Int, y: Int) {
        val templates = selectedTemplates()
        val several = templates.size > 1
        val popup = JPopupMenu()
        val showOnly = if (several) "Show Only These ${templates.size} Templates" else "Show Only This Template"
        popup.add(JMenuItem(showOnly, Icons.filter).apply {
            isEnabled = canFilter && templates.isNotEmpty()
            addActionListener { showOnly(templates) }
        })
        popup.add(JMenuItem(if (several) "Hide These ${templates.size} Templates" else "Hide This Template").apply {
            isEnabled = canFilter && templates.isNotEmpty()
            addActionListener { addRules(templates.map { Query.Rule(Query.TEMPLATE_FIELD, QueryOp.NOT_EQUALS, it) }) }
        })
        popup.addSeparator()
        popup.add(JMenuItem(if (several) "Copy Templates" else "Copy Template").apply {
            isEnabled = templates.isNotEmpty()
            addActionListener {
                val selection = StringSelection(templates.joinToString("\n"))
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            }
        })
        val template = templates.singleOrNull()
        val slots = template?.let { Templates.slotCount(it) } ?: 0
        if (canChart() && template != null && slots > 0) {
            popup.addSeparator()
            popup.add(JMenu("Chart Slot").apply {
                for (slot in 0..<slots) {
                    val text = "${slot + 1}: ${SlotChart.label(template, slot).cut(MAX_MENU_CHARS)}"
                    add(JCheckBoxMenuItem(text, charts.chart(template, slot) != null).apply {
                        isEnabled = charts.canChart(template, slot)
                        addActionListener { toggleChart(template, slot) }
                    })
                }
            })
        }
        popup.show(table, x, y)
    }

    private fun showOnly(templates: List<String>) {
        if (!canFilter || templates.isEmpty()) return
        val rules = templates.map { Query.Rule(Query.TEMPLATE_FIELD, QueryOp.EQUALS, it) }
        addRules(listOf(rules.singleOrNull() ?: Query.Group(all = false, items = rules)))
    }

    private fun faint(color: Color) = Color(color.red, color.green, color.blue, color.alpha / 3)

    /** Pulsing boxes where the counts and templates will be, while they are counted. */
    private inner class Placeholders : JComponent() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = table.background
                g2.fillRect(0, 0, width, height)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val pulse = (1 + sin(System.currentTimeMillis() / CYCLE_MILLIS * 2 * PI)) / 2
                val base = UIManager.getColor("Label.disabledForeground")
                g2.color = Color(base.red, base.green, base.blue, (40 + 40 * pulse).toInt())
                val metrics = table.getFontMetrics(table.font)
                val rowHeight = table.rowHeight
                val boxHeight = rowHeight / 2
                val countWidth = metrics.stringWidth("000,000")
                val templateX = metrics.stringWidth("0,000,000,000") + 24
                val arc = UIScale.scale(6)
                var y = table.tableHeader.preferredSize.height + (rowHeight - boxHeight) / 2
                var row = 0
                while (y < height) {
                    // Varied, but the same on every repaint, so the boxes do not jump about.
                    val shape = ((row + 1) * -7046029254386353131L ushr 56) / 255.0
                    val count = (countWidth * (0.5 + shape / 2)).toInt()
                    g2.fillRoundRect(templateX - 24 - count, y, count, boxHeight, arc, arc)
                    val template = ((width - templateX - 16) * (0.3 + 0.55 * shape)).toInt()
                    if (template > 0) g2.fillRoundRect(templateX, y, template, boxHeight, arc, arc)
                    y += rowHeight
                    row++
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** Columns: the total, one per level that some counted row has, and the template. */
    private class TemplatesModel : AbstractTableModel() {
        private var counts: TemplateCounts? = null
        private var levels: List<Level> = emptyList()

        /** Shows [counts]; returns whether the columns changed. */
        fun update(counts: TemplateCounts?): Boolean {
            this.counts = counts
            val levels = counts?.levels.orEmpty()
            if (levels == this.levels) {
                fireTableDataChanged()
                return false
            }
            this.levels = levels
            fireTableStructureChanged()
            return true
        }

        val hasCounts get() = counts != null

        fun levelAt(column: Int): Level? = levels.getOrNull(column - 1)

        /** Null for the records over the template limit. */
        fun template(row: Int): String? = counts?.templates?.get(row)

        override fun getRowCount() = counts?.size ?: 0

        override fun getColumnCount() = levels.size + 2

        override fun getColumnName(column: Int) = when (column) {
            0 -> "Count"
            columnCount - 1 -> "Template"
            else -> levels[column - 1].name
        }

        override fun getColumnClass(column: Int): Class<*> =
            if (column < columnCount - 1) Int::class.javaObjectType else String::class.java

        override fun getValueAt(row: Int, column: Int): Any? {
            val counts = counts ?: return null
            return when (column) {
                0 -> counts.total(row)
                columnCount - 1 -> counts.templates[row]
                else -> counts.count(row, levels[column - 1])
            }
        }
    }

    /** A count, in the color of its [level]; zero shows as nothing. */
    private class CountRenderer(private val level: Level?) : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.TRAILING
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val n = value as Int? ?: 0
            val text = if (n == 0) "" else "%,d".format(n)
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            if (!isSelected) foreground = levelColor(level, table.foreground)
            // Apart from the next column, which starts at its left edge.
            border = CompoundBorder(border, PADDING)
            return this
        }
    }

    /**
     * A template with its placeholders dimmed, so the fixed text stands out; a charted one bold in its chart's color,
     * one without numbers faint, the one under the mouse underlined.
     */
    private inner class TemplateRenderer : DefaultTableCellRenderer() {
        private var template: String? = null
        private var dim = foreground
        private var selected = false

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val template = value as String?
            this.template = template
            val text = when (template) {
                null -> OTHER_MESSAGES
                "" -> "(no message)"
                else -> template
            }
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            selected = isSelected
            dim = if (isSelected) foreground else UIManager.getColor("Label.disabledForeground")
            if (template.isNullOrEmpty()) foreground = dim
            toolTipText = template?.takeIf { it.isNotEmpty() }
            return this
        }

        override fun paintComponent(g: Graphics) {
            val template = template
            if (template.isNullOrEmpty() || Templates.PLACEHOLDER !in template) return super.paintComponent(g)
            // The background as usual, then the text in parts.
            val text = text
            setText("")
            super.paintComponent(g)
            setText(text)
            val g2 = g as Graphics2D
            val metrics = g2.getFontMetrics(font)
            var x = insets.left
            val y = (height - metrics.height) / 2 + metrics.ascent
            val parts = template.split(Templates.PLACEHOLDER)
            val plain = font
            val bold = plain.deriveFont(Font.BOLD)
            val hovered = hovered?.takeIf { it.first == template }?.second
            for ((i, part) in parts.withIndex()) {
                if (x >= width) break
                g2.color = foreground
                g2.font = plain
                BasicGraphicsUtils.drawString(this, g2, part, x.toFloat(), y.toFloat())
                x += metrics.stringWidth(part)
                if (i < parts.lastIndex) {
                    // Advanced by the plain width, as the mouse finds slots by it; monospaced bold is as wide.
                    val chart = charts.chart(template, i)
                    // On the selection's background a chart color may not show; bold still marks it.
                    g2.color = chart?.color?.takeUnless { selected } ?: dim
                    if (chart == null && !charts.canChart(template, i)) g2.color = faint(g2.color)
                    g2.font = if (chart != null) bold else plain
                    BasicGraphicsUtils.drawString(this, g2, Templates.PLACEHOLDER, x.toFloat(), y.toFloat())
                    val placeholder = metrics.stringWidth(Templates.PLACEHOLDER)
                    if (i == hovered) g2.fillRect(x, y + metrics.descent / 2, placeholder, 1)
                    x += placeholder
                }
            }
        }
    }
}
