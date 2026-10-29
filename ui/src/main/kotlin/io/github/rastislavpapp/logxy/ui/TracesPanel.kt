package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.formatTimeSpan
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.TraceRules
import io.github.rastislavpapp.logxy.core.session.SessionStatus
import io.github.rastislavpapp.logxy.core.trace.TraceColumn
import io.github.rastislavpapp.logxy.core.trace.TraceRecords
import io.github.rastislavpapp.logxy.core.trace.TraceSteps
import io.github.rastislavpapp.logxy.core.trace.Traces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

/** Building the traces again while a file is tailed waits this long, so a busy file does not keep it busy. */
private const val REFRESH_MILLIS = 1000

/** Builds that take longer show their progress. */
private const val PROGRESS_DELAY_MILLIS = 200

private const val TABLE_CARD = "table"
private const val PLACEHOLDERS_CARD = "placeholders"

private const val SCOPE_TIP =
    "Traces are found in all records, whatever the filter: a click on a trace sets the filter to it"

/** A field the trace ids can come from: its [name], and the name its column shows, as the picker lists it. */
class TraceColumnChoice(val name: String, val shown: String) {
    override fun toString() = shown
}

/**
 * The traces of all records by a trace id column the user picks (see `GLOSSARY.md`), one row each, longest first,
 * with the duration statistics above, and beside it tabs: a [TraceDurationChart] of them, the records of the selected
 * trace with the steps between them, which [traceRecords] gives, and the steps of all traces summed by template pair.
 * Builds them only while it is showing: [traces] does the work, reading only the records appended since it last ran,
 * and [progress] tells how far it got. The traces ignore the filter, which the statistics say, with the counts of
 * [status].
 *
 * A click shows only the clicked trace's records, Ctrl-click adds a trace to those shown, both through [edit], which
 * sets the filter; they wait for the double-click time, as a double-click instead calls [goTo] with the trace's
 * first record and leaves the filter as it is. A dot of the chart acts like its trace's row, and a double-click on
 * a record of the selected trace goes to it. Selecting a step lists only the traces where it took longest.
 */
