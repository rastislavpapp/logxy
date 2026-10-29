package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.formatTimeSpan
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.trace.Traces
import net.miginfocom.swing.MigLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val PLOT_PADDING = 6
private const val DOT_SIZE = 3f
private const val SELECTED_DOT_SIZE = 9f

/** Dots of traces closer than this many pixels are drawn as one. */
private const val CELL_SIZE = 2

/** A dot is found by the mouse when it is this many cells away at most. */
private const val HIT_CELLS = 2

/** The height of a bar of the side histogram, in pixels before scaling: the durations it counts are those dots. */
private const val BAND_HEIGHT = 6

/** The width of the side histogram's bars, and of the room right of them for the percentile labels. */
private const val BARS_WIDTH = 90
private const val PERCENTILE_LABELS_WIDTH = 30

/** The least space, in text heights, between two labels on the duration axis. */
private const val LABEL_SPACING = 1.6

/** The most labels on the duration axis over a narrow range, before those too close to the others are left out. */
private const val MOST_STEP_LABELS = 5

/** The most steps of the count axis under the side histogram. */
private const val MOST_COUNT_STEPS = 4

/** A percentile line is found by the mouse this many pixels away at most. */
private const val LINE_HIT = 3

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** The durations that label the axis over a wide range, each ten times the one before, in nanoseconds. */
private val DECADES = longArrayOf(
    1, 10, 100, 1_000, 10_000, 100_000, MILLI, 10 * MILLI, 100 * MILLI, SECOND, 10 * SECOND,
    MINUTE, 10 * MINUTE, HOUR, 10 * HOUR, DAY, 10 * DAY,
)

/** The steps between the labels of the axis over a narrow range, in nanoseconds: round amounts of each unit. */
private val STEPS: LongArray = buildList {
    var power = 1L
    while (power < SECOND) {
        add(power)
        add(2 * power)
        add(5 * power)
        power *= 10
    }
    addAll(listOf(SECOND, 2 * SECOND, 5 * SECOND, 10 * SECOND, 15 * SECOND, 30 * SECOND))
    addAll(listOf(MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE))
    addAll(listOf(HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY, 7 * DAY))
}.toLongArray()

private const val TITLE_TIP =
    "<html>Each dot is a trace: across, when it started; up, how long it took.<br>" +
        "The bars on the right count the traces at each duration.</html>"

private const val DURATION_TIP =
    "<html>How long each trace took, from its first record to its last.<br>" +
        "Durations grow by a factor up the axis, so a few very slow traces don't squash the rest.</html>"

private const val START_TIP = "When each trace's first record was logged"

private const val COUNT_TIP = "Count of traces per duration range. Hover a bar for its range and count."

/** [nanos], epoch nanoseconds, as a date and time in the system's zone; empty for [Records.NO_TIMESTAMP]. */
internal fun formatTraceTime(nanos: Long): String {
    if (nanos == Records.NO_TIMESTAMP) return ""
    val instant = Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND), Math.floorMod(nanos, NANOS_PER_SECOND))
    return TIME.format(instant.atZone(ZoneId.systemDefault()))
}

/** A round duration for an axis, without the parts that are 0: `1d 6h`, not `1d 6h 0min 0s`. */
internal fun axisLabel(nanos: Long): String =
    formatTimeSpan(nanos).split(' ').dropLastWhile { it == "0s" || it == "0min" || it == "0h" }.joinToString(" ")

/**
 * What a percentile of the trace durations means, for a tooltip: [label] such as `p90`, the share of traces
 * [percent] that took [nanos] or less, e.g. "90% of traces took this long or less, 10% took longer."
 */
internal fun percentileTip(label: String, percent: Int, nanos: Long): String {
    val meaning = if (percent == 50) {
        "Half the traces took this long or less, half took longer."
    } else {
        "$percent% of traces took this long or less, ${100 - percent}% took longer."
    }
    val name = if (percent == 50) "$label, the median" else label
    return "<html>$name · ${formatTimeSpan(nanos)}<br>$meaning</html>"
}

