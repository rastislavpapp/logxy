package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.util.UIScale
import io.github.rastislavpapp.logxy.core.template.VariableSlices
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.time.Instant
import java.time.ZoneId
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Histogram slices per slice of the charts: a percentile of the few records in one would be mostly noise. */
private const val CHART_GROUP = 4

/** The plot is this many times as tall as the histogram, at least. */
private const val PLOT_HEIGHTS = 2
private const val PLOT_PADDING = 4
private const val LEGEND_PADDING = 2
private const val LEGEND_GAP = 12

/** Legend labels are cut to this many characters, so a few charts fit side by side. */
private const val MAX_LEGEND_CHARS = 40
private const val CLOSE = "×"
private const val DOT_SIZE = 6f

/** At most this share of the plot is below 0 while some line is above it. */
private const val MAX_BELOW_ZERO = 0.5

/**
 * The variables charted in [charts], over the times of [timeline] (of the histogram bar above, so the two line up),
 * in one plot: per chart a line of its [VariableChart.metric] per slice of [CHART_GROUP] histogram slices. Each line
 * has its own scale, as their units differ, and all have 0 on one shared line; the left edge labels each scale's
 * ends in its chart's color. A legend row above switches a chart's metric (its label) or closes it (×).
 *
 * At the [pointer]'s time, set while the mouse is over this plot or the histogram, a line crosses the plot with each
 * chart's value there beside it; [onPointer] hears where the mouse points here. Clicking calls [onJumpToRecord] with
 * the record whose value is closest to the nearest line's point there, or [onJump] with the time if no line has one.
 * Dragging selects whole slices of the histogram, as dragging there does: [onDragged] hears the times while
 * dragging, [onRange] once dropped.
 */
class VariableChartPanel(private val charts: VariableCharts, private val timeline: () -> Timeline?) : JComponent() {

    var onPointer: (time: Long?) -> Unit = {}
    var onJump: (time: Long) -> Unit = {}
    var onJumpToRecord: (record: Int) -> Unit = {}

    /** From [from] to [to], exclusive. */
    var onRange: (from: Long, to: Long) -> Unit = { _, _ -> }

    /** The times being dragged over, or null once dropped. */
    var onDragged: (times: Pair<Long, Long>?) -> Unit = {}

    /** The times being dragged over on the histogram, from and to (exclusive); null if none. */
    var draggedTimes: Pair<Long, Long>? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    private var pressX = -1
    private var dragging = false

    /** The times dragged over, from and to (exclusive); null if none. */
    private var dragged: Pair<Long, Long>? = null
        set(value) {
            if (field == value) return
            field = value
            onDragged(value)
            repaint()
        }

    /** The time the mouse points at, here or on the histogram; null if none. */
    var pointer: Long? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    /** Times of the first and last marked row; null if unknown. */
    var markedTimes: Pair<Long, Long>? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    private val zone = ZoneId.systemDefault()

    /** Where the legend's labels and close marks were painted, and their charts. */
    private val legend = mutableListOf<LegendEntry>()

    /** The lines as last painted, for the pointer and clicks. */
    private val lines = mutableListOf<Line>()

    private class LegendEntry(val bounds: Rectangle, val chart: VariableChart, val closes: Boolean)

    /** [chart]'s [values] of [slices] as painted: [zeroY] where 0 is, [scale] pixels per unit. */
    private class Line(
        val chart: VariableChart,
        val slices: VariableSlices,
        val values: DoubleArray,
        val zeroY: Double,
        val scale: Double,
    ) {
        fun y(value: Double) = zeroY - value * scale
    }

