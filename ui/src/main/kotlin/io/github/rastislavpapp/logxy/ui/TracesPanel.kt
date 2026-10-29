package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.formatTimeSpan
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.TraceRules
import io.github.rastislavpapp.logxy.core.trace.DurationHistogram
import io.github.rastislavpapp.logxy.core.trace.TraceColumn
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
import java.awt.Font
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
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

/** A field the trace ids can come from: its [name], and the name its column shows, as the picker lists it. */
class TraceColumnChoice(val name: String, val shown: String) {
    override fun toString() = shown
}

/**
 * The traces of all records by a trace id column the user picks (see `GLOSSARY.md`), one row each, longest first,
 * with the duration statistics above, and beside it a histogram of the durations and the traces' start times against
 * their durations ([DurationHistogramChart], [TraceScatterChart]). Builds them only while it is showing: [traces]
 * does the work, reading only the records appended since it last ran, and [progress] tells how far it got.
 *
 * A click shows only the clicked trace's records, Ctrl-click adds a trace to those shown, both through [edit], which
 * sets the filter; they wait for the double-click time, as a double-click instead calls [goToFirst] with the trace's
 * first record and leaves the filter as it is. A dot of the scatter acts like its trace's row.
 */
class TracesPanel(
    private val scope: CoroutineScope,
    private val traces: suspend (column: String) -> Traces?,
    private val progress: () -> Double,
    private val edit: (change: (Query.Group) -> Query.Group) -> Unit,
    private val goToFirst: (record: Int) -> Unit,
) : JPanel(BorderLayout()) {

    private val model = TracesModel()
    private val table = JTable(model)
    private val summary = JLabel(" ")
    private val picker = JComboBox<TraceColumnChoice>()

    /** Whether [picker] is being set from code, so its change is not the user's choice. */
    private var choosing = false

    private val scrollPane = JScrollPane(table).apply { border = NO_OUTLINE }
    private val placeholders = PlaceholderRows(table)
    private val center = JPanel(CardLayout()).apply {
        add(scrollPane, TABLE_CARD)
        add(placeholders, PLACEHOLDERS_CARD)
    }

    private val histogram = DurationHistogramChart()
    private val scatter = TraceScatterChart(::clickTrace, ::goToTrace)
    private val charts = JPanel(GridLayout(1, 2)).apply {
        add(histogram)
        add(JPanel(BorderLayout()).apply {
            add(scatter, BorderLayout.CENTER)
            add(TimeAxis(scatter::timeline), BorderLayout.SOUTH)
        })
    }

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
            rightComponent = charts
            resizeWeight = TABLE_SHARE
            border = null
        }, BorderLayout.CENTER)
        picker.addActionListener {
            if (!choosing) column = (picker.selectedItem as TraceColumnChoice?)?.name
        }
        table.rowSorter = TraceSorter()
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
                    model.firstRecord(row)?.let(goToFirst)
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
        job = scope.launch {
            val timer = Timer(40) { showProgress() }.apply {
                initialDelay = PROGRESS_DELAY_MILLIS
                start()
            }
            try {
                val traces = traces(column)
                val shown = withContext(Dispatchers.Default) { traces?.let { shownTraces(it, sortColumn, descending) } }
                setTraces(traces, shown?.order ?: IntArray(0), shown)
                showSummary(missing = traces == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                summary.text = "Cannot find the traces: ${e.message ?: e.javaClass.simpleName}"
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

    /** What is worked out off the UI thread about a build's [Traces]: the table's order, and what the charts draw. */
    private class Shown(val order: IntArray, val histogram: DurationHistogram?, val extent: TraceExtent?)

    private fun shownTraces(traces: Traces, sortColumn: TraceColumn, descending: Boolean) = Shown(
        traces.order(sortColumn, descending),
        traces.durationHistogram(DURATION_BINS),
        TraceExtent.of(traces),
    )

    /** Shows [traces] in [order] and in the charts as [shown] has them, keeping the selected traces selected. */
    private fun setTraces(traces: Traces?, order: IntArray, shown: Shown? = null) {
        val selected = selectedTraces()
        histogram.histogram = shown?.histogram
        scatter.show(traces, shown?.extent)
        if (model.update(traces, order)) setUpColumns()
        if (selected.isEmpty()) return
        val rows = IntArray(order.size)
        for ((row, trace) in order.withIndex()) rows[trace] = row
        for (trace in selected) {
            if (trace < rows.size) table.addRowSelectionInterval(rows[trace], rows[trace])
        }
    }

    private fun showSummary(missing: Boolean = false) {
        val column = column
        val traces = model.traces
        summary.text = when {
            column == null -> "Pick the column that holds the trace ids, such as a request id or a thread."
            missing -> "These records have no column “$column” to take trace ids from."
            traces == null -> " "
            traces.size == 0 -> "No record has a value in “$column”."
            else -> statistics(traces)
        }
    }

    private fun statistics(traces: Traces): String {
        val counted = count(traces.size, "trace")
        val statistics = traces.statistics ?: return counted
        val spread = listOf(
            "min" to statistics.min,
            "median" to statistics.median,
            "p90" to statistics.p90,
            "p95" to statistics.p95,
            "p99" to statistics.p99,
            "max" to statistics.max,
        ).joinToString(" · ") { (label, nanos) -> "$label ${formatTimeSpan(nanos)}" }
        return "$counted · $spread"
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
        model.traces?.firstRecord(trace)?.let(goToFirst)
    }

    private fun selectedTraces(): List<Int> = table.selectedRows.toList().mapNotNull(model::trace)

    private fun showProgress() {
        val fraction = progress()
        // Reading new records has a progress; sorting the traces after it has none.
        shownProgress = if (fraction >= 1.0) -1.0 else fraction
        if (model.rowCount == 0) (center.layout as CardLayout).show(center, PLACEHOLDERS_CARD)
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
        paintProgressStrip(g, width, shownProgress ?: return)
    }

    private fun setUpColumns() {
        val columns = table.columnModel
        for (column in 0..<columns.columnCount) {
            val tableColumn = columns.getColumn(column)
            val shown = model.columnAt(column)
            tableColumn.cellRenderer = TraceRenderer(shown)
            val sample = when (shown) {
                TraceColumn.ID -> "0123456789abcdef0123"
                TraceColumn.START, TraceColumn.END -> "0000-00-00 00:00:00.000"
                TraceColumn.DURATION -> "0 min 00.000 s"
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

    /** One row per trace, in the order given; a merge's traces also name their files. */
    private class TracesModel : AbstractTableModel() {
        var traces: Traces? = null
            private set
        private var order = IntArray(0)
        private var columns = SINGLE_FILE

        /** Shows [traces] in [order]; returns whether the columns changed. */
        fun update(traces: Traces?, order: IntArray): Boolean {
            this.traces = traces
            this.order = order
            val columns = if (traces?.isMerged == true) MERGED else SINGLE_FILE
            if (columns == this.columns) {
                fireTableDataChanged()
                return false
            }
            this.columns = columns
            fireTableStructureChanged()
            return true
        }

        fun columnAt(column: Int): TraceColumn = columns[column]

        fun columnOf(column: TraceColumn): Int = columns.indexOf(column)

        /** The trace shown in [row]; null if there is none. */
        fun trace(row: Int): Int? = order.getOrNull(row)

        /** The row [trace] is shown in; -1 if it is not. */
        fun rowOf(trace: Int): Int = order.indexOf(trace)

        fun firstRecord(row: Int): Int? = trace(row)?.let { traces?.firstRecord(it) }

        override fun getRowCount() = order.size

        override fun getColumnCount() = columns.size

        override fun getColumnName(column: Int) = when (columns[column]) {
            TraceColumn.ID -> "Trace id"
            TraceColumn.START -> "Start"
            TraceColumn.END -> "End"
            TraceColumn.DURATION -> "Duration"
            TraceColumn.RECORDS -> "Records"
            TraceColumn.LEVEL -> "Worst level"
            TraceColumn.SOURCES -> "Sources"
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            val traces = traces ?: return null
            val trace = order[row]
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
