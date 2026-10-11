package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.util.UIScale
import io.github.rastislavpapp.logxy.core.session.TimeHistogram
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
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
 * and a burst that is a small share of all records still rises well off the bottom. The rows in view are outlined.
 * Clicking calls [onJump] with the time there; dragging calls [onRange] with the times covered; the
 * context menu calls [onShowAllTimes] when [hasTimeRange].
 */
class TimeHistogramBar(private val histogram: () -> TimeHistogram?) : JComponent() {

    var onJump: (time: Long) -> Unit = {}

    /** From [from] to [to], exclusive. */
    var onRange: (from: Long, to: Long) -> Unit = { _, _ -> }
    var onShowAllTimes: () -> Unit = {}
    var hasTimeRange: () -> Boolean = { false }

    /** Times of the first and last row in view; null if unknown. */
    var visibleTimes: Pair<Long, Long>? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    private val zone = ZoneId.systemDefault()
    private var pressX = -1
    private var dragging = false
    private var selection: IntRange? = null

    /** The histogram as painted: [count] slices of [group] bins each, the first starting at bin [first]. */
    private class Slices(val histogram: TimeHistogram, val first: Int, val group: Int, val count: Int) {
        val totals = IntArray(count)
        val warnings = IntArray(count)
        val errors = IntArray(count)

        init {
            for (bin in first..histogram.lastBin) {
                val slice = (bin - first) / group
                warnings[slice] += histogram.count(TimeHistogram.WARN, bin)
                errors[slice] += histogram.count(TimeHistogram.ERROR, bin)
                totals[slice] += histogram.count(TimeHistogram.OTHER, bin)
            }
            for (i in 0..<count) totals[i] += warnings[i] + errors[i]
        }

        fun start(slice: Int) = histogram.binStart(first + slice * group)
    }

    init {
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                if (!SwingUtilities.isLeftMouseButton(e)) return
                pressX = e.x
                dragging = false
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
                if (dragging && range != null) onRange(slices.start(range.first), slices.start(range.last + 1))
                else if (!dragging) onJump(slices.start(sliceAt(slices, e.x)))
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

    override fun getPreferredSize(): Dimension {
        val labels = getFontMetrics(labelFont).height
        return Dimension(0, chartBottom + UIScale.scale(TICK_HEIGHT) + labels + UIScale.scale(2))
    }

    override fun getMinimumSize() = preferredSize

    private fun slices(): Slices? {
        val histogram = histogram() ?: return null
        if (histogram.isEmpty || width <= 0) return null
        val most = maxOf(1, width / UIScale.scale(SLICE_WIDTH))
        var group = 1
        while (histogram.lastBin / group - histogram.firstBin / group + 1 > most) group *= 2
        return Slices(histogram, histogram.firstBin / group * group, group, histogram.lastBin / group - histogram.firstBin / group + 1)
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
            val slices = slices() ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // One scale for all, so a line's height means as many records as the area's.
            val peak = sqrt(maxOf(1, slices.totals.max()).toDouble())
            paintVolume(g2, slices, peak)
            paintLine(g2, slices, slices.warnings, peak, UIManager.getColor("Actions.Yellow"))
            paintLine(g2, slices, slices.errors, peak, UIManager.getColor("Actions.Red"))
            paintPeak(g2, slices)
            val accent = UIManager.getColor("Component.accentColor")
            visibleTimes?.let { (from, to) ->
                val x1 = timeX(slices, from)
                paintRegion(g2, x1, maxOf(x1 + UIScale.scale(2), timeX(slices, to)), accent, 0.12f)
            }
            selection?.let {
                paintRegion(g2, sliceX(slices, it.first).toDouble(), sliceX(slices, it.last + 1).toDouble(), accent, 0.3f)
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

    private fun paintRegion(g2: Graphics2D, x1: Double, x2: Double, accent: Color, alpha: Float) {
        val left = x1.toInt()
        val right = maxOf(left + 1, x2.toInt())
        g2.color = withAlpha(accent, alpha)
        g2.fillRect(left, 0, right - left, chartBottom)
        g2.color = accent
        g2.fillRect(left, 0, 1, chartBottom)
        g2.fillRect(right - 1, 0, 1, chartBottom)
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
            val labelWidth = metrics.stringWidth(candidateFormatter.format(Instant.ofEpochSecond(0, from))) + UIScale.scale(LABEL_GAP)
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
        val slice = sliceAt(slices, e.x)
        val from = slices.start(slice)
        val to = slices.start(slice + 1)
        val multiDay = localDay(slices.start(0)) != localDay(slices.start(slices.count) - 1)
        val time = if (to - from < SECOND) "HH:mm:ss.SSS" else "HH:mm:ss"
        val formatter = DateTimeFormatter.ofPattern(if (multiDay) "yyyy-MM-dd $time" else time).withZone(zone)
        val parts = buildList {
            add("%,d records".format(slices.totals[slice]))
            slices.errors[slice].takeIf { it > 0 }?.let { add("%,d errors".format(it)) }
            slices.warnings[slice].takeIf { it > 0 }?.let { add("%,d warnings".format(it)) }
        }
        val range = "${formatter.format(Instant.ofEpochSecond(0, from))} – ${formatter.format(Instant.ofEpochSecond(0, to))}"
        return "<html>$range<br>${parts.joinToString(", ")}<br><small>Click to go there, drag to filter by time</small></html>"
    }

    private fun withAlpha(color: Color, alpha: Float) = Color(color.red, color.green, color.blue, (alpha.coerceIn(0f, 1f) * 255).toInt())
}