/**
 * The durations to label the axis from [shortest] to [longest] with, in nanoseconds, from the shortest: each ten
 * times the one before if at least two such fall in the range, else the multiples of the smallest round step that
 * gives at most [MOST_STEP_LABELS], so a narrow range such as `1d 5h` to `1d 11h` gets `1d 6h`, `1d 8h`, `1d 10h`.
 */
internal fun durationTicks(shortest: Long, longest: Long): LongArray {
    val decades = DECADES.filter { it in shortest..longest }
    if (decades.size >= 2) return decades.toLongArray()
    val step = STEPS.firstOrNull { longest / it - (shortest - 1) / it <= MOST_STEP_LABELS } ?: STEPS.last()
    val first = (shortest + step - 1) / step * step
    return generateSequence(first) { it + step }.takeWhile { it <= longest }.toList().toLongArray()
}

/** [n] traces in few characters for an axis: `800`, `2k`, `2.5k`, `1.2M`. */
internal fun shortCount(n: Int): String = when {
    n < 1_000 -> "$n"
    n < 1_000_000 -> "${decimal(n / 100, 10)}k"
    else -> "${decimal(n / 100_000, 10)}M"
}

/** [tenths] divided by [per], with one decimal only if it is not 0. */
private fun decimal(tenths: Int, per: Int) =
    if (tenths % per == 0) "${tenths / per}" else "${tenths / per}.${tenths % per}"

private fun withAlpha(color: Color, alpha: Float) =
    Color(color.red, color.green, color.blue, (alpha.coerceIn(0f, 1f) * 255).toInt())

private val mutedText: Color get() = UIManager.getColor("Label.disabledForeground")

/**
 * The extent of the traces of a [Traces] on the chart: [from] the earliest start to [to] the latest, and the
 * durations from [shortest] to [longest], leaving out those of 0, which sit on the bottom edge.
 */
class TraceExtent(val from: Long, val to: Long, val shortest: Long, val longest: Long) {
    companion object {
        /** The extent of the traces with times; null if none has one. */
        fun of(traces: Traces): TraceExtent? {
            var from = Long.MAX_VALUE
            var to = Long.MIN_VALUE
            var shortest = Long.MAX_VALUE
            var longest = 0L
            var timed = false
            for (trace in 0..<traces.size) {
                val duration = traces.duration(trace)
                if (duration == Traces.NO_DURATION) continue
                timed = true
                val start = traces.start(trace)
                from = minOf(from, start)
                to = maxOf(to, start)
                if (duration > 0) shortest = minOf(shortest, duration)
                longest = maxOf(longest, duration)
            }
            if (!timed) return null
            // A lone duration, or none above 0, still needs a span to put on the axis.
            if (shortest == Long.MAX_VALUE) shortest = 1
            if (longest <= shortest) longest = shortest * 2
            return TraceExtent(from, to, shortest, longest)
        }
    }
}

/** A dashed line across the chart at a percentile of the durations, such as `p90`. */
private class Percentile(val label: String, val percent: Int, val nanos: Long) {
    val tip get() = percentileTip(label, percent, nanos)
}

/**
 * What the parts of a [TraceDurationChart] share: the traces, where a duration is up the plot, the bar of the side
 * histogram the mouse is on (in either part), and the selected traces. All parts are as tall as each other, so a
 * duration is at the same height in each.
 */
private class DurationChart {
    var traces: Traces? = null
        private set
    var extent: TraceExtent? = null
        private set
    var percentiles: List<Percentile> = emptyList()
        private set

    /** The bar of the side histogram the mouse is on, or the one of the dot it is on; -1 for none. */
    var hoveredBand = -1
        set(value) {
            if (value == field) return
            field = value
            repaintParts()
        }

    /** The selected traces, drawn larger than the others. */
    var selected = IntArray(0)
        set(value) {
            field = value
            repaintParts()
        }

    /** Repainted when what they show changed; laid out again too, as the duration labels' width may change. */
    val parts = ArrayList<JComponent>()

    /** Counts of the traces per bar as last counted, for plots [countedHeight] pixels tall. */
    private var counts = IntArray(0)
    private var countedHeight = -1

