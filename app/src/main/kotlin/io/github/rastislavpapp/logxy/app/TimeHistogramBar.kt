package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.util.UIScale
import io.github.rastislavpapp.logxy.core.session.TimeHistogram
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
import java.awt.geom.Path2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.sqrt

private const val CHART_HEIGHT = 40
private const val TICK_HEIGHT = 3
private const val MATCH_STRIP_HEIGHT = 3

/** The slot charts' strip holds several lines: taller than the histogram. */
private const val STRIP_HEIGHT = CHART_HEIGHT * 3 / 2
private const val LEGEND_PADDING = 2

/** Histogram slices per slice of the charts. */
private const val CHART_GROUP = 4
private const val LEGEND_GAP = 12

/** Legend labels are cut to this many characters, so a few charts fit side by side. */
private const val MAX_LEGEND_CHARS = 40
private const val CLOSE = "×"

/** Pixels per slice of time: wide enough that counts are not mostly noise, narrow enough to look continuous. */
private const val SLICE_WIDTH = 2

/** Space a time label needs around it, in pixels. */
private const val LABEL_GAP = 24

/** How far the mouse must move, in pixels, before a press becomes a drag. */
private const val DRAG_THRESHOLD = 3

private const val MILLI = 1_000_000L
private const val SECOND = 1_000 * MILLI
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private val TICK_STEPS = longArrayOf(
    MILLI, 2 * MILLI, 5 * MILLI, 10 * MILLI, 20 * MILLI, 50 * MILLI, 100 * MILLI, 200 * MILLI, 500 * MILLI,
    SECOND, 2 * SECOND, 5 * SECOND, 10 * SECOND, 15 * SECOND, 30 * SECOND,
    MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
    HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY, 7 * DAY, 30 * DAY,
)

/**
 * Shown records over time: their volume as an area, with lines for warnings and errors over it on the same
 * scale, so heights compare truthfully. The scale is the square root, so one spike does not flatten the rest
 * and a burst that is a small share of all records still rises well off the bottom. The selected rows, or the
 * top row in view, are marked. The records a search found are a strip along the bottom, in every slice with a match;
 * [found] counts them on bins of their own, which are mapped onto the slices by time.
 * Clicking calls [onJump] with the time there; dragging calls [onRange] with the times covered; the
 * context menu calls [onShowAllTimes] when [hasTimeRange].
 *
 * Below, while there are [charts], a strip of them on the same slices: per chart the p50 and p99 of its values, each
 * chart on its own scale, as their units differ; a legend row above it closes them. Clicking and dragging there do
 * what they do on the histogram.
 */
