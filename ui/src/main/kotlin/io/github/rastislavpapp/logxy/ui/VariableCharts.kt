package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.template.Templates
import io.github.rastislavpapp.logxy.core.template.VariableSeries
import io.github.rastislavpapp.logxy.core.template.VariableSlices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Component
import javax.swing.JOptionPane
import javax.swing.Timer
import kotlin.math.abs

/** At most this many variables are charted at once: one per color of [CHART_COLORS]. */
const val MAX_VARIABLE_CHARTS = 6

/** Reading the values again while a file is tailed waits this long, so a busy file does not keep it reading. */
private const val REFRESH_MILLIS = 1000

/** Readable on light and dark backgrounds, and apart from the levels' red and amber: a chart is no error line. */
private val CHART_COLORS = listOf(
    Color(0x3D7FE0),
    Color(0x2E9E58),
    Color(0x9B5DE0),
    Color(0x13A1A1),
    Color(0xD9559F),
    Color(0x8C6E45),
)

/**
 * Charts template variable [variable] of [template] in [charts], or stops charting it if it is charted. Tells the
 * user, in a dialog over [parent], when no more charts fit.
 */
fun toggleChart(parent: Component, charts: VariableCharts, template: String, variable: Int) {
    if (charts.toggle(template, variable)) return
    JOptionPane.showMessageDialog(
        parent,
        "At most $MAX_VARIABLE_CHARTS variables can be charted at once. Close one first.",
        "Chart Variable",
        JOptionPane.INFORMATION_MESSAGE,
    )
}

/** Whole numbers as they are, others to a few significant digits. */
fun formatVariableValue(value: Double): String = when {
    value.isNaN() -> "–"
    value == Math.rint(value) && abs(value) < 1e15 -> "%,d".format(value.toLong())
    abs(value) >= 100 -> "%,.0f".format(value)
    abs(value) >= 1 -> "%.2f".format(value)
    else -> "%.3g".format(value)
}

/** What a chart shows of each slice's numbers; [short] goes in front of its label. */
enum class VariableMetric(val title: String, val short: String, val of: (VariableSlices) -> DoubleArray) {
    AVERAGE("Average", "avg", { it.average }),
    P50("Median (p50)", "p50", { it.p50 }),
    P99("99th Percentile (p99)", "p99", { it.p99 }),
    MAX("Maximum", "max", { it.max }),
    SUM("Sum", "sum", { it.sum }),
    ;

    /**
     * The value a record must be near to stand for [slice] of [slices]: the slice's value of this metric, or for a
     * sum, which no single record comes near, the slice's largest value.
     */
    fun target(slices: VariableSlices, slice: Int): Double = if (this == SUM) slices.max[slice] else of(slices)[slice]
}

/** What the values of a variable came to over all shown rows of its template that have a time. */
class VariableSummary(val records: Int, val numbers: Int, val min: Double, val average: Double, val max: Double) {
    constructor(values: VariableSeries) : this(values.size, values.numbers, values.min, values.average, values.max)
}

/** One variable of a template charted over time; see [VariableCharts]. */
class VariableChart internal constructor(val template: String, val variable: Int, val color: Color) {
    /** False until its values are first read: only a variable with numbers is shown. */
    var shown = false
        internal set

    var metric = VariableMetric.AVERAGE

    /** The shown rows' values; null until read. */
    internal var values: VariableSeries? = null

    /**
     * The last [VariableSeries.slices] computed, and in [slicedValues] the values they were computed from. They are
     * drawn at their own times until newer ones come.
     */
    var slices: VariableSlices? = null
        internal set
    var slicedValues: VariableSeries? = null
        internal set
    internal var slicing: Job? = null

    val label: String = label(template, variable)

    companion object {
        /** [variable] and its numbered [template], e.g. `{2} · took {1} in {2} ms`. */
        fun label(template: String, variable: Int): String = "{${variable + 1}} · ${Templates.numbered(template)}"
    }
}

/**
 * The variables charted in one tab, in the order they were added, each with its own color. Values are read through
 * [read], one pass per template for all its charted variables, for the shown rows, and kept until [refresh] reads them
 * again. [onChanged] is called when charts are added or removed and when new values or slices come. A variable read
 * for the first time is shown only if it holds a number, and then [onShown] is called; otherwise [onNoNumbers] is.
 * Use on the UI thread.
 */