    fun show(traces: Traces?, extent: TraceExtent?) {
        this.traces = traces
        this.extent = extent
        val statistics = traces?.statistics
        percentiles = if (statistics == null || extent == null) {
            emptyList()
        } else {
            listOf(
                Percentile("p50", 50, statistics.median),
                Percentile("p90", 90, statistics.p90),
                Percentile("p99", 99, statistics.p99),
            )
        }
        countedHeight = -1
        for (part in parts) part.revalidate()
        repaintParts()
    }

    private fun repaintParts() {
        for (part in parts) part.repaint()
    }

    fun top() = look.scale(PLOT_PADDING)

    fun bottom(height: Int) = height - look.scale(PLOT_PADDING)

    fun bandHeight() = look.scale(BAND_HEIGHT)

    /** Where [duration] is up the plot, from 0 at the bottom to 1 at the top. */
    fun fraction(duration: Long, extent: TraceExtent): Double {
        if (duration < extent.shortest) return 0.0
        val low = ln(extent.shortest.toDouble())
        return (ln(duration.toDouble()) - low) / (ln(extent.longest.toDouble()) - low)
    }

    /** The duration [fraction] of the way up the plot. */
    fun durationAt(fraction: Double, extent: TraceExtent): Long {
        val low = ln(extent.shortest.toDouble())
        return Math.round(exp(low + fraction * (ln(extent.longest.toDouble()) - low)))
    }

    /** The y of [duration] in a part [height] pixels tall. */
    fun y(duration: Long, extent: TraceExtent, height: Int): Double {
        val span = bottom(height) - top()
        return bottom(height) - fraction(duration, extent) * span
    }

    /** The bar of the side histogram at [y] in a part [height] pixels tall; -1 above or below the plot. */
    fun bandAt(y: Int, height: Int): Int {
        if (y !in top()..bottom(height)) return -1
        return ((bottom(height) - y) / bandHeight()).coerceAtMost(bands(height) - 1)
    }

    fun bands(height: Int) = maxOf(1, (bottom(height) - top() + bandHeight() - 1) / bandHeight())

    /** The traces with times in each bar of the side histogram, from the bottom, in a part [height] pixels tall. */
    fun counts(height: Int): IntArray {
        if (height == countedHeight) return counts
        val traces = traces
        val extent = extent
        counts = IntArray(bands(height))
        countedHeight = height
        if (traces == null || extent == null) return counts
        val span = (bottom(height) - top()).toDouble()
        for (trace in 0..<traces.size) {
            val duration = traces.duration(trace)
            if (duration == Traces.NO_DURATION) continue
            val band = (fraction(duration, extent) * span / bandHeight()).toInt()
            counts[band.coerceIn(0, counts.size - 1)]++
        }
        return counts
    }

    /** The durations of [band]'s bars, as "from – to", or "up to" for the bottom bar, which has those of 0 too. */
    fun bandRange(band: Int, height: Int, extent: TraceExtent): String {
        val span = (bottom(height) - top()).toDouble()
        val to = durationAt(minOf(1.0, (band + 1) * bandHeight() / span), extent)
        if (band == 0) return "up to ${formatTimeSpan(to)}"
        return "${formatTimeSpan(durationAt(band * bandHeight() / span, extent))} – ${formatTimeSpan(to)}"
    }

    /** The labelled durations and their y in a part [height] pixels tall, from the bottom, leaving room between. */
    fun ticks(height: Int, lineHeight: Int): List<Pair<Long, Double>> {
        val extent = extent ?: return emptyList()
        val shown = ArrayList<Pair<Long, Double>>()
        var lastY = Double.POSITIVE_INFINITY
        for (duration in durationTicks(extent.shortest, extent.longest)) {
            val y = y(duration, extent, height)
            if (lastY - y < lineHeight * LABEL_SPACING) continue
            shown += duration to y
            lastY = y
        }
        return shown
    }

    /** The percentile whose line is at [y] give or take [LINE_HIT] pixels, in a part [height] pixels tall. */
    fun percentileAt(y: Int, height: Int): Percentile? {
        val extent = extent ?: return null
        return percentiles.firstOrNull { abs(y(it.nanos, extent, height) - y) <= look.scale(LINE_HIT) }
    }

    fun dashed(): BasicStroke {
        val dash = floatArrayOf(look.scale(4f), look.scale(3f))
        return BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
    }