class TimeHistogramBar(
    private val histogram: () -> TimeHistogram?,
    private val found: () -> TimeHistogram? = { null },
    private val charts: SlotCharts? = null,
) : JComponent() {

    var onJump: (time: Long) -> Unit = {}

    /** From [from] to [to], exclusive. */
    var onRange: (from: Long, to: Long) -> Unit = { _, _ -> }
    var onShowAllTimes: () -> Unit = {}
    var hasTimeRange: () -> Boolean = { false }

    /** Times of the first and last marked row; null if unknown. */
    var markedTimes: Pair<Long, Long>? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    private val zone = ZoneId.systemDefault()
    private var pressX = -1
    private var dragging = false
    private var selection: IntRange? = null

    /** Where the legend's close marks were painted, and the chart each closes. */
    private val closeBoxes = mutableListOf<Pair<Rectangle, SlotChart>>()

    /** The histogram as painted: [count] slices of [group] bins each, the first starting at bin [first]. */
    private class Slices(
        val histogram: TimeHistogram,
        val first: Int,
        val group: Int,
        val count: Int,
        found: TimeHistogram?,
    ) {
        val totals = IntArray(count)
        val warnings = IntArray(count)
        val errors = IntArray(count)

        /** Matches of the search per slice; null if there is no search. */
        val matches: IntArray? = found?.let { IntArray(count) }

        init {
            for (bin in first..histogram.lastBin) {
                val slice = (bin - first) / group
                warnings[slice] += histogram.count(TimeHistogram.WARN, bin)
                errors[slice] += histogram.count(TimeHistogram.ERROR, bin)
                totals[slice] += histogram.count(TimeHistogram.OTHER, bin)
            }
            for (i in 0..<count) totals[i] += warnings[i] + errors[i]
            if (found != null && matches != null) addMatches(found, matches)
        }

        /**
         * Adds the bins of [found] to [matches] by their times: a bin wider than a slice shares its count among the
         * slices it covers, at least 1 each.
         */
        private fun addMatches(found: TimeHistogram, matches: IntArray) {
            if (found.isEmpty) return
            val origin = start(0)
            val span = (start(1) - origin).coerceAtLeast(1)
            for (bin in found.firstBin..found.lastBin) {
                val inBin = (0..<TimeHistogram.CATEGORIES).sumOf { found.count(it, bin) }
                if (inBin <= 0) continue
                val from = found.binStart(bin)
                val first = Math.floorDiv(from - origin, span)
                val last = Math.floorDiv(from + found.width - 1 - origin, span)
                if (last < 0 || first >= count) continue
                val share = ((inBin + last - first) / (last - first + 1)).toInt()
                for (slice in maxOf(0L, first)..minOf(count - 1L, last)) matches[slice.toInt()] += share
            }
        }

        fun start(slice: Int) = histogram.binStart(first + slice * group)
    }

    init {
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                if (!SwingUtilities.isLeftMouseButton(e)) return
                closeBoxes.firstOrNull { it.first.contains(e.point) }?.let { (_, chart) ->
                    charts?.remove(chart)
                    return
                }
                pressX = e.x
                dragging = false
            }

            override fun mouseMoved(e: MouseEvent) {
                val onClose = closeBoxes.any { it.first.contains(e.point) }
                cursor = if (onClose) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
            }

            override fun mouseDragged(e: MouseEvent) {
                if (pressX < 0) return
                if (abs(e.x - pressX) > DRAG_THRESHOLD) dragging = true
                if (!dragging) return
                val slices = slices() ?: return
                val a = sliceAt(slices, pressX)
                val b = sliceAt(slices, e.x)
                selection = minOf(a, b)..maxOf(a, b)
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                if (pressX < 0) return
                val slices = slices()
                val range = selection
                pressX = -1
                selection = null
                repaint()
                if (slices == null) return
                if (dragging && range != null) {
                    onRange(slices.start(range.first), slices.start(range.last + 1))
                } else if (!dragging) {
                    onJump(slices.start(sliceAt(slices, e.x)))
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun showMenu(e: MouseEvent) {
        val popup = JPopupMenu()
        popup.add(JMenuItem("Show All Times").apply {
            isEnabled = hasTimeRange()
            addActionListener { onShowAllTimes() }
        })
        popup.show(this, e.x, e.y)
    }

    private val labelFont get() = UIManager.getFont("small.font") ?: font

    private val chartTop get() = UIScale.scale(4)
    private val chartBottom get() = chartTop + UIScale.scale(CHART_HEIGHT)
    private val labelsBottom
        get() = chartBottom + UIScale.scale(TICK_HEIGHT) + getFontMetrics(labelFont).height + UIScale.scale(2)

    private val hasCharts get() = charts?.all?.isNotEmpty() == true
    private val legendTop get() = labelsBottom
    private val stripTop get() = legendTop + getFontMetrics(labelFont).height + UIScale.scale(LEGEND_PADDING * 2)
    private val stripBottom get() = stripTop + UIScale.scale(STRIP_HEIGHT)

    override fun getPreferredSize(): Dimension =
        Dimension(0, if (hasCharts) stripBottom + UIScale.scale(LEGEND_PADDING) else labelsBottom)

    override fun getMinimumSize() = preferredSize

    private fun slices(): Slices? {
        val histogram = histogram() ?: return null
        if (histogram.isEmpty || width <= 0) return null
        val most = maxOf(1, width / UIScale.scale(SLICE_WIDTH))
        var group = 1
        fun count() = histogram.lastBin / group - histogram.firstBin / group + 1
        while (count() > most) group *= 2
        return Slices(histogram, histogram.firstBin / group * group, group, count(), found())
    }

    private fun sliceX(slices: Slices, slice: Int) = (slice.toLong() * width / slices.count).toInt()

    private fun sliceAt(slices: Slices, x: Int) = (x.coerceIn(0, width - 1).toLong() * slices.count / width).toInt()

    private fun timeX(slices: Slices, time: Long): Double {
        val from = slices.start(0)
        return (time - from).toDouble() / (slices.start(slices.count) - from) * width
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIManager.getColor("Table.background")
            g2.fillRect(0, 0, width, height)
            g2.color = UIManager.getColor("Component.borderColor")
            g2.fillRect(0, height - 1, width, 1)
            closeBoxes.clear()
            val slices = slices() ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (hasCharts) paintCharts(g2, slices)
            // One scale for all, so a line's height means as many records as the area's.
            val peak = sqrt(maxOf(1, slices.totals.max()).toDouble())
            paintVolume(g2, slices, peak)
            paintMatches(g2, slices)
            paintLine(g2, slices, slices.warnings, peak, UIManager.getColor("Actions.Yellow"))
            paintLine(g2, slices, slices.errors, peak, UIManager.getColor("Actions.Red"))
            paintPeak(g2, slices)
            val accent = UIManager.getColor("Component.accentColor")
            markedTimes?.let { (from, to) ->
                val x1 = timeX(slices, from)
                paintRegion(g2, x1, maxOf(x1 + UIScale.scale(2), timeX(slices, to)), accent, 0.12f)
            }
            selection?.let {
                val from = sliceX(slices, it.first).toDouble()
                paintRegion(g2, from, sliceX(slices, it.last + 1).toDouble(), accent, 0.3f)
            }
            paintTicks(g2, slices)
        } finally {
            g2.dispose()
        }
    }

    private fun paintVolume(g2: Graphics2D, slices: Slices, peak: Double) {
        val area = path(slices) { sqrt(slices.totals[it].toDouble()) / peak }.apply {
            lineTo(width.toDouble(), chartBottom.toDouble())
            lineTo(0.0, chartBottom.toDouble())
            closePath()
        }
        g2.color = withAlpha(UIManager.getColor("Label.disabledForeground"), 0.3f)
        g2.fill(area)
    }

    /** A strip along the bottom of the chart in every slice with a match; it adds no height to the bar. */
    private fun paintMatches(g2: Graphics2D, slices: Slices) {
        val matches = slices.matches ?: return
        // The table's color without its alpha, as the strip is thin and sits on the volume.
        g2.color = Color(FOUND_COLOR.red, FOUND_COLOR.green, FOUND_COLOR.blue)
        val strip = UIScale.scale(MATCH_STRIP_HEIGHT)
        for (slice in 0..<slices.count) {
            if (matches[slice] == 0) continue
            val x = sliceX(slices, slice)
            g2.fillRect(x, chartBottom - strip, maxOf(1, sliceX(slices, slice + 1) - x), strip)
        }
    }

    /** [counts] as a line on the volume's square-root scale, which lifts small counts off the bottom. */
    private fun paintLine(g2: Graphics2D, slices: Slices, counts: IntArray, peak: Double, color: Color) {
        if (counts.max() == 0) return
        g2.color = color
        g2.stroke = BasicStroke(UIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(path(slices) { sqrt(counts[it].toDouble()) / peak })
    }

    /** The count the top of the chart stands for, e.g. `1,234 / 2 min`, so the height can be read. */
    private fun paintPeak(g2: Graphics2D, slices: Slices) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = labelFont
        g2.color = UIManager.getColor("Label.disabledForeground")
        val text = "%,d / %s".format(slices.totals.max(), shortSpan(slices.start(1) - slices.start(0)))
        g2.drawString(text, UIScale.scale(4), chartTop + g2.fontMetrics.ascent)
    }

    private fun shortSpan(nanos: Long): String = when {
        nanos % DAY == 0L -> "${nanos / DAY} d"
        nanos % HOUR == 0L -> "${nanos / HOUR} h"
        nanos % MINUTE == 0L -> "${nanos / MINUTE} min"
        nanos % SECOND == 0L -> "${nanos / SECOND} s"
        nanos % MILLI == 0L -> "${nanos / MILLI} ms"
        else -> "$nanos ns"
    }

    /** A line through the middle of each slice at [fraction] of the chart's height, held level to both edges. */
    private fun path(slices: Slices, fraction: (Int) -> Double): Path2D.Double {
        val height = (chartBottom - chartTop).toDouble()
        fun y(slice: Int) = chartBottom - fraction(slice) * height
        val sliceWidth = width.toDouble() / slices.count
        return Path2D.Double().apply {
            moveTo(0.0, y(0))
            for (i in 0..<slices.count) lineTo((i + 0.5) * sliceWidth, y(i))
            lineTo(width.toDouble(), y(slices.count - 1))
        }
    }

    /** Over the histogram, and over the charts' strip if it shows. */
    private fun paintRegion(g2: Graphics2D, x1: Double, x2: Double, accent: Color, alpha: Float) {
        val left = x1.toInt()
        val right = maxOf(left + 1, x2.toInt())
        val bands = if (hasCharts) listOf(0 to chartBottom, stripTop to stripBottom) else listOf(0 to chartBottom)
        for ((top, bottom) in bands) {
            g2.color = withAlpha(accent, alpha)
            g2.fillRect(left, top, right - left, bottom - top)
            g2.color = accent
            g2.fillRect(left, top, 1, bottom - top)
            g2.fillRect(right - 1, top, 1, bottom - top)
        }
    }

    /** The legend, then each chart's p50 as a line and its p99 dashed, on a scale of its own. */
    private fun paintCharts(g2: Graphics2D, slices: Slices) {
        val charts = charts ?: return
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = labelFont
        val metrics = g2.fontMetrics
        g2.color = UIManager.getColor("Component.borderColor")
        g2.fillRect(0, legendTop, width, 1)
        val baseline = legendTop + UIScale.scale(LEGEND_PADDING) + metrics.ascent
        val chip = metrics.ascent * 2 / 3
        val close = metrics.stringWidth(CLOSE)
        val besideLabel = chip + UIScale.scale(4 + 4 + LEGEND_GAP) + close
        // Labels are cut shorter until the whole row fits, so every chart can be closed.
        var maxChars = MAX_LEGEND_CHARS
        var legend = charts.all.map { it.label.cut(maxChars) }
        while (maxChars > 1 && UIScale.scale(4) + legend.sumOf { besideLabel + metrics.stringWidth(it) } > width) {
            maxChars--
            legend = charts.all.map { it.label.cut(maxChars) }
        }
        var x = UIScale.scale(4)
        for ((chart, label) in charts.all.zip(legend)) {
            g2.color = chart.color
            g2.fillRect(x, baseline - chip, chip, chip)
            x += chip + UIScale.scale(4)
            g2.color = UIManager.getColor("Label.foreground")
            g2.drawString(label, x, baseline)
            x += metrics.stringWidth(label) + UIScale.scale(4)
            g2.color = UIManager.getColor("Label.disabledForeground")
            g2.drawString(CLOSE, x, baseline)
            closeBoxes += Rectangle(x - 2, legendTop, close + 4, stripTop - legendTop) to chart
            x += close + UIScale.scale(LEGEND_GAP)
        }
        val (from, width, count) = chartSlicing(slices)
        val top = stripTop + UIScale.scale(LEGEND_PADDING)
        val height = (stripBottom - top).toDouble()
        val labels = mutableListOf<Pair<String, Color>>()
        val dashed = BasicStroke(
            UIScale.scale(1f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
            floatArrayOf(UIScale.scale(3f), UIScale.scale(3f)), 0f,
        )
        val solid = BasicStroke(UIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (chart in charts.all) {
            val values = charts.slices(chart, from, width, count) ?: continue
            val shown = (0..<values.count).filter { i ->
                val x = timeX(slices, values.from + i * values.width + values.width / 2)
                x >= 0 && x <= this.width && !values.p50[i].isNaN()
            }
            if (shown.isEmpty()) continue
            val high = shown.maxOf { values.p99[it] }
            // From 0, or from the lowest value shown if any is below.
            val low = minOf(0.0, shown.minOf { minOf(values.p50[it], values.p99[it]) })
            val range = (high - low).takeIf { it > 0 } ?: 1.0
            fun line(numbers: DoubleArray) = Path2D.Double().apply {
                var drawing = false
                for (i in 0..<values.count) {
                    val value = numbers[i]
                    if (value.isNaN()) {
                        drawing = false
                        continue
                    }
                    val x = timeX(slices, values.from + i * values.width + values.width / 2)
                    val y = stripBottom - (value - low) / range * height
                    if (drawing) lineTo(x, y) else moveTo(x, y)
                    drawing = true
                }
            }
            g2.color = chart.color
            g2.stroke = dashed
            g2.draw(line(values.p99))
            g2.stroke = solid
            g2.draw(line(values.p50))
            labels += "p99 ${formatValue(high)}" to chart.color
        }
        // Over all lines, on the background, so they stay readable.
        var labelY = stripTop
        val background = withAlpha(UIManager.getColor("Table.background"), 0.85f)
        for ((text, color) in labels) {
            g2.color = background
            g2.fillRect(UIScale.scale(2), labelY, metrics.stringWidth(text) + UIScale.scale(4), metrics.height)
            g2.color = color
            g2.drawString(text, UIScale.scale(4), labelY + metrics.ascent)
            labelY += metrics.height
        }
    }

    /**
     * The charts' slices: [CHART_GROUP] of the histogram's each, as a percentile of the few records in one would be
     * mostly noise; as start, width and count.
     */
    private fun chartSlicing(slices: Slices): Triple<Long, Long, Int> {
        val from = slices.start(0)
        val width = (slices.start(1) - from) * CHART_GROUP
        return Triple(from, width, (slices.count + CHART_GROUP - 1) / CHART_GROUP)
    }

    /** Ticks at round times, labeled as far apart as the labels need. */
    private fun paintTicks(g2: Graphics2D, slices: Slices) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = labelFont
        val metrics = g2.fontMetrics
        val from = slices.start(0)
        val to = slices.start(slices.count)
        val multiDay = localDay(from) != localDay(to - 1)
        val span = to - from
        var step = TICK_STEPS.last()
        var formatter = tickFormatter(step, multiDay)
        for (candidate in TICK_STEPS) {
            val candidateFormatter = tickFormatter(candidate, multiDay)
            val label = candidateFormatter.format(Instant.ofEpochSecond(0, from))
            val labelWidth = metrics.stringWidth(label) + UIScale.scale(LABEL_GAP)
            if (candidate.toDouble() / span * width >= labelWidth) {
                step = candidate
                formatter = candidateFormatter
                break
            }
        }
        // Round in local time, so hours and days start where the clock says.
        val offset = zone.rules.getOffset(Instant.ofEpochSecond(0, from)).totalSeconds * SECOND
        var tick = Math.floorDiv(from + offset + step - 1, step) * step - offset
        val y = chartBottom + UIScale.scale(TICK_HEIGHT) + metrics.ascent
        while (tick < to) {
            val x = timeX(slices, tick).toInt()
            g2.color = UIManager.getColor("Component.borderColor")
            g2.fillRect(x, chartBottom, 1, UIScale.scale(TICK_HEIGHT))
            val label = formatter.format(Instant.ofEpochSecond(0, tick))
            val labelX = (x - metrics.stringWidth(label) / 2).coerceIn(0, width - metrics.stringWidth(label))
            g2.color = UIManager.getColor("Label.disabledForeground")
            g2.drawString(label, labelX, y)
            tick += step
        }
    }

    private fun localDay(time: Long) = Instant.ofEpochSecond(0, time).atZone(zone).toLocalDate()

    private fun tickFormatter(step: Long, multiDay: Boolean): DateTimeFormatter {
        val pattern = when {
            step >= DAY -> "MM-dd"
            step >= MINUTE -> if (multiDay) "MM-dd HH:mm" else "HH:mm"
            step >= SECOND -> "HH:mm:ss"
            else -> "HH:mm:ss.SSS"
        }
        return DateTimeFormatter.ofPattern(pattern).withZone(zone)
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val slices = slices() ?: return null
        if (closeBoxes.any { it.first.contains(e.point) }) return "Stop charting"
        val slice = sliceAt(slices, e.x)
        val onCharts = hasCharts && e.y >= legendTop
        // On the charts, their own wider slice.
        val (chartsFrom, chartsWidth) = chartSlicing(slices)
        val from = if (onCharts) {
            chartsFrom + Math.floorDiv(slices.start(slice) - chartsFrom, chartsWidth) * chartsWidth
        } else {
            slices.start(slice)
        }
        val to = if (onCharts) from + chartsWidth else slices.start(slice + 1)
        val multiDay = localDay(slices.start(0)) != localDay(slices.start(slices.count) - 1)
        val time = if (to - from < SECOND) "HH:mm:ss.SSS" else "HH:mm:ss"
        val formatter = DateTimeFormatter.ofPattern(if (multiDay) "yyyy-MM-dd $time" else time).withZone(zone)
        val lines = if (onCharts) {
            chartLines(from)
        } else {
            val parts = buildList {
                add("%,d records".format(slices.totals[slice]))
                slices.errors[slice].takeIf { it > 0 }?.let { add("%,d errors".format(it)) }
                slices.warnings[slice].takeIf { it > 0 }?.let { add("%,d warnings".format(it)) }
                slices.matches?.get(slice)?.takeIf { it > 0 }?.let { add("%,d matches".format(it)) }
            }
            listOf(parts.joinToString(", "))
        }
        val range = "${formatter.format(Instant.ofEpochSecond(0, from))} – " +
            formatter.format(Instant.ofEpochSecond(0, to))
        val hint = "<small>Click to go there, drag to filter by time</small>"
        return "<html>$range<br>${lines.joinToString("<br>")}<br>$hint</html>"
    }

    /** Per chart, in its color, the numbers of its slice that starts at [time]. */
    private fun chartLines(time: Long): List<String> = charts?.all.orEmpty().mapNotNull { chart ->
        val values = chart.slices ?: return@mapNotNull null
        val i = Math.floorDiv(time - values.from, values.width)
        if (i !in 0..<values.count) return@mapNotNull null
        val slice = i.toInt()
        val color = "#%06x".format(chart.color.rgb and 0xFFFFFF)
        val label = escapeHtml(chart.label.cut(MAX_LEGEND_CHARS))
        val records = values.records[slice]
        val numbers = if (records == values.withoutNumber[slice]) {
            "no numbers"
        } else {
            listOf(values.min, values.p50, values.p99, values.max)
                .joinToString(" · ") { formatValue(it[slice]) }
                .let { "min · p50 · p99 · max: $it" }
        }
        val without = values.withoutNumber[slice].takeIf { it > 0 }?.let { ", %,d without a number".format(it) }
        "<font color=\"$color\">■</font> $label: ${"%,d".format(records)} records, $numbers${without.orEmpty()}"
    }

    /** Whole numbers as they are, others to a few significant digits. */
    private fun formatValue(value: Double): String = when {
        value.isNaN() -> "–"
        value == Math.rint(value) && abs(value) < 1e15 -> "%,d".format(value.toLong())
        abs(value) >= 100 -> "%,.0f".format(value)
        abs(value) >= 1 -> "%.2f".format(value)
        else -> "%.3g".format(value)
    }

    private fun withAlpha(color: Color, alpha: Float) =
        Color(color.red, color.green, color.blue, (alpha.coerceIn(0f, 1f) * 255).toInt())
}
