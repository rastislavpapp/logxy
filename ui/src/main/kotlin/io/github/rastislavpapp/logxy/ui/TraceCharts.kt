package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.formatTimeSpan
import io.github.rastislavpapp.logxy.core.log.Records
import io.github.rastislavpapp.logxy.core.trace.DurationHistogram
import io.github.rastislavpapp.logxy.core.trace.Traces
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.ln
import kotlin.math.roundToInt

/** The bins of the duration histogram, however wide it is. */
const val DURATION_BINS = 48

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val PLOT_PADDING = 4
private const val DOT_SIZE = 5f

/** Dots of traces closer than this many pixels are drawn as one. */
private const val CELL_SIZE = 2

/** A dot is found by the mouse when it is this many cells away at most. */
private const val HIT_CELLS = 2

/** The least space, in text heights, between two labels on the duration axis. */
private const val LABEL_SPACING = 1.6

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** The round durations that label the duration axis, in nanoseconds. */
private val ROUND_DURATIONS = longArrayOf(
    1, 10, 100, 1_000, 10_000, 100_000, MILLI, 10 * MILLI, 100 * MILLI, SECOND, 10 * SECOND,
    MINUTE, 10 * MINUTE, HOUR, 10 * HOUR, DAY,
)

/** [nanos], epoch nanoseconds, as a date and time in the system's zone; empty for [Records.NO_TIMESTAMP]. */
internal fun formatTraceTime(nanos: Long): String {
    if (nanos == Records.NO_TIMESTAMP) return ""
    val instant = Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND), Math.floorMod(nanos, NANOS_PER_SECOND))
    return TIME.format(instant.atZone(ZoneId.systemDefault()))
}

/** A duration for an axis, without the zero seconds [formatTimeSpan] adds to minutes: `1 min`, not `1 min 0 s`. */
private fun axisLabel(nanos: Long): String = formatTimeSpan(nanos).removeSuffix(" 0 s").removeSuffix(" 0 min")

private fun withAlpha(color: Color, alpha: Float) =
    Color(color.red, color.green, color.blue, (alpha.coerceIn(0f, 1f) * 255).toInt())

/**
 * How many traces took how long, as bars over [histogram]'s bins, which grow in duration by a constant factor so a
 * few far slower traces leave the rest their shape. The most traces in a bin labels the left edge, the durations the
 * bottom. Hovering a bar shows its duration range and count.
 */
class DurationHistogramChart : JComponent() {

    var histogram: DurationHistogram? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        toolTipText = ""
    }

    private val labelFont get() = look.smallFont
    private val padding get() = look.scale(PLOT_PADDING)
    private val axisHeight get() = getFontMetrics(labelFont).height + padding

    override fun getPreferredSize() = Dimension(0, look.scale(HISTOGRAM_HEIGHT * 2))

    override fun getMinimumSize() = Dimension(0, 0)

    private fun binAt(x: Int): Int? {
        val histogram = histogram ?: return null
        if (x !in 0..<width) return null
        return (x * histogram.counts.size / width).takeIf { it < histogram.counts.size }
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val histogram = histogram ?: return null
        val bin = binAt(e.x) ?: return null
        val range = when {
            bin == 0 && histogram.hasZeroBin -> "0"
            else -> "${axisLabel(histogram.from(bin))} – ${axisLabel(histogram.to(bin))}"
        }
        return "<html>$range<br>${count(histogram.counts[bin], "trace")}</html>"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIManager.getColor("Table.background")
            g2.fillRect(0, 0, width, height)
            g2.font = labelFont
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val histogram = histogram ?: return
            paintBars(g2, histogram)
            paintLabels(g2, histogram)
        } finally {
            g2.dispose()
        }
    }

    private fun paintBars(g2: Graphics2D, histogram: DurationHistogram) {
        val most = histogram.counts.max()
        val top = padding
        val bottom = height - axisHeight
        g2.color = look.border
        g2.fillRect(0, bottom, width, 1)
        g2.color = look.accent
        val bins = histogram.counts.size
        for (bin in 0..<bins) {
            val count = histogram.counts[bin]
            if (count == 0) continue
            val left = bin * width / bins
            val right = (bin + 1) * width / bins
            val barHeight = maxOf(1, ((bottom - top) * count.toLong() / most).toInt())
            g2.fillRect(left, bottom - barHeight, maxOf(1, right - left - 1), barHeight)
        }
    }

    /** The most traces in a bin at the top left, and durations at bin edges along the bottom, where they fit. */
    private fun paintLabels(g2: Graphics2D, histogram: DurationHistogram) {
        val metrics = g2.fontMetrics
        g2.color = UIManager.getColor("Label.disabledForeground")
        g2.drawString(histogram.counts.max().toString(), look.scale(4), padding + metrics.ascent)
        val baseline = height - padding / 2 - metrics.descent
        val bins = histogram.counts.size
        var next = 0
        for (edge in 0..bins) {
            val x = edge * width / bins
            val label = axisLabel(histogram.edgeAt(edge))
            val labelWidth = metrics.stringWidth(label)
            // A chart narrower than the label puts it at the left edge.
            val labelX = (x - labelWidth / 2).coerceAtMost(width - labelWidth).coerceAtLeast(0)
            if (labelX < next) continue
            g2.drawString(label, labelX, baseline)
            next = labelX + labelWidth + look.scale(12)
        }
    }

    private fun DurationHistogram.edgeAt(edge: Int) = if (edge < counts.size) from(edge) else to(counts.size - 1)
}