    /** The faint lines across a part at the labelled durations. */
    fun paintGrid(g2: Graphics2D, width: Int, height: Int) {
        g2.color = withAlpha(look.border, 0.5f)
        for ((_, y) in ticks(height, g2.fontMetrics.height)) g2.fillRect(0, y.toInt(), width, 1)
    }

    /** The percentile lines across [width] pixels of a part. */
    fun paintPercentiles(g2: Graphics2D, width: Int, height: Int) {
        val extent = extent ?: return
        val stroke = g2.stroke
        g2.stroke = dashed()
        g2.color = look.warning
        for (percentile in percentiles) {
            val y = y(percentile.nanos, extent, height).toInt()
            g2.drawLine(0, y, width, y)
        }
        g2.stroke = stroke
    }
}

/**
 * How long the traces of a [Traces] took: a dot per trace with times, its start across and its duration up, and on
 * the right a histogram of the durations on the same axis, so a bar counts the dots beside it. Dashed lines mark
 * the p50, p90 and p99 of the durations. Durations grow by a constant factor up the axis, so a few far slower
 * traces do not flatten the rest. Axis titles and labels explain themselves in tooltips; [onClick] and
 * [onDoubleClick] are told the trace of a clicked dot.
 */
class TraceDurationChart(
    onClick: (trace: Int, extend: Boolean) -> Unit,
    onDoubleClick: (trace: Int) -> Unit,
) : JPanel(MigLayout("insets 4 4 0 8, gap 0", "[pref!]4[grow,fill][pref!]", "[pref!]2[grow,fill][pref!]")) {

    private val chart = DurationChart()
    private val scatter = TraceScatter(chart, onClick, onDoubleClick)
    private val histogram = SideHistogram(chart)

    init {
        val title = JLabel("Trace durations").apply {
            font = font.deriveFont(Font.BOLD)
            toolTipText = TITLE_TIP
        }
        add(axisTitle("duration", DURATION_TIP, SwingConstants.TRAILING))
        add(title)
        add(axisTitle("count", COUNT_TIP, SwingConstants.LEADING), "wrap")
        add(DurationLabels(chart))
        add(scatter)
        add(histogram, "wrap")
        add(axisTitle("start time", START_TIP, SwingConstants.TRAILING), "top")
        add(TimeAxis(scatter::timeline).also { chart.parts += it })
        add(CountAxis(chart, histogram))
    }

    override fun updateUI() {
        super.updateUI()
        background = UIManager.getColor("Table.background")
    }

    private fun axisTitle(text: String, tip: String, alignment: Int) = JLabel(text, alignment).apply {
        font = look.smallFont
        foreground = mutedText
        toolTipText = tip
    }

    /** Shows [traces], whose [extent] is found off the UI thread; null for no traces. */
    fun show(traces: Traces?, extent: TraceExtent?) = chart.show(traces, extent)

    /** The selected traces, which the chart draws larger. */
    var selected: IntArray
        get() = chart.selected
        set(value) {
            chart.selected = value
        }
}

/** The labels of the duration axis, right-aligned at their durations. */
private class DurationLabels(private val chart: DurationChart) : JComponent() {

    init {
        chart.parts += this
        toolTipText = DURATION_TIP
    }

    override fun getPreferredSize(): Dimension {
        val extent = chart.extent ?: return Dimension(0, 0)
        val metrics = getFontMetrics(look.smallFont)
        val widest = durationTicks(extent.shortest, extent.longest).maxOfOrNull { metrics.stringWidth(axisLabel(it)) }
        return Dimension(widest ?: 0, 0)
    }