class VariableCharts(
    private val scope: CoroutineScope,
    private val read: suspend (template: String, variables: IntArray) -> List<VariableSeries>?,
) {
    private val charts = mutableListOf<VariableChart>()
    private var job: Job? = null
    private val throttle = Timer(REFRESH_MILLIS) { readValues(charts.toList()) }.apply { isRepeats = false }

    /** What the values came to, of each variable read since the shown rows changed, charted or not. */
    private val summaries = HashMap<Pair<String, Int>, VariableSummary>()

    var onChanged: () -> Unit = {}
    var onNoNumbers: (VariableChart) -> Unit = {}

    /** Called when a chart is first shown, once its values are read and found to hold numbers. */
    var onShown: (VariableChart) -> Unit = {}

    /** Called when the last chart is closed, by [remove] or [removeAll]. */
    var onAllRemoved: () -> Unit = {}

    /** The charts shown, without those still read for the first time. */
    val all: List<VariableChart> get() = charts.filter { it.shown }

    /** The chart of [variable] of [template], also while it is read for the first time; null if none. */
    fun chart(template: String, variable: Int): VariableChart? =
        charts.firstOrNull { it.template == template && it.variable == variable }

    /** What the variable's values came to, if read since the shown rows changed; else null. */
    fun summary(template: String, variable: Int): VariableSummary? = summaries[template to variable]

    /** Whether [variable] of [template] may hold numbers: it was not read, or found some. */
    fun canChart(template: String, variable: Int): Boolean =
        chart(template, variable) != null || summary(template, variable)?.numbers != 0

    /** Charts [variable] of [template], or stops charting it; false if [MAX_VARIABLE_CHARTS] are charted already. */
    fun toggle(template: String, variable: Int): Boolean {
        chart(template, variable)?.let {
            remove(it)
            return true
        }
        if (!canChart(template, variable)) return true
        if (charts.size >= MAX_VARIABLE_CHARTS) return false
        val color = CHART_COLORS.first { color -> charts.none { it.color == color } }
        val chart = VariableChart(template, variable, color)
        charts += chart
        onChanged()
        readValues(listOf(chart))
        return true
    }

    fun remove(chart: VariableChart) {
        chart.slicing?.cancel()
        charts -= chart
        onChanged()
        if (charts.isEmpty()) onAllRemoved()
    }

    /** Stops charting every variable, also those still read for the first time. */
    fun removeAll() {
        if (charts.isEmpty()) return
        throttle.stop()
        job?.cancel()
        for (chart in charts) chart.slicing?.cancel()
        charts.clear()
        onChanged()
        onAllRemoved()
    }

    /** Reads the values of every chart again, for other shown rows; forgets what other variables came to. */
    fun refresh() {
        throttle.stop()
        summaries.keys.retainAll { (template, variable) -> chart(template, variable) != null }
        readValues(charts.toList())
    }

    /** Reads the values again within a second, for rows that keep coming while a file is tailed. */
    fun refreshSoon() {
        if (charts.isNotEmpty() && !throttle.isRunning) throttle.start()
    }

    /** Forgets the values, e.g. while the file is read again; the charts stay. */
    fun reset() {
        throttle.stop()
        job?.cancel()
        summaries.clear()
        for (chart in charts) {
            chart.slicing?.cancel()
            chart.values = null
            chart.slices = null
            chart.slicedValues = null
        }
        onChanged()
    }

    /**
     * [chart]'s values summed up in [count] slices of [width] nanoseconds from [from]. They are computed off the UI
     * thread; until they are, this returns the last slices computed, perhaps of other times, or null, and
     * [onChanged] is called once they are.
     */
    fun slices(chart: VariableChart, from: Long, width: Long, count: Int): VariableSlices? {
        val values = chart.values ?: return null
        val last = chart.slices
        val current = last != null && chart.slicedValues === values &&
            last.from == from && last.width == width && last.count == count
        if (current || chart.slicing?.isActive == true) return last
        chart.slicing = scope.launch {
            val slices = withContext(Dispatchers.Default) { values.slices(from, width, count) }
            if (chart.values === values) {
                chart.slices = slices
                chart.slicedValues = values
            }
            // Also when newer values came meanwhile: asked for while this ran, they are sliced on the next call.
            onChanged()
        }
        return last
    }

    private fun readValues(of: List<VariableChart>) {
        if (of.isEmpty()) return
        // A newer read covers the charts of the one it replaces.
        val pending = if (job?.isActive == true) charts.toList() else of
        job?.cancel()
        job = scope.launch {
            for ((template, group) in pending.groupBy { it.template }) {
                val variables = group.map { it.variable }.toIntArray()
                val values = try {
                    read(template, variables)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Cannot read the values of variables {} of “{}”", variables.toList(), template, e)
                    null
                }
                for ((i, chart) in group.withIndex()) {
                    // Closed while it was read.
                    if (chart !in charts) continue
                    val read = values?.get(i)
                    chart.values = read
                    if (read == null) continue
                    summaries[template to chart.variable] = VariableSummary(read)
                    if (chart.shown) continue
                    if (read.numbers == 0) {
                        charts -= chart
                        onNoNumbers(chart)
                    } else {
                        chart.shown = true
                        onShown(chart)
                    }
                }
                onChanged()
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(VariableCharts::class.java)
    }
}