/**
 * The extent of the traces of a [Traces] on the scatter: [from] the earliest start to [to] the latest, and the
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
            return TraceExtent(from, to, shortest, maxOf(longest, shortest * 2))
        }
    }
}

/**
 * One dot per trace of [traces] with times, its start across and its duration up, on a scale that grows by a constant
 * factor so a few far slower traces do not flatten the rest. Dots less than [CELL_SIZE] pixels apart are one dot, of
 * the slowest of those traces. Hovering a dot shows its trace; [onClick] and [onDoubleClick] are told the trace.
 * The start times are those of [timeline], which a [TimeAxis] under the chart paints too.
 */
class TraceScatterChart(
    private val onClick: (trace: Int, extend: Boolean) -> Unit,
    private val onDoubleClick: (trace: Int) -> Unit,
) : JComponent() {

    private var traces: Traces? = null
    private var extent: TraceExtent? = null

    /** The slowest trace in each cell of the grid as last laid out, or -1; and how many traces fall in it. */
    private var slowest = IntArray(0)
    private var crowd = IntArray(0)
    private var columns = 0
    private var rows = 0

    /** Whether [slowest] needs laying out again, as the traces or the size changed. */
    private var dirty = true
    private var gridWidth = 0
    private var gridHeight = 0

    /** Shows [traces], whose [extent] is given as it is found off the UI thread; null for no traces. */
    fun show(traces: Traces?, extent: TraceExtent?) {
        this.traces = traces
        this.extent = extent
        dirty = true
        repaint()
    }

    init {
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
                cursor = if (traceAt(e.x, e.y) >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private val labelFont get() = look.smallFont
    private val plotTop get() = look.scale(PLOT_PADDING)
    private val plotBottom get() = height - look.scale(PLOT_PADDING)
    private val cell get() = look.scale(CELL_SIZE)

    override fun getPreferredSize() = Dimension(0, look.scale(HISTOGRAM_HEIGHT * 2))

    override fun getMinimumSize() = Dimension(0, 0)

    /**
     * The times the dots are placed at, for the axis under the chart, at [width] pixels: one slice from the earliest
     * start to just past the latest, so the latest start is inside it.
     */
    fun timeline(width: Int): Timeline? {
        val extent = extent ?: return null
        return Timeline(extent.from, extent.to - extent.from + 1, 1, width)
    }

    /** Where [duration] is up the plot, from 0 at the bottom to 1 at the top. */
    private fun heightOf(duration: Long, extent: TraceExtent): Double {
        if (duration < extent.shortest) return 0.0
        val low = ln(extent.shortest.toDouble())
        return (ln(duration.toDouble()) - low) / (ln(extent.longest.toDouble()) - low)
    }

    private fun layOut(traces: Traces, extent: TraceExtent) {
        gridWidth = width
        gridHeight = height
        dirty = false
        val timeline = timeline(width) ?: return
        columns = width / cell + 1
        rows = (plotBottom - plotTop) / cell + 1
        slowest = IntArray(columns * rows) { -1 }
        crowd = IntArray(columns * rows)
        val span = (plotBottom - plotTop).toDouble()
        for (trace in 0..<traces.size) {
            val duration = traces.duration(trace)
            if (duration == Traces.NO_DURATION) continue
            val x = timeline.x(traces.start(trace)).toInt().coerceAtMost(width - 1).coerceAtLeast(0)
            val y = (plotBottom - heightOf(duration, extent) * span).roundToInt() - plotTop
            val index = (y / cell).coerceIn(0, rows - 1) * columns + x / cell
            crowd[index]++
            val other = slowest[index]
            if (other < 0 || duration > traces.duration(other)) slowest[index] = trace
        }
    }

    private fun prepare() {
        val traces = traces ?: return
        val extent = extent ?: return
        if (dirty || width != gridWidth || height != gridHeight) layOut(traces, extent)
    }

    /** The cell of the point ([x], [y]), or the nearest with a trace within [HIT_CELLS]; -1 if none. */
    private fun cellAt(x: Int, y: Int): Int {
        prepare()
        if (slowest.isEmpty()) return -1
        val column = x / cell
        val row = (y - plotTop) / cell
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

    private fun traceAt(x: Int, y: Int): Int = cellAt(x, y).let { if (it < 0) -1 else slowest[it] }

    override fun getToolTipText(e: MouseEvent): String? {
        val traces = traces ?: return null
        val cell = cellAt(e.x, e.y)
        if (cell < 0) return null
        val trace = slowest[cell]
        val more = crowd[cell] - 1
        val rest = if (more > 0) "<br><small>and ${count(more, "other trace")} here</small>" else ""
        return "<html>${escapeHtml(traces.id(trace))}<br>Start ${formatTraceTime(traces.start(trace))}" +
            "<br>Duration ${formatTimeSpan(traces.duration(trace))}$rest</html>"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIManager.getColor("Table.background")
            g2.fillRect(0, 0, width, height)
            val extent = extent ?: return
            prepare()
            g2.font = labelFont
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            paintDurations(g2, extent)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintDots(g2)
        } finally {
            g2.dispose()
        }
    }

    /** Lines at round durations, with their labels at the left edge, as many as leave room between them. */
    private fun paintDurations(g2: Graphics2D, extent: TraceExtent) {
        val metrics = g2.fontMetrics
        val span = (plotBottom - plotTop).toDouble()
        var lastY = Double.POSITIVE_INFINITY
        for (duration in ROUND_DURATIONS) {
            if (duration < extent.shortest || duration > extent.longest) continue
            val y = plotBottom - heightOf(duration, extent) * span
            if (lastY - y < metrics.height * LABEL_SPACING) continue
            lastY = y
            g2.color = withAlpha(look.border, 0.5f)
            g2.fillRect(0, y.toInt(), width, 1)
            g2.color = UIManager.getColor("Label.disabledForeground")
            g2.drawString(axisLabel(duration), look.scale(4), y.toInt() - metrics.descent)
        }
    }

    private fun paintDots(g2: Graphics2D) {
        val size = look.scale(DOT_SIZE).toDouble()
        g2.color = withAlpha(look.accent, 0.6f)
        for (index in slowest.indices) {
            if (slowest[index] < 0) continue
            val x = (index % columns) * cell + cell / 2.0
            val y = plotTop + (index / columns) * cell + cell / 2.0
            g2.fill(Ellipse2D.Double(x - size / 2, y - size / 2, size, size))
        }
    }
}
