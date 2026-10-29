package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.formatTimeSpan
import io.github.rastislavpapp.logxy.core.template.Templates
import io.github.rastislavpapp.logxy.core.trace.TraceRecords
import io.github.rastislavpapp.logxy.core.trace.TraceSteps
import io.github.rastislavpapp.logxy.core.trace.Traces
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.roundToInt

private const val STEPS_ACCURACY = "Count and total are exact; median and p95 are estimated, within 1%."

/**
 * Which step: the message templates of its two records, [from] the first, [to] the second, as [TraceSteps] gives
 * them; null stands for the records past the templates kept.
 */
internal data class StepKey(val from: String?, val to: String?) {
    /** The two templates numbered, as `Calling {1} → {1} answered`. */
    val name: String get() = "${templateName(from)} → ${templateName(to)}"
}

/** [template] numbered; null stands for the records past the templates kept. */
private fun templateName(template: String?): String =
    if (template == null) "Other messages" else Templates.numbered(template)

/** [nanos] as a time span; empty for [Traces.NO_DURATION]. */
private fun durationText(nanos: Long) = if (nanos == Traces.NO_DURATION) "" else formatTimeSpan(nanos)

/** The width of [sample] in [table]'s font, with room for the cell's margins. */
private fun widthOf(table: JTable, sample: String) =
    table.getFontMetrics(table.font).stringWidth(sample) + look.scale(16)

/**
 * A line of text over a table of [model] in the traces' monospaced font, without a grid. [setUpTable] sets the
 * columns up again whenever the look changes.
 */
internal abstract class TraceTablePanel(model: AbstractTableModel) : JPanel(BorderLayout()) {
    protected val heading = JLabel()
    protected val table = JTable(model)

    init {
        heading.border = BorderFactory.createEmptyBorder(look.scale(4), look.scale(8), look.scale(4), look.scale(8))
        add(heading, BorderLayout.NORTH)
        add(JScrollPane(table).apply { border = NO_OUTLINE }, BorderLayout.CENTER)
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        (table.tableHeader.defaultRenderer as? JLabel)?.horizontalAlignment = SwingConstants.LEADING
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    override fun updateUI() {
        super.updateUI()
        // Also called from JPanel's constructor, before the table is there.
        @Suppress("SENSELESS_COMPARISON")
        if (table != null) setUpTable()
    }

    protected abstract fun setUpTable()

    /**
     * Sets the font, and sizes the columns to fit [samples], each at most twice that, so the column without one,
     * [wide], gets the rest, starting from the width of [wideSample].
     */
    protected fun fitColumns(samples: List<String>, wide: Int, wideSample: String) {
        val base = look.monospacedFont
        table.font = Font(base.name, base.style, base.size)
        table.rowHeight = table.getFontMetrics(table.font).height + 4
        for ((column, sample) in samples.withIndex()) {
            val tableColumn = table.columnModel.getColumn(column)
            val width = widthOf(table, if (column == wide) wideSample else sample)
            tableColumn.preferredWidth = width
            if (column != wide) tableColumn.maxWidth = 2 * width
        }
    }
}

/**
 * The records of one trace in order, each with the step to the next record and that step's share of the trace's
 * duration, as a bar. A double-click on a record calls [goTo] with its record number.
 */
internal class TraceRecordsView(private val goTo: (record: Int) -> Unit) : TraceTablePanel(RecordsModel()) {
    private val model get() = table.model as RecordsModel

    init {
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val records = model.records ?: return
                val row = table.rowAtPoint(e.point)
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2 && row >= 0) goTo(records.record(row))
            }
        })
        setUpTable()
        show(null, "", 0)
    }

    override fun setUpTable() {
        val samples = listOf("0000-00-00 00:00:00.000", "", "00min 00.000s", "100%  ")
        fitColumns(samples, MESSAGE, "Calling the payment service for order")
        for (column in 0..<table.columnCount) {
            val renderer = when (column) {
                SHARE -> ShareRenderer()
                MESSAGE -> TextRenderer(numbers = false) { row -> model.records?.template(row)?.let(::templateName) }
                else -> TextRenderer(numbers = column == STEP)
            }
            table.columnModel.getColumn(column).cellRenderer = renderer
        }
    }

    /**
     * Shows [records], those of the trace named [trace], which has [total] records in all; with null records,
     * [trace] is a hint instead, such as to select a trace.
     */
    fun show(records: TraceRecords?, trace: String, total: Int) {
        val shown = if (records == null || records.size == total) "" else "the first ${"%,d".format(records.size)} of "
        heading.text = when {
            records == null -> trace.ifEmpty { "Select a trace to see its records and the steps between them." }
            total == 1 -> "Trace $trace has one record, so no steps."
            else -> "Trace $trace · $shown${count(total, "record")} · double-click one to go to it"
        }
        model.records = records
        model.fireTableDataChanged()
    }

    private class RecordsModel : AbstractTableModel() {
        var records: TraceRecords? = null

        override fun getRowCount() = records?.size ?: 0
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("Time", "Message", "Step to next", "Share")[column]

        override fun getValueAt(row: Int, column: Int): Any? {
            val records = records ?: return null
            return when (column) {
                TIME -> formatTraceTime(records.time(row))
                MESSAGE -> records.message(row)
                STEP -> durationText(records.step(row))
                else -> if (records.step(row) == Traces.NO_DURATION) null else records.share(row)
            }
        }
    }

    private companion object {
        const val TIME = 0
        const val MESSAGE = 1
        const val STEP = 2
        const val SHARE = 3
    }
}