    override fun getMinimumSize() = Dimension(preferredSize.width, 0)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.font = look.smallFont
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = mutedText
            val metrics = g2.fontMetrics
            for ((duration, y) in chart.ticks(height, metrics.height)) {
                val label = axisLabel(duration)
                g2.drawString(label, width - metrics.stringWidth(label), y.toInt() + metrics.ascent / 2 - 1)
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * One dot per trace with times, its start across and its duration up. Dots less than [CELL_SIZE] pixels apart are
 * one dot, of the slowest of those traces; selected traces are drawn larger over the others, which then fade.
 * Hovering a dot shows its trace, and the bar of the side histogram it is counted in; [onClick] and [onDoubleClick]
 * are told the trace. The start times are those of [timeline], which a [TimeAxis] under the chart paints too.
 */
private class TraceScatter(
    private val chart: DurationChart,
    private val onClick: (trace: Int, extend: Boolean) -> Unit,
    private val onDoubleClick: (trace: Int) -> Unit,
) : JComponent() {

    /** The slowest trace in each cell of the grid as last laid out, or -1; and how many traces fall in it. */
    private var slowest = IntArray(0)
    private var crowd = IntArray(0)
    private var columns = 0
    private var rows = 0

    /** The traces and size [slowest] was laid out for. */
    private var gridTraces: Traces? = null
    private var gridExtent: TraceExtent? = null
    private var gridWidth = 0
    private var gridHeight = 0

    /** The dots as last drawn, and what they were drawn for (see [dots]). */
    private var dotsImage: BufferedImage? = null
    private var dotsKey: List<Any> = emptyList()

    init {
        chart.parts += this
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val trace = traceAt(e.x, e.y)
                if (trace < 0) return
                if (e.clickCount == 1) {
                    onClick(trace, e.modifiersEx and InputEvent.CTRL_DOWN_MASK != 0)
                } else {
                    onDoubleClick(trace)
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                val selected = selectedAt(e.x, e.y)
                val cell = if (selected >= 0) -1 else cellAt(e.x, e.y)
                val onDot = selected >= 0 || cell >= 0
                cursor = if (onDot) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
                chart.hoveredBand = when {
                    selected >= 0 -> bandOf(selected)
                    cell >= 0 -> chart.bandAt(cellY(cell).toInt(), height)
                    else -> -1
                }
            }

            override fun mouseExited(e: MouseEvent) {
                chart.hoveredBand = -1
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private val cell get() = look.scale(CELL_SIZE)

    override fun getPreferredSize() = Dimension(0, look.scale(HISTOGRAM_HEIGHT * 2))

    override fun getMinimumSize() = Dimension(0, 0)

    /**
     * The times the dots are placed at, for the axis under the chart, at [width] pixels: one slice from the earliest
     * start to just past the latest, so the latest start is inside it.
     */
    fun timeline(width: Int): Timeline? {
        val extent = chart.extent ?: return null
        return Timeline(extent.from, extent.to - extent.from + 1, 1, width)
    }

    private fun layOut(traces: Traces, extent: TraceExtent) {
        gridTraces = traces
        gridExtent = extent
        gridWidth = width
        gridHeight = height
        val timeline = timeline(width) ?: return
        val top = chart.top()
        columns = width / cell + 1
        rows = (chart.bottom(height) - top) / cell + 1
        slowest = IntArray(columns * rows) { -1 }
        crowd = IntArray(columns * rows)
        for (trace in 0..<traces.size) {
            val duration = traces.duration(trace)
            if (duration == Traces.NO_DURATION) continue
            val x = timeline.x(traces.start(trace)).toInt().coerceAtMost(width - 1).coerceAtLeast(0)
            val y = chart.y(duration, extent, height).roundToInt() - top
            val index = (y / cell).coerceIn(0, rows - 1) * columns + x / cell
            crowd[index]++
            val other = slowest[index]
            if (other < 0 || duration > traces.duration(other)) slowest[index] = trace
        }
    }

    private fun prepare() {
        val traces = chart.traces ?: return
        val extent = chart.extent ?: return
        val changed = traces !== gridTraces || extent !== gridExtent
        if (changed || width != gridWidth || height != gridHeight) layOut(traces, extent)
    }

    private fun cellX(index: Int) = (index % columns) * cell + cell / 2.0

    private fun cellY(index: Int) = chart.top() + (index / columns) * cell + cell / 2.0

    /** The cell of the point ([x], [y]), or the nearest with a trace within [HIT_CELLS]; -1 if none. */
    private fun cellAt(x: Int, y: Int): Int {
        prepare()
        if (slowest.isEmpty() || chart.traces == null) return -1
        val column = x / cell
        val row = (y - chart.top()) / cell
        var best = -1
        var distance = Int.MAX_VALUE
        for (dy in -HIT_CELLS..HIT_CELLS) {
            for (dx in -HIT_CELLS..HIT_CELLS) {
                val c = column + dx
                val r = row + dy
                if (c !in 0..<columns || r !in 0..<rows || slowest[r * columns + c] < 0) continue
                if (dx * dx + dy * dy < distance) {
                    distance = dx * dx + dy * dy
                    best = r * columns + c
                }
            }
        }
        return best
    }

    /** The trace at ([x], [y]): a selected one whose larger dot is there first, as it is drawn over the others. */
    private fun traceAt(x: Int, y: Int): Int {
        val selected = selectedAt(x, y)
        if (selected >= 0) return selected
        val cell = cellAt(x, y)
        return if (cell < 0) -1 else slowest[cell]
    }

    /** The bar of the side histogram that counts [trace]. */
    private fun bandOf(trace: Int): Int {
        val traces = chart.traces ?: return -1
        val extent = chart.extent ?: return -1
        return chart.bandAt(chart.y(traces.duration(trace), extent, height).toInt(), height)
    }

    /** The selected trace whose dot is nearest to ([x], [y]) and covers it; -1 if none does. */
    private fun selectedAt(x: Int, y: Int): Int {
        val traces = chart.traces ?: return -1
        val extent = chart.extent ?: return -1
        val timeline = timeline(width) ?: return -1
        val reach = look.scale(SELECTED_DOT_SIZE) / 2.0 + look.scale(CELL_SIZE)
        var best = -1
        var distance = reach * reach
        for (trace in chart.selected) {
            if (trace >= traces.size || traces.duration(trace) == Traces.NO_DURATION) continue
            val dx = timeline.x(traces.start(trace)).coerceIn(0.0, width - 1.0) - x
            val dy = chart.y(traces.duration(trace), extent, height) - y
            if (dx * dx + dy * dy <= distance) {
                distance = dx * dx + dy * dy
                best = trace
            }
        }
        return best
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val traces = chart.traces ?: return null
        val selected = selectedAt(e.x, e.y)
        if (selected >= 0) return traceTip(traces, selected, "")
        val cell = cellAt(e.x, e.y)
        if (cell < 0) return chart.percentileAt(e.y, height)?.tip
        val more = crowd[cell] - 1
        val rest = if (more > 0) "<br><small>and ${count(more, "other trace")} here</small>" else ""
        return traceTip(traces, slowest[cell], rest)
    }

    private fun traceTip(traces: Traces, trace: Int, rest: String) =
        "<html>${escapeHtml(traces.id(trace))}<br>Start ${formatTraceTime(traces.start(trace))}" +
            "<br>Duration ${formatTimeSpan(traces.duration(trace))}$rest</html>"

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIManager.getColor("Table.background")
            g2.fillRect(0, 0, width, height)
            val traces = chart.traces ?: return
            val extent = chart.extent ?: return
            prepare()
            g2.font = look.smallFont
            chart.paintGrid(g2, width, height)
            paintHoveredBand(g2)
            chart.paintPercentiles(g2, width, height)
            g2.drawImage(dots(g2), 0, 0, width, height, null)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintSelected(g2, traces, extent)
        } finally {
            g2.dispose()
        }
    }

    /** The durations the hovered bar of the side histogram counts, as a band across the plot. */
    private fun paintHoveredBand(g2: Graphics2D) {
        val band = chart.hoveredBand
        if (band < 0) return
        val top = maxOf(chart.top(), chart.bottom(height) - (band + 1) * chart.bandHeight())
        g2.color = withAlpha(look.accent, 0.15f)
        g2.fillRect(0, top, width, chart.bottom(height) - band * chart.bandHeight() - top)
    }

    /**
     * The dots as an image at the screen's scale, drawn again only when the grid, the size or whether traces are
     * selected changed: tens of thousands of dots take long to draw, and the chart is repainted on each hover.
     */
    private fun dots(g2: Graphics2D): BufferedImage {
        val scaleX = g2.transform.scaleX
        val scaleY = g2.transform.scaleY
        val key = listOf(slowest, width, height, scaleX, scaleY, chart.selected.isEmpty())
        dotsImage?.let { if (key == dotsKey) return it }
        val image = BufferedImage(
            maxOf(1, ceil(width * scaleX).toInt()),
            maxOf(1, ceil(height * scaleY).toInt()),
            BufferedImage.TYPE_INT_ARGB_PRE,
        )
        val ig = image.createGraphics()
        try {
            ig.scale(scaleX, scaleY)
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintDots(ig)
        } finally {
            ig.dispose()
        }
        dotsImage = image
        dotsKey = key
        return image
    }

    /**
     * A dot per cell, the more opaque the more traces it holds, so thousands of traces show where they crowd
     * rather than one solid area; all fainter while some are selected.
     */
    private fun paintDots(g2: Graphics2D) {
        val size = look.scale(DOT_SIZE).toDouble()
        val most = ln(1.0 + (crowd.maxOrNull() ?: 0))
        val strength = if (chart.selected.isEmpty()) 1f else 0.4f
        for (index in slowest.indices) {
            if (slowest[index] < 0) continue
            val density = if (most > 0) ln(1.0 + crowd[index]) / most else 1.0
            g2.color = withAlpha(look.accent, strength * (0.25f + 0.65f * density.toFloat()))
            g2.fill(Ellipse2D.Double(cellX(index) - size / 2, cellY(index) - size / 2, size, size))
        }
    }

    /** The selected traces' dots, larger and outlined, each where it is, not only the slowest of its cell. */
    private fun paintSelected(g2: Graphics2D, traces: Traces, extent: TraceExtent) {
        val timeline = timeline(width) ?: return
        val size = look.scale(SELECTED_DOT_SIZE).toDouble()
        for (trace in chart.selected) {
            if (trace >= traces.size) continue
            val duration = traces.duration(trace)
            if (duration == Traces.NO_DURATION) continue
            val x = timeline.x(traces.start(trace)).coerceIn(0.0, width - 1.0)
            val y = chart.y(duration, extent, height)
            val dot = Ellipse2D.Double(x - size / 2, y - size / 2, size, size)
            g2.color = look.accent
            g2.fill(dot)
            g2.color = UIManager.getColor("Table.foreground")
            g2.draw(dot)
        }
    }
}

/**
 * How many traces took each duration, as bars growing to the right on the scatter's duration axis, with the
 * percentile lines carried on and labelled on the right. Hovering a bar shows its range and count, and marks the
 * durations it counts across the scatter.
 */
private class SideHistogram(private val chart: DurationChart) : JComponent() {

    init {
        chart.parts += this
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val onBars = chart.traces != null && e.x < barsWidth
                chart.hoveredBand = if (onBars) chart.bandAt(e.y, height) else -1
            }

            override fun mouseExited(e: MouseEvent) {
                chart.hoveredBand = -1
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    val barsWidth get() = look.scale(BARS_WIDTH)

    override fun getPreferredSize() = Dimension(barsWidth + look.scale(PERCENTILE_LABELS_WIDTH), 0)

    override fun getMinimumSize() = Dimension(preferredSize.width, 0)

    /** The count at the right edge of the bars, a round number at or above the most in a bar; and its step. */
    fun countScale(): Pair<Int, Int> {
        val most = chart.counts(height).maxOrNull() ?: 0
        if (most == 0) return 1 to 1
        var power = 1
        while (true) {
            for (factor in intArrayOf(1, 2, 5)) {
                val step = factor * power
                val steps = (most + step - 1) / step
                if (steps <= MOST_COUNT_STEPS) return steps * step to step
            }
            power *= 10
        }
    }

    /** The x of [count] along the bars, when [top] is at their right edge. */
    fun xOf(count: Int, top: Int) = (count.toLong() * (barsWidth - 1) / top).toInt()

    override fun getToolTipText(e: MouseEvent): String? {
        val extent = chart.extent ?: return null
        if (e.x >= barsWidth) return percentileLabelAt(e.y, extent)?.tip
        val band = chart.bandAt(e.y, height)
        if (band < 0) return null
        val count = chart.counts(height)[band]
        return "<html>${chart.bandRange(band, height, extent)}<br>Count: ${"%,d".format(count)}</html>"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIManager.getColor("Table.background")
            g2.fillRect(0, 0, width, height)
            val extent = chart.extent ?: return
            g2.font = look.smallFont
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val (top, step) = countScale()
            g2.color = withAlpha(look.border, 0.5f)
            for (count in 0..top step step) g2.fillRect(xOf(count, top), 0, 1, height)
            chart.paintGrid(g2, barsWidth, height)
            paintBars(g2, top)
            chart.paintPercentiles(g2, barsWidth, height)
            paintPercentileLabels(g2, extent)
        } finally {
            g2.dispose()
        }
    }

    private fun paintBars(g2: Graphics2D, top: Int) {
        val counts = chart.counts(height)
        val bottom = chart.bottom(height)
        val bandHeight = chart.bandHeight()
        for ((band, count) in counts.withIndex()) {
            if (count == 0) continue
            val y = maxOf(chart.top(), bottom - (band + 1) * bandHeight)
            val length = maxOf(1, (count.toLong() * barsWidth / top).toInt())
            g2.color = withAlpha(look.accent, if (band == chart.hoveredBand) 1f else 0.6f)
            g2.fillRect(0, y, length, bottom - band * bandHeight - y - 1)
        }
    }

    /**
     * Where `p50`, `p90` and `p99` go right of the bars: at their lines, but a label closer than [lineHeight] to the
     * one below moves up, and the labels move down together if that takes the top one off the plot.
     */
    private fun labelYs(extent: TraceExtent, lineHeight: Int): DoubleArray {
        val percentiles = chart.percentiles
        val ys = DoubleArray(percentiles.size) { chart.y(percentiles[it].nanos, extent, height) }
        for (i in 1..<ys.size) ys[i] = minOf(ys[i], ys[i - 1] - lineHeight)
        val topmost = chart.top() + lineHeight / 2.0
        for (i in ys.indices.reversed()) {
            val below = if (i == ys.lastIndex) topmost else ys[i + 1] + lineHeight
            ys[i] = maxOf(ys[i], below)
        }
        return ys
    }

    private fun paintPercentileLabels(g2: Graphics2D, extent: TraceExtent) {
        val metrics = g2.fontMetrics
        g2.color = look.warning
        val ys = labelYs(extent, metrics.height)
        for ((i, percentile) in chart.percentiles.withIndex()) {
            g2.drawString(percentile.label, barsWidth + look.scale(4), ys[i].toInt() + metrics.ascent / 2 - 1)
        }
    }

    /** The percentile whose label is at [y], or whose line is when the labels moved away from it. */
    private fun percentileLabelAt(y: Int, extent: TraceExtent): Percentile? {
        val lineHeight = getFontMetrics(look.smallFont).height
        val ys = labelYs(extent, lineHeight)
        val label = ys.indices.firstOrNull { abs(ys[it] - y) <= lineHeight / 2.0 }
        return if (label != null) chart.percentiles[label] else chart.percentileAt(y, height)
    }
}

/** The counts under the side [histogram]'s bars, at its steps after 0, where they fit. */
private class CountAxis(chart: DurationChart, private val histogram: SideHistogram) : JComponent() {

    init {
        chart.parts += this
        toolTipText = COUNT_TIP
    }

    override fun getPreferredSize(): Dimension {
        val labels = getFontMetrics(look.smallFont).height
        return Dimension(histogram.preferredSize.width, look.scale(PLOT_PADDING) + labels)
    }

    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.font = look.smallFont
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = mutedText
            val metrics = g2.fontMetrics
            val (top, step) = histogram.countScale()
            var next = 0
            // Not 0, which the bars plainly start from, and whose label would run into the time axis's last one.
            for (count in step..top step step) {
                val label = shortCount(count)
                val x = histogram.xOf(count, top)
                val labelX = (x - metrics.stringWidth(label) / 2).coerceAtLeast(0)
                if (labelX < next) continue
                g2.drawString(label, labelX, look.scale(2) + metrics.ascent)
                next = labelX + metrics.stringWidth(label) + look.scale(6)
            }
        } finally {
            g2.dispose()
        }
    }
}