class TracesPanel(
    private val scope: CoroutineScope,
    private val traces: suspend (column: String) -> Traces?,
    private val progress: () -> Double,
    private val status: () -> SessionStatus,
    private val edit: (change: (Query.Group) -> Query.Group) -> Unit,
    private val goTo: (record: Int) -> Unit,
    private val traceRecords: (traces: Traces, trace: Int) -> TraceRecords?,
) : JPanel(BorderLayout()) {

    private val model = TracesModel()
    private val table = JTable(model)

    /** The statistics of the traces, each part with a tooltip saying what it means; or a message, such as an error. */
    private val summary = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0))
    private val picker = JComboBox<TraceColumnChoice>()

    /** Whether [picker] is being set from code, so its change is not the user's choice. */
    private var choosing = false

    private val scrollPane = JScrollPane(table).apply { border = NO_OUTLINE }
    private val placeholders = PlaceholderRows(table)
    private val cards = JPanel(CardLayout()).apply {
        add(scrollPane, TABLE_CARD)
        add(placeholders, PLACEHOLDERS_CARD)
    }

    /** Above the traces while a step narrows them: which step, and the button that shows all traces again. */
    private val stepLabel = JLabel()
    private val stepBar = JPanel(MigLayout("insets 2 8 2 8", "[grow,fill][]")).apply {
        add(stepLabel)
        add(JButton("Show all traces").apply { addActionListener { stepsView.clearSelection() } })
        isVisible = false
    }
    private val center = JPanel(BorderLayout()).apply {
        add(stepBar, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
    }

    private val chart = TraceDurationChart(::clickTrace, ::goToTrace)
    private val recordsView = TraceRecordsView { goTo(it) }
    private val stepsView = TraceStepsView(::narrowTo)
    private val tabs = JTabbedPane().apply {
        addTab("Chart", chart)
        addTab("Trace", recordsView)
        addTab("Steps", stepsView)
    }

    /** The step whose longest traces the table lists instead of all; null to list all. */
    private var step: StepKey? = null

    /** How far a slow build got, from 0 to 1, or -1 if unknown; null while not building. */
    private var shownProgress: Double? = null
    private var job: Job? = null
    private val throttle = Timer(REFRESH_MILLIS) { refresh() }.apply { isRepeats = false }

    /** Whether the records changed while the panel was hidden, so the traces are built once it shows. */
    private var stale = true

    private val doubleClickMillis =
        Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: DEFAULT_DOUBLE_CLICK_MILLIS

    /** Sets the filter to the selected traces once a click turned out not to start a double-click. */
    private val clickTimer = Timer(doubleClickMillis) { showOnlySelected() }.apply { isRepeats = false }

    /** The name of the field the trace ids come from; null until the user picks one. */
    var column: String? = null
        set(value) {
            if (value == field) return
            field = value
            selectChoice()
            showStep(null)
            setTraces(null, IntArray(0))
            refresh()
        }

    init {
        add(JPanel(MigLayout("insets 4 8 2 8", "[][]16[grow]")).apply {
            add(JLabel("Trace id"))
            add(picker)
            add(summary)
        }, BorderLayout.NORTH)
        add(look.horizontalSplit().apply {
            leftComponent = center
            rightComponent = tabs
            resizeWeight = TABLE_SHARE
            border = null
        }, BorderLayout.CENTER)
        picker.addActionListener {
            if (!choosing) column = (picker.selectedItem as TraceColumnChoice?)?.name
        }
        table.rowSorter = TraceSorter()
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            chart.selected = selectedTraces().toIntArray()
            showSelectedRecords()
        }
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        (table.tableHeader.defaultRenderer as? JLabel)?.horizontalAlignment = SwingConstants.LEADING
        updateLook()
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && stale) refresh()
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                if (e.clickCount == 1) {
                    clickTimer.restart()
                } else {
                    clickTimer.stop()
                    model.firstRecord(row)?.let(goTo)
                }
            }
        })
        showSummary()
    }

    override fun updateUI() {
        super.updateUI()
        // Also called from JPanel's constructor, before the table is there.
        @Suppress("SENSELESS_COMPARISON")
        if (table != null) updateLook()
    }

    private fun updateLook() {
        val base = look.monospacedFont
        table.font = Font(base.name, base.style, base.size)
        table.rowHeight = table.getFontMetrics(table.font).height + 4
        setUpColumns()
    }

    /** Lists [choices] in the picker; a column picked before stays picked, also while these records lack it. */
    fun setChoices(choices: List<TraceColumnChoice>) {
        choosing = true
        try {
            picker.model = DefaultComboBoxModel(choices.toTypedArray())
        } finally {
            choosing = false
        }
        selectChoice()
    }

    private fun selectChoice() {
        choosing = true
        try {
            val choices = (0..<picker.itemCount).map { picker.getItemAt(it) }
            picker.selectedItem = choices.firstOrNull { it.name == column }
        } finally {
            choosing = false
        }
    }

    /** Builds the traces again: now if showing, else once shown. */
    fun refresh() {
        throttle.stop()
        job?.cancel()
        stale = true
        if (!isShowing) return
        stale = false
        val column = column ?: return showSummary()
        val (sortColumn, descending) = sortedBy()
        val step = step
        job = scope.launch {
            val timer = Timer(40) { showProgress() }.apply {
                initialDelay = PROGRESS_DELAY_MILLIS
                start()
            }
            try {
                val traces = traces(column)
                val shown = withContext(Dispatchers.Default) {
                    traces?.let { shownTraces(it, sortColumn, descending, step) }
                }
                setTraces(traces, shown?.order ?: IntArray(0), shown)
                showSummary(missing = traces == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Cannot find the traces: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                timer.stop()
                hideProgress()
            }
        }
    }

    /** Builds the traces again within a second, for records that keep coming while a file is tailed. */
    fun refreshSoon() {
        if (!throttle.isRunning) throttle.start()
    }

    /** Forgets the traces, e.g. while the file is read again. */
    fun reset() {
        throttle.stop()
        clickTimer.stop()
        job?.cancel()
        stale = true
        setTraces(null, IntArray(0))
        showSummary()
    }

    /** The column [TraceSorter] sorts by, and whether descending. */
    private fun sortedBy(): Pair<TraceColumn, Boolean> {
        val key = table.rowSorter.sortKeys.firstOrNull() ?: return TraceColumn.DURATION to true
        return model.columnAt(key.column) to (key.sortOrder != SortOrder.ASCENDING)
    }

    /**
     * What is worked out off the UI thread about a build's [Traces]: the table's order, what the chart draws, and
     * while a step narrows the traces, its time in each trace of [order].
     */
    private class Shown(val order: IntArray, val extent: TraceExtent?, val stepTimes: LongArray?)

    /** [traces] sorted as the table shows them, or with [step] those where it took longest, the longest first. */
    private fun shownTraces(
        traces: Traces,
        sortColumn: TraceColumn,
        descending: Boolean,
        step: StepKey?,
    ): Shown {
        val extent = TraceExtent.of(traces)
        if (step == null) return Shown(traces.order(sortColumn, descending), extent, null)
        val none = Shown(IntArray(0), extent, LongArray(0))
        val steps = traces.steps ?: return none
        val pair = steps.pairOf(step.from, step.to)
        if (pair < 0) return none
        return Shown(steps.longest(pair), extent, steps.longestTimes(pair))
    }

    /** Shows [traces] in [order] and in the chart as [shown] has them, keeping the selected traces selected. */
    private fun setTraces(traces: Traces?, order: IntArray, shown: Shown? = null) {
        val selected = selectedTraces()
        chart.show(traces, shown?.extent)
        stepsView.show(traces, step)
        if (model.update(traces, order, shown?.stepTimes)) setUpColumns()
        val rows = IntArray(traces?.size ?: 0) { -1 }
        for ((row, trace) in order.withIndex()) rows[trace] = row
        for (trace in selected) {
            val row = rows.getOrElse(trace) { -1 }
            if (row >= 0) table.addRowSelectionInterval(row, row)
        }
        showSelectedRecords()
    }

    /** Shows the records of the trace at the selection's lead in the Trace tab, or a hint if none is selected. */
    private fun showSelectedRecords() {
        val traces = model.traces
        val lead = table.selectionModel.leadSelectionIndex
        val trace = if (table.isRowSelected(lead)) model.trace(lead) else null
        if (traces == null || trace == null) return recordsView.show(null, "", 0)
        recordsView.show(traceRecords(traces, trace), traces.id(trace), traces.records(trace))
    }

    /** Lists only the traces where the step [selected] took longest; null lists all traces. */
    private fun narrowTo(selected: StepKey?) {
        if (selected == step) return
        showStep(selected)
        table.clearSelection()
        refresh()
    }

    private fun showStep(selected: StepKey?) {
        step = selected
        stepBar.isVisible = selected != null
        if (selected != null) {
            stepLabel.text = "Where ${selected.name} took longest"
            stepLabel.toolTipText = "The ${TraceSteps.LONGEST} traces at most where this step took longest, " +
                "each with its longest time of the step"
        }
    }

    /** Updates the statistics' counts of the records, e.g. after the filter changed. */
    fun statusChanged() {
        if (model.traces != null) showSummary()
    }

    private fun showSummary(missing: Boolean = false) {
        val column = column
        val traces = model.traces
        when {
            column == null -> showMessage("Pick the column that holds the trace ids, such as a request id or a thread.")
            missing -> showMessage("These records have no column “$column” to take trace ids from.")
            traces == null -> showMessage(" ")
            traces.size == 0 -> showMessage("No record has a value in “$column”.")
            else -> showStatistics(traces)
        }
    }

    private fun showMessage(text: String) = showSummaryParts(listOf(JLabel(text)))

    /**
     * The count of traces, in how many records, and their durations' spread, each with a tooltip saying what it
     * means: as the filter does not narrow the traces, "in all 6,576,388 records, not only the 84 shown".
     */
    private fun showStatistics(traces: Traces) {
        val status = status()
        val records = status.records
        val scope = when {
            records == null -> ""
            status.shown == null -> " in all ${"%,d".format(records)} records"
            else -> " in all ${"%,d".format(records)} records, not only the ${"%,d".format(status.shown)} shown"
        }
        val parts = arrayListOf(part(count(traces.size, "trace") + scope, SCOPE_TIP))
        val statistics = traces.statistics
        if (statistics != null) {
            parts += part("min ${formatTimeSpan(statistics.min)}", "The shortest trace")
            parts += part("median ${formatTimeSpan(statistics.median)}", percentileTip("p50", 50, statistics.median))
            parts += part("p90 ${formatTimeSpan(statistics.p90)}", percentileTip("p90", 90, statistics.p90))
            parts += part("p95 ${formatTimeSpan(statistics.p95)}", percentileTip("p95", 95, statistics.p95))
            parts += part("p99 ${formatTimeSpan(statistics.p99)}", percentileTip("p99", 99, statistics.p99))
            parts += part("max ${formatTimeSpan(statistics.max)}", "The longest trace")
        }
        showSummaryParts(parts)
    }

    private fun part(text: String, tip: String) = JLabel(text).apply { toolTipText = tip }

    /** Shows [parts] as the statistics, with dots between them. */
    private fun showSummaryParts(parts: List<JLabel>) {
        summary.removeAll()
        for ((index, part) in parts.withIndex()) {
            if (index > 0) summary.add(JLabel(" · "))
            summary.add(part)
        }
        summary.revalidate()
        summary.repaint()
    }

    private fun showOnlySelected() {
        val column = column ?: return
        val traces = model.traces ?: return
        val ids = selectedTraces().map(traces::id)
        if (ids.isNotEmpty()) edit { TraceRules.showOnly(column, ids) }
    }

    /** A click on a dot of the scatter acts as one on the trace's row; [extend] adds it to the selection. */
    private fun clickTrace(trace: Int, extend: Boolean) {
        val row = model.rowOf(trace)
        if (row < 0) return
        table.changeSelection(row, 0, extend, false)
        table.scrollRectToVisible(table.getCellRect(row, 0, true))
        clickTimer.restart()
    }

    private fun goToTrace(trace: Int) {
        clickTimer.stop()
        model.traces?.firstRecord(trace)?.let(goTo)
    }

    private fun selectedTraces(): List<Int> = table.selectedRows.toList().mapNotNull(model::trace)

    private fun showProgress() {
        val fraction = progress()
        // Reading new records has a progress; sorting the traces after it has none.
        shownProgress = if (fraction >= 1.0) -1.0 else fraction
        if (model.rowCount == 0) (cards.layout as CardLayout).show(cards, PLACEHOLDERS_CARD)
        placeholders.repaint()
        repaint(0, 0, width, PROGRESS_HEIGHT)
    }

    private fun hideProgress() {
        if (shownProgress == null) return
        shownProgress = null
        (cards.layout as CardLayout).show(cards, TABLE_CARD)
        repaint(0, 0, width, PROGRESS_HEIGHT)
    }

    // Painted over the top inset, like the filter bar's strip, so showing it moves nothing.
    override fun paint(g: Graphics) {
        super.paint(g)
        paintProgressStrip(g, width, shownProgress ?: return)
    }

    private fun setUpColumns() {
        val columns = table.columnModel
        for (column in 0..<columns.columnCount) {
            val tableColumn = columns.getColumn(column)
            // The step's time shows as a duration does.
            val shown = if (model.isStep(column)) TraceColumn.DURATION else model.columnAt(column)
            tableColumn.cellRenderer = TraceRenderer(shown)
            val sample = when (shown) {
                TraceColumn.ID -> "0123456789abcdef0123"
                TraceColumn.START, TraceColumn.END -> "0000-00-00 00:00:00.000"
                TraceColumn.DURATION -> "00h 00min 00.000s"
                TraceColumn.RECORDS -> "000,000"
                TraceColumn.LEVEL -> "UNKNOWN"
                TraceColumn.SOURCES -> "server.log, server.log"
            }
            tableColumn.preferredWidth = table.getFontMetrics(table.font).stringWidth(sample) + look.scale(16)
        }
    }

    /**
     * Keeps the sort the header shows, and asks for the traces again in the new order when the user sorts: the
     * model's rows already come in [Traces.order], as a [javax.swing.table.TableRowSorter] would hold an object per
     * trace.
     */
    private inner class TraceSorter : RowSorter<TableModel>() {
        // RowSorter has a model of its own name.
        private val rows get() = this@TracesPanel.model
        private var keys = listOf(SortKey(rows.columnOf(TraceColumn.DURATION), SortOrder.DESCENDING))

        override fun getModel(): TableModel = rows

        override fun toggleSortOrder(column: Int) {
            // A step lists its longest traces in that order only.
            if (step != null) return
            val current = keys.firstOrNull()
            val order = when {
                current?.column == column && current.sortOrder == SortOrder.DESCENDING -> SortOrder.ASCENDING
                current?.column == column -> SortOrder.DESCENDING
                // Numbers and levels start with the largest, text and times with the first.
                rows.columnAt(column) in DESCENDING_FIRST -> SortOrder.DESCENDING
                else -> SortOrder.ASCENDING
            }
            sortKeys = listOf(SortKey(column, order))
        }

        override fun setSortKeys(keys: List<SortKey>?) {
            this.keys = keys.orEmpty()
            fireSortOrderChanged()
            refresh()
        }

        override fun getSortKeys(): List<SortKey> = keys
        override fun convertRowIndexToModel(index: Int) = index
        override fun convertRowIndexToView(index: Int) = index
        override fun getViewRowCount() = rows.rowCount
        override fun getModelRowCount() = rows.rowCount
        override fun modelStructureChanged() {}
        override fun allRowsChanged() {}
        override fun rowsInserted(firstRow: Int, endRow: Int) {}
        override fun rowsDeleted(firstRow: Int, endRow: Int) {}
        override fun rowsUpdated(firstRow: Int, endRow: Int) {}
        override fun rowsUpdated(firstRow: Int, endRow: Int, column: Int) {}
    }

    /**
     * One row per trace, in the order given; a merge's traces also name their files. While a step narrows the
     * traces, a last column has its time in each.
     */
    private class TracesModel : AbstractTableModel() {
        var traces: Traces? = null
            private set
        private var order = IntArray(0)
        private var columns = SINGLE_FILE
        private var stepTimes: LongArray? = null

        /** Shows [traces] in [order], and [stepTimes] in a step's column unless null; tells if the columns changed. */
        fun update(traces: Traces?, order: IntArray, stepTimes: LongArray?): Boolean {
            this.traces = traces
            this.order = order
            val columns = if (traces?.isMerged == true) MERGED else SINGLE_FILE
            val stepChanged = (stepTimes == null) != (this.stepTimes == null)
            this.stepTimes = stepTimes
            if (columns == this.columns && !stepChanged) {
                fireTableDataChanged()
                return false
            }
            this.columns = columns
            fireTableStructureChanged()
            return true
        }

        /** Whether [column] is the step's, which no [TraceColumn] is. */
        fun isStep(column: Int): Boolean = column == columns.size

        fun columnAt(column: Int): TraceColumn = columns[column]

        fun columnOf(column: TraceColumn): Int = columns.indexOf(column)

        /** The trace shown in [row]; null if there is none. */
        fun trace(row: Int): Int? = order.getOrNull(row)

        /** The row [trace] is shown in; -1 if it is not. */
        fun rowOf(trace: Int): Int = order.indexOf(trace)

        fun firstRecord(row: Int): Int? = trace(row)?.let { traces?.firstRecord(it) }

        override fun getRowCount() = order.size

        override fun getColumnCount() = if (stepTimes == null) columns.size else columns.size + 1

        override fun getColumnName(column: Int): String {
            if (isStep(column)) return "Step"
            return when (columns[column]) {
                TraceColumn.ID -> "Trace id"
                TraceColumn.START -> "Start"
                TraceColumn.END -> "End"
                TraceColumn.DURATION -> "Duration"
                TraceColumn.RECORDS -> "Records"
                TraceColumn.LEVEL -> "Worst level"
                TraceColumn.SOURCES -> "Sources"
            }
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            val traces = traces ?: return null
            val trace = order[row]
            val stepTimes = stepTimes
            if (isStep(column) && stepTimes != null) return duration(stepTimes[row])
            return when (columns[column]) {
                TraceColumn.ID -> traces.id(trace)
                TraceColumn.START -> formatTraceTime(traces.start(trace))
                TraceColumn.END -> formatTraceTime(traces.end(trace))
                TraceColumn.DURATION -> duration(traces.duration(trace))
                TraceColumn.RECORDS -> "%,d".format(traces.records(trace))
                TraceColumn.LEVEL -> traces.worstLevel(trace)
                TraceColumn.SOURCES -> traces.sources(trace).joinToString(", ")
            }
        }

        private fun duration(nanos: Long) = if (nanos == Traces.NO_DURATION) "" else formatTimeSpan(nanos)

        companion object {
            val SINGLE_FILE = TraceColumn.entries - TraceColumn.SOURCES
            val MERGED = TraceColumn.entries.toList()
        }
    }

    /** A cell of [column]: numbers right-aligned, the worst level in its color. */
    private class TraceRenderer(private val column: TraceColumn) : DefaultTableCellRenderer() {
        init {
            val numbers = column == TraceColumn.DURATION || column == TraceColumn.RECORDS
            horizontalAlignment = if (numbers) SwingConstants.TRAILING else SwingConstants.LEADING
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val level = value as? Level
            super.getTableCellRendererComponent(table, level?.name ?: value, isSelected, hasFocus, row, column)
            if (this.column == TraceColumn.LEVEL && !isSelected) foreground = levelColor(level, table.foreground)
            return this
        }
    }

    private companion object {
        const val DEFAULT_DOUBLE_CLICK_MILLIS = 500

        /** The table gets this share of a widening panel; the charts beside it the rest. */
        const val TABLE_SHARE = 0.5

        val DESCENDING_FIRST = setOf(TraceColumn.DURATION, TraceColumn.RECORDS, TraceColumn.LEVEL)
    }
}
