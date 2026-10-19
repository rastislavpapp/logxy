package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.template.SlotSlices
import io.github.rastislavpapp.logxy.core.template.SlotValues
import io.github.rastislavpapp.logxy.core.template.Templates
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

/** At most this many slots are charted at once: one per color of [CHART_COLORS]. */
const val MAX_SLOT_CHARTS = 6

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

/** Charts [slot] of [template] in [charts], or stops; tells the user, over [parent], when no more fit. */
fun toggleChart(parent: Component, charts: SlotCharts, template: String, slot: Int) {
    if (charts.toggle(template, slot)) return
    JOptionPane.showMessageDialog(
        parent,
        "At most $MAX_SLOT_CHARTS slots can be charted at once. Close one first.",
        "Chart Slot",
        JOptionPane.INFORMATION_MESSAGE,
    )
}

/** Whole numbers as they are, others to a few significant digits. */
fun formatSlotValue(value: Double): String = when {
    value.isNaN() -> "–"
    value == Math.rint(value) && abs(value) < 1e15 -> "%,d".format(value.toLong())
    abs(value) >= 100 -> "%,.0f".format(value)
    abs(value) >= 1 -> "%.2f".format(value)
    else -> "%.3g".format(value)
}

/** What a chart shows of each slice's numbers; [short] goes in front of its label. */
enum class SlotMetric(val title: String, val short: String, val of: (SlotSlices) -> DoubleArray) {
    AVERAGE("Average", "avg", { it.average }),
    P50("Median (p50)", "p50", { it.p50 }),
    P99("99th Percentile (p99)", "p99", { it.p99 }),
    MAX("Maximum", "max", { it.max }),
    SUM("Sum", "sum", { it.sum }),
    ;

    /** The value of a record that stands for [slice] of [slices]: the largest for a sum, which no record is near. */
    fun target(slices: SlotSlices, slice: Int): Double = if (this == SUM) slices.max[slice] else of(slices)[slice]
}

/** What the values of a slot came to over all shown rows of its template that have a time. */
class SlotSummary(val records: Int, val numbers: Int, val min: Double, val average: Double, val max: Double) {
    constructor(values: SlotValues) : this(values.size, values.numbers, values.min, values.average, values.max)
}

/** One slot of a template charted over time; see [SlotCharts]. */
class SlotChart internal constructor(val template: String, val slot: Int, val color: Color) {
    /** False until its values are first read: only a slot with numbers is shown. */
    var shown = false
        internal set

    var metric = SlotMetric.AVERAGE

    /** The shown rows' values; null until read. */
    internal var values: SlotValues? = null

    /** The last [SlotValues.slices] computed, and for which values; drawn by their own times until newer ones come. */
    var slices: SlotSlices? = null
        internal set
    var slicedValues: SlotValues? = null
        internal set
    internal var slicing: Job? = null

    val label: String = label(template, slot)

    companion object {
        /** [template] with [slot] marked, e.g. `done in {•} ms`. */
        fun label(template: String, slot: Int): String = template.split(Templates.PLACEHOLDER).let { parts ->
            buildString {
                for ((i, part) in parts.withIndex()) {
                    append(part)
                    if (i < parts.lastIndex) append(if (i == slot) "{•}" else Templates.PLACEHOLDER)
                }
            }
        }
    }
}

/**
 * The slots charted in one tab, in the order they were added, each with its own color. Values are read through
 * [read], one pass per template for all its charted slots, for the shown rows, and kept until [refresh] reads them
 * again; [onChanged] hears of charts added or removed and of new values or slices. A slot read for the first time
 * is shown only if it holds a number; else [onNoNumbers] hears of it. Use on the UI thread.
 */
class SlotCharts(
    private val scope: CoroutineScope,
    private val read: suspend (template: String, slots: IntArray) -> List<SlotValues>?,
) {
    private val charts = mutableListOf<SlotChart>()
    private var job: Job? = null
    private val throttle = Timer(REFRESH_MILLIS) { readValues(charts.toList()) }.apply { isRepeats = false }

    /** Of the slots read since the shown rows changed, charted or not. */
    private val summaries = HashMap<Pair<String, Int>, SlotSummary>()

    var onChanged: () -> Unit = {}
    var onNoNumbers: (SlotChart) -> Unit = {}

    /** The charts shown, without those still read for the first time. */
    val all: List<SlotChart> get() = charts.filter { it.shown }

    /** The chart of [slot] of [template], also while it is read for the first time; null if none. */
    fun chart(template: String, slot: Int): SlotChart? =
        charts.firstOrNull { it.template == template && it.slot == slot }

    /** What the slot's values came to, if read since the shown rows changed; else null. */
    fun summary(template: String, slot: Int): SlotSummary? = summaries[template to slot]

    /** Whether [slot] of [template] may hold numbers: it was not read, or found some. */
    fun canChart(template: String, slot: Int): Boolean =
        chart(template, slot) != null || summary(template, slot)?.numbers != 0

    /** Charts [slot] of [template], or stops charting it; false if [MAX_SLOT_CHARTS] are charted already. */
    fun toggle(template: String, slot: Int): Boolean {
        chart(template, slot)?.let {
            remove(it)
            return true
        }
        if (!canChart(template, slot)) return true
        if (charts.size >= MAX_SLOT_CHARTS) return false
        val color = CHART_COLORS.first { color -> charts.none { it.color == color } }
        val chart = SlotChart(template, slot, color)
        charts += chart
        onChanged()
        readValues(listOf(chart))
        return true
    }

    fun remove(chart: SlotChart) {
        chart.slicing?.cancel()
        charts -= chart
        onChanged()
    }

    /** Reads the values of every chart again, for other shown rows; forgets what other slots came to. */
    fun refresh() {
        throttle.stop()
        summaries.keys.retainAll { (template, slot) -> chart(template, slot) != null }
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
     * [chart]'s values in [count] slices of [width] nanoseconds from [from]. Computed off the UI thread: until they
     * are, the last ones computed (perhaps of other slices), or null; [onChanged] hears when they are.
     */
    fun slices(chart: SlotChart, from: Long, width: Long, count: Int): SlotSlices? {
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

    private fun readValues(of: List<SlotChart>) {
        if (of.isEmpty()) return
        // A newer read covers the charts of the one it replaces.
        val pending = if (job?.isActive == true) charts.toList() else of
        job?.cancel()
        job = scope.launch {
            for ((template, group) in pending.groupBy { it.template }) {
                val slots = group.map { it.slot }.toIntArray()
                val values = try {
                    read(template, slots)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Cannot read the values of slots {} of “{}”", slots.toList(), template, e)
                    null
                }
                for ((i, chart) in group.withIndex()) {
                    // Closed while it was read.
                    if (chart !in charts) continue
                    val read = values?.get(i)
                    chart.values = read
                    if (read == null) continue
                    summaries[template to chart.slot] = SlotSummary(read)
                    if (!chart.shown && read.numbers == 0) {
                        charts -= chart
                        onNoNumbers(chart)
                    } else {
                        chart.shown = true
                    }
                }
                onChanged()
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SlotCharts::class.java)
    }
}