/**
 * The steps of all traces summed by template pair, the most total time first: how many, how long in the middle and
 * at p95, in total, and their share of all steps' time. Selecting a step calls [select] with it, or with null when
 * none is selected.
 */
internal class TraceStepsView(private val select: (StepKey?) -> Unit) : TraceTablePanel(StepsModel()) {
    private val model get() = table.model as StepsModel

    /** Whether the selection is being set from code, so its change is not the user's choice. */
    private var choosing = false

    init {
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || choosing) return@addListSelectionListener
            select(model.step(table.selectedRow))
        }
        heading.toolTipText = "A step is the time from one record of a trace to the next one of the same trace. " +
            "Select a row to list the traces where that step took longest."
        setUpTable()
        show(traces = null, selected = null)
    }

    override fun setUpTable() {
        val samples = listOf("", "000,000", "00min 00.000s", "00min 00.000s", "00h 00min 00.000s", "100%  ")
        fitColumns(samples, NAME, "Calling payment service → Payment answered")
        for (column in 0..<table.columnCount) {
            val renderer = when (column) {
                SHARE -> ShareRenderer()
                NAME -> TextRenderer(numbers = false) { row -> model.step(row)?.name }
                else -> TextRenderer(numbers = true)
            }
            table.columnModel.getColumn(column).cellRenderer = renderer
        }
    }

    /** Shows the steps of [traces], keeping the step [selected] selected if it is there. */
    fun show(traces: Traces?, selected: StepKey?) {
        val steps = traces?.steps
        heading.text = when {
            traces == null -> " "
            steps == null -> "Steps are named by message templates, and these records have no message."
            steps.size == 0 -> "No trace has two records with times, so there are no steps."
            else -> "${count(steps.size, "kind")} of step, the most total time first. $STEPS_ACCURACY"
        }
        choosing = true
        try {
            model.update(steps)
            val row = if (selected == null) -1 else model.rowOf(selected)
            if (row >= 0) table.setRowSelectionInterval(row, row) else table.clearSelection()
        } finally {
            choosing = false
        }
    }

    fun clearSelection() = table.clearSelection()

    private class StepsModel : AbstractTableModel() {
        private var steps: TraceSteps? = null
        private var order = IntArray(0)

        fun update(steps: TraceSteps?) {
            this.steps = steps
            order = steps?.order() ?: IntArray(0)
            fireTableDataChanged()
        }

        /** The step shown in [row]; null if there is none. */
        fun step(row: Int): StepKey? {
            val steps = steps ?: return null
            val pair = order.getOrNull(row) ?: return null
            return StepKey(steps.from(pair), steps.to(pair))
        }

        /** The row [step] is shown in; -1 if it is not. */
        fun rowOf(step: StepKey): Int {
            val steps = steps ?: return -1
            return order.indexOf(steps.pairOf(step.from, step.to))
        }

        override fun getRowCount() = order.size
        override fun getColumnCount() = 6
        override fun getColumnName(column: Int) = listOf("Step", "Count", "Median", "p95", "Total", "Share")[column]

        override fun getValueAt(row: Int, column: Int): Any? {
            val steps = steps ?: return null
            val pair = order[row]
            return when (column) {
                NAME -> StepKey(steps.from(pair), steps.to(pair)).name
                COUNT -> "%,d".format(steps.count(pair))
                MEDIAN -> durationText(steps.median(pair))
                P95 -> durationText(steps.p95(pair))
                TOTAL -> durationText(steps.total(pair))
                else -> if (steps.total == 0L) 0.0 else steps.total(pair).toDouble() / steps.total
            }
        }
    }

    private companion object {
        const val NAME = 0
        const val COUNT = 1
        const val MEDIAN = 2
        const val P95 = 3
        const val TOTAL = 4
        const val SHARE = 5
    }
}

/** A cell of text, or of [numbers] right-aligned, with the tooltip [tip] gives for its row, if any. */
private class TextRenderer(numbers: Boolean, private val tip: (row: Int) -> String? = { null }) :
    DefaultTableCellRenderer() {
    init {
        horizontalAlignment = if (numbers) SwingConstants.TRAILING else SwingConstants.LEADING
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        toolTipText = tip(row)
        return this
    }
}

/** A share from 0 to 1 as a percentage, over a bar as long as the share of the cell; nothing for null. */
private class ShareRenderer : DefaultTableCellRenderer() {
    private var share = 0.0

    init {
        horizontalAlignment = SwingConstants.TRAILING
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        share = (value as? Double) ?: 0.0
        val text = if (value == null) "" else "${(share * 100).roundToInt()}%"
        return super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
    }

    // Over the cell's background, see-through so the text stays readable.
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val inset = look.scale(2)
        val accent = look.accent
        g.color = Color(accent.red, accent.green, accent.blue, BAR_ALPHA)
        g.fillRect(inset, inset, ((width - 2 * inset) * share).roundToInt(), height - 2 * inset)
    }

    private companion object {
        const val BAR_ALPHA = 80
    }
}