    init {
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val entry = legend.firstOrNull { it.bounds.contains(e.point) }
                when {
                    entry?.closes == true -> charts.remove(entry.chart)
                    entry != null -> showMetrics(entry)
                    e.y >= plotTop -> {
                        pressX = e.x
                        dragging = false
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (pressX < 0) return
                pressX = -1
                val range = dragged
                dragged = null
                when {
                    !dragging -> jump(e)
                    range != null -> onRange(range.first, range.second)
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                val onLegend = legend.any { it.bounds.contains(e.point) }
                cursor = if (onLegend) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
                point(if (e.y >= plotTop) e.x else null)
            }

            override fun mouseDragged(e: MouseEvent) {
                point(e.x)
                if (pressX < 0) return
                if (abs(e.x - pressX) > DRAG_THRESHOLD) dragging = true
                if (dragging) drag(pressX, e.x)
            }

            override fun mouseExited(e: MouseEvent) = point(null)
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    /** Selects the histogram's slices from the one at [x1] to the one at [x2]. */
    private fun drag(x1: Int, x2: Int) {
        val timeline = timeline() ?: return
        fun slice(x: Int) = Math.floorDiv(timeline.timeAt(x) - timeline.from, timeline.sliceWidth)
        val first = minOf(slice(x1), slice(x2))
        val last = maxOf(slice(x1), slice(x2))
        dragged = timeline.from + first * timeline.sliceWidth to timeline.from + (last + 1) * timeline.sliceWidth
    }

    private fun point(x: Int?) {
        val time = x?.let { timeline()?.timeAt(it) }
        pointer = time
        onPointer(time)
    }

    private val labelFont get() = UIManager.getFont("small.font") ?: font
    private val legendHeight get() = getFontMetrics(labelFont).height + UIScale.scale(LEGEND_PADDING * 2)
    private val plotTop get() = legendHeight + UIScale.scale(PLOT_PADDING)
    private val plotBottom get() = height - 1 - UIScale.scale(PLOT_PADDING)

    override fun getPreferredSize(): Dimension =
        Dimension(0, legendHeight + UIScale.scale(PLOT_HEIGHTS * HISTOGRAM_HEIGHT + 2 * PLOT_PADDING) + 1)

    override fun getMinimumSize() = preferredSize

    /** The charts' slices: start, width and count. */
    private fun slicing(timeline: Timeline) =
        Triple(timeline.from, timeline.sliceWidth * CHART_GROUP, (timeline.count + CHART_GROUP - 1) / CHART_GROUP)

    override fun getToolTipText(e: MouseEvent): String? {
        val entry = legend.firstOrNull { it.bounds.contains(e.point) } ?: return null
        if (entry.closes) return "Stop charting"
        val label = escapeHtml(entry.chart.label)
        return "<html>$label<br><small>Click to choose what is charted</small></html>"
    }

    private fun showMetrics(entry: LegendEntry) {
        val popup = JPopupMenu()
        for (metric in VariableMetric.entries) {
            popup.add(JRadioButtonMenuItem(metric.title, metric == entry.chart.metric).apply {
                addActionListener {
                    entry.chart.metric = metric
                    repaint()
                }
            })
        }
        popup.show(this, entry.bounds.x, entry.bounds.y + entry.bounds.height)
    }

    /** Goes to the record behind the line nearest to [e] at its time. */
    private fun jump(e: MouseEvent) {
        val time = timeline()?.timeAt(e.x) ?: return
        var best: Line? = null
        var bestSlice = -1
        var distance = Double.POSITIVE_INFINITY
        for (line in lines) {
            val slice = sliceOf(line.slices, time)
            if (slice < 0 || line.values[slice].isNaN()) continue
            val d = abs(line.y(line.values[slice]) - e.y)
            if (d < distance) {
                best = line
                bestSlice = slice
                distance = d
            }
        }
        val values = best?.chart?.slicedValues
        if (best == null || values == null) return onJump(time)
        val from = best.slices.from + bestSlice * best.slices.width
        val index = values.closest(from, from + best.slices.width, best.chart.metric.target(best.slices, bestSlice))
        if (index >= 0) onJumpToRecord(values.row(index)) else onJump(time)
    }

    private fun sliceOf(slices: VariableSlices, time: Long): Int =
        Math.floorDiv(time - slices.from, slices.width).let { if (it in 0..<slices.count) it.toInt() else -1 }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIManager.getColor("Table.background")
            g2.fillRect(0, 0, width, height)
            g2.color = UIManager.getColor("Component.borderColor")
            g2.fillRect(0, height - 1, width, 1)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = labelFont
            paintLegend(g2)
            lines.clear()
            val timeline = timeline() ?: return
            markedTimes?.let { (from, to) -> paintMarked(g2, timeline, from, to) }
            (dragged ?: draggedTimes)?.let { (from, to) -> paintDragged(g2, timeline, from, to) }
            paintLines(g2, timeline)
            pointer?.let { paintPointer(g2, timeline, it) }
        } finally {
            g2.dispose()
        }
    }

    /** Per chart its color, metric and label, and a ×; labels are cut shorter until the row fits. */
    private fun paintLegend(g2: Graphics2D) {
        legend.clear()
        val metrics = g2.fontMetrics
        val baseline = UIScale.scale(LEGEND_PADDING) + metrics.ascent
        val chip = metrics.ascent * 2 / 3
        val close = metrics.stringWidth(CLOSE)
        val besideLabel = chip + UIScale.scale(4 + 4 + LEGEND_GAP) + close
        val all = charts.all
        fun texts(maxChars: Int) = all.map { "${it.metric.short} · ${it.label.cut(maxChars)}" }
        var maxChars = MAX_LEGEND_CHARS
        var texts = texts(maxChars)
        while (maxChars > 1 && UIScale.scale(4) + texts.sumOf { besideLabel + metrics.stringWidth(it) } > width) {
            texts = texts(--maxChars)
        }
        var x = UIScale.scale(4)
        for ((chart, text) in all.zip(texts)) {
            val start = x
            g2.color = chart.color
            g2.fillRect(x, baseline - chip, chip, chip)
            x += chip + UIScale.scale(4)
            g2.color = UIManager.getColor("Label.foreground")
            g2.drawString(text, x, baseline)
            x += metrics.stringWidth(text)
            legend += LegendEntry(Rectangle(start, 0, x - start, legendHeight), chart, closes = false)
            x += UIScale.scale(4)
            g2.color = UIManager.getColor("Label.disabledForeground")
            g2.drawString(CLOSE, x, baseline)
            legend += LegendEntry(Rectangle(x - 2, 0, close + 4, legendHeight), chart, closes = true)
            x += close + UIScale.scale(LEGEND_GAP)
        }
    }

    private fun paintMarked(g2: Graphics2D, timeline: Timeline, from: Long, to: Long) {
        val accent = UIManager.getColor("Component.accentColor")
        val left = timeline.x(from).toInt()
        val right = maxOf(left + UIScale.scale(2), timeline.x(to).toInt())
        g2.color = withAlpha(accent, 0.12f)
        g2.fillRect(left, plotTop, right - left, plotBottom - plotTop)
    }

    /** As the histogram shows the times dragged over: shaded, with edges. */
    private fun paintDragged(g2: Graphics2D, timeline: Timeline, from: Long, to: Long) {
        val accent = UIManager.getColor("Component.accentColor")
        val left = timeline.x(from).toInt()
        val right = maxOf(left + 1, timeline.x(to).toInt())
        g2.color = withAlpha(accent, 0.3f)
        g2.fillRect(left, plotTop, right - left, plotBottom - plotTop)
        g2.color = accent
        g2.fillRect(left, plotTop, 1, plotBottom - plotTop)
        g2.fillRect(right - 1, plotTop, 1, plotBottom - plotTop)
    }

    /**
     * Each chart's line on its own scale, with 0 on one line for all: as far up as the chart reaching furthest below
     * 0 needs, at most [MAX_BELOW_ZERO] of the way while some line is above. Its ends are labeled at the left.
     */
    private fun paintLines(g2: Graphics2D, timeline: Timeline) {
        val (from, sliceWidth, count) = slicing(timeline)
        // Per chart, how far above and below 0 its scale must reach.
        class Range(
            val chart: VariableChart,
            val slices: VariableSlices,
            val values: DoubleArray,
            val top: Double,
            val bottom: Double,
        )
        val ranges = charts.all.mapNotNull { chart ->
            val slices = charts.slices(chart, from, sliceWidth, count) ?: return@mapNotNull null
            val values = chart.metric.of(slices)
            val shown = (0..<slices.count).filter { i ->
                val x = timeline.x(slices.from + i * slices.width + slices.width / 2)
                x >= 0 && x <= width && !values[i].isNaN()
            }
            if (shown.isEmpty()) return@mapNotNull null
            val top = niceCeil(maxOf(0.0, shown.maxOf { values[it] }))
            val bottom = niceCeil(maxOf(0.0, -shown.minOf { values[it] }))
            Range(chart, slices, values, if (top == 0.0 && bottom == 0.0) 1.0 else top, bottom)
        }
        val plotTop = plotTop.toDouble()
        val plotBottom = plotBottom.toDouble()
        var below = ranges.maxOfOrNull { it.bottom / (it.top + it.bottom) } ?: 0.0
        if (ranges.any { it.top > 0 }) below = minOf(below, MAX_BELOW_ZERO)
        val zeroY = plotBottom - below * (plotBottom - plotTop)
        val above = zeroY - plotTop
        val under = plotBottom - zeroY
        g2.color = withAlpha(UIManager.getColor("Component.borderColor"), 0.5f)
        g2.fillRect(0, plotTop.toInt(), width, 1)
        g2.color = UIManager.getColor("Component.borderColor")
        g2.fillRect(0, zeroY.toInt(), width, 1)
        val stroke = BasicStroke(UIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val topLabels = mutableListOf<Pair<String, Color>>()
        val bottomLabels = mutableListOf<Pair<String, Color>>()
        for (range in ranges) {
            val scale = minOf(
                if (range.top > 0) above / range.top else Double.POSITIVE_INFINITY,
                if (range.bottom > 0) under / range.bottom else Double.POSITIVE_INFINITY,
            )
            val line = Line(range.chart, range.slices, range.values, zeroY, scale)
            lines += line
            g2.color = range.chart.color
            g2.stroke = stroke
            paintLine(g2, timeline, line)
            if (above > 0) topLabels += formatVariableValue(above / scale) to range.chart.color
            if (range.bottom > 0) bottomLabels += formatVariableValue(-under / scale) to range.chart.color
        }
        val metrics = g2.fontMetrics
        paintLabels(g2, topLabels, plotTop.toInt())
        paintLabels(g2, bottomLabels, plotBottom.toInt() - bottomLabels.size * metrics.height)
        val zeroTop = (zeroY - metrics.height).toInt().coerceAtLeast(plotTop.toInt() + topLabels.size * metrics.height)
        paintLabels(g2, listOf("0" to UIManager.getColor("Label.disabledForeground")), zeroTop)
    }

    /** Through the middle of each slice with a value, broken where there is none; a lone value is a dot. */
    private fun paintLine(g2: Graphics2D, timeline: Timeline, line: Line) {
        val slices = line.slices
        val path = Path2D.Double()
        var run = 0
        fun x(i: Int) = timeline.x(slices.from + i * slices.width + slices.width / 2)
        for (i in 0..<slices.count) {
            val value = line.values[i]
            if (value.isNaN()) {
                if (run == 1) dot(g2, x(i - 1), line.y(line.values[i - 1]))
                run = 0
                continue
            }
            if (run == 0) path.moveTo(x(i), line.y(value)) else path.lineTo(x(i), line.y(value))
            run++
        }
        if (run == 1) dot(g2, x(slices.count - 1), line.y(line.values[slices.count - 1]))
        g2.draw(path)
    }

    private fun dot(g2: Graphics2D, x: Double, y: Double) {
        val size = UIScale.scale(DOT_SIZE).toDouble()
        g2.fill(Ellipse2D.Double(x - size / 2, y - size / 2, size, size))
    }

    /** [labels] one under another from [top] at the left edge, on the background so lines do not hide them. */
    private fun paintLabels(g2: Graphics2D, labels: List<Pair<String, Color>>, top: Int) {
        val metrics = g2.fontMetrics
        val background = withAlpha(UIManager.getColor("Table.background"), 0.85f)
        var y = top
        for ((text, color) in labels) {
            g2.color = background
            g2.fillRect(UIScale.scale(2), y, metrics.stringWidth(text) + UIScale.scale(4), metrics.height)
            g2.color = color
            g2.drawString(text, UIScale.scale(4), y + metrics.ascent)
            y += metrics.height
        }
    }

    /** The line at [time], a dot on each line in its slice there, and beside it the slice's time and values. */
    private fun paintPointer(g2: Graphics2D, timeline: Timeline, time: Long) {
        val x = timeline.x(time).toInt()
        paintPointerLine(g2, x, plotTop, plotBottom)
        val (from, sliceWidth, count) = slicing(timeline)
        val slice = Math.floorDiv(time - from, sliceWidth)
        if (slice !in 0..<count) return
        val sliceFrom = from + slice * sliceWidth
        val multiDay = localDay(timeline.from) != localDay(timeline.to - 1)
        val readout = mutableListOf(
            formatTimeRange(sliceFrom, sliceFrom + sliceWidth, zone, multiDay) to
                UIManager.getColor("Label.disabledForeground"),
        )
        for (line in lines) {
            val i = sliceOf(line.slices, time)
            val value = if (i >= 0) line.values[i] else Double.NaN
            readout += "${line.chart.metric.short} ${formatVariableValue(value)}" to line.chart.color
            if (value.isNaN()) continue
            g2.color = line.chart.color
            dot(g2, timeline.x(line.slices.from + i * line.slices.width + line.slices.width / 2), line.y(value))
        }
        paintReadout(g2, x, plotTop, width, readout)
    }

    private fun localDay(time: Long) = Instant.ofEpochSecond(0, time).atZone(zone).toLocalDate()

    /** The smallest of 1, 2, 2.5 or 5 times a power of ten that is at least [value]; 0 for 0. */
    private fun niceCeil(value: Double): Double {
        if (value <= 0) return 0.0
        val power = 10.0.pow(floor(log10(value)))
        // Rounding may put the value a hair above its own power of ten.
        return listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * power >= value * (1 - 1e-9) } * power
    }

    private fun withAlpha(color: Color, alpha: Float) =
        Color(color.red, color.green, color.blue, (alpha.coerceIn(0f, 1f) * 255).toInt())
}
