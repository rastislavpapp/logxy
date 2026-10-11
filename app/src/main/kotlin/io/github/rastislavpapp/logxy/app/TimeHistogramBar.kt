package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.util.UIScale
import io.github.rastislavpapp.logxy.core.session.TimeHistogram
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs

private const val CHART_HEIGHT = 34
private const val MIN_BAR_WIDTH = 4

/** Warnings and errors get at least this many pixels in a bar, so a few of them among many records still show. */
private const val MIN_LEVEL_HEIGHT = 2

/** How far the mouse must move, in pixels, before a press becomes a drag. */
private const val DRAG_THRESHOLD = 3

private const val SECOND = 1_000_000_000L

/**
 * Shown records per span of time, as a thin bar chart colored by level, so spikes of errors stand out.
 * The rows in view are shaded. Clicking a bar calls [onJump] with its start; dragging over bars calls
 * [onRange] with the times they cover; the context menu calls [onShowAllTimes] when [hasTimeRange].
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

    /** Bars as painted: [count] bars of [group] histogram bins each, the first starting at bin [first]. */
    private class Bars(val histogram: TimeHistogram, val first: Int, val group: Int, val count: Int) {
        fun start(bar: Int) = histogram.binStart(first + bar * group)

        fun count(category: Int, bar: Int): Int {
            var sum = 0
            for (bin in first + bar * group..<minOf(first + (bar + 1) * group, TimeHistogram.BINS)) sum += histogram.count(category, bin)
            return sum
        }

        fun total(bar: Int) = (0..<TimeHistogram.CATEGORIES).sumOf { count(it, bar) }
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
                val bars = bars() ?: return
                val a = barAt(bars, pressX)
                val b = barAt(bars, e.x)
                selection = minOf(a, b)..maxOf(a, b)
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                if (pressX < 0) return
                val bars = bars()
                val range = selection
                pressX = -1
                selection = null
                repaint()
                if (bars == null) return
                if (dragging && range != null) onRange(bars.start(range.first), bars.start(range.last + 1))
                else if (!dragging) onJump(bars.start(barAt(bars, e.x)))
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

    override fun getPreferredSize(): Dimension {
        val labels = getFontMetrics(labelFont).height
        return Dimension(0, UIScale.scale(CHART_HEIGHT) + labels + UIScale.scale(4))
    }

    override fun getMinimumSize() = preferredSize

    private val chartTop get() = UIScale.scale(3)
    private val chartHeight get() = UIScale.scale(CHART_HEIGHT)

    private fun bars(): Bars? {
        val histogram = histogram() ?: return null
        if (histogram.isEmpty || width <= 0) return null
        val most = maxOf(1, width / UIScale.scale(MIN_BAR_WIDTH))
        var group = 1
        while (histogram.lastBin / group - histogram.firstBin / group + 1 > most) group *= 2
        return Bars(histogram, histogram.firstBin / group * group, group, histogram.lastBin / group - histogram.firstBin / group + 1)
    }

    private fun barX(bars: Bars, bar: Int) = (bar.toLong() * width / bars.count).toInt()

    private fun barAt(bars: Bars, x: Int) = (x.coerceIn(0, width - 1).toLong() * bars.count / width).toInt()

    private fun timeX(bars: Bars, time: Long): Int {
        val from = bars.start(0)
        val span = bars.start(bars.count) - from
        return ((time - from).toDouble() / span * width).toInt().coerceIn(0, width)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = UIManager.getColor("Table.background")
        g2.fillRect(0, 0, width, height)
        g2.color = UIManager.getColor("Component.borderColor")
        g2.fillRect(0, height - 1, width, 1)
        val bars = bars() ?: return
        val accent = UIManager.getColor("Component.accentColor")

        visibleTimes?.let { (from, to) ->
            val x1 = timeX(bars, from)
            val x2 = maxOf(x1 + UIScale.scale(2), timeX(bars, to))
            g2.color = withAlpha(accent, 0.18f)
            g2.fillRect(x1, 0, x2 - x1, chartTop + chartHeight)
        }

        val totals = IntArray(bars.count) { bars.total(it) }
        val most = maxOf(1, totals.max())
        val bottom = chartTop + chartHeight
        val colors = arrayOf(
            UIManager.getColor("Actions.Grey") ?: UIManager.getColor("Label.disabledForeground"),
            UIManager.getColor("Actions.Yellow"),
            UIManager.getColor("Actions.Red"),
        )
        fun height(count: Int, min: Int) = if (count == 0) 0 else maxOf(min, (count.toLong() * chartHeight / most).toInt())
        for (bar in 0..<bars.count) {
            if (totals[bar] == 0) continue
            val x = barX(bars, bar)
            val barWidth = barX(bars, bar + 1) - x
            // A gap between bars once they are wide enough to spare it.
            val drawn = if (barWidth >= 3) barWidth - 1 else barWidth
            val errors = height(bars.count(TimeHistogram.ERROR, bar), UIScale.scale(MIN_LEVEL_HEIGHT))
            val warnings = height(bars.count(TimeHistogram.WARN, bar), UIScale.scale(MIN_LEVEL_HEIGHT))
            val total = minOf(chartHeight, maxOf(height(totals[bar], 1), errors + warnings))
            val errorPart = minOf(errors, total)
            val warningPart = minOf(warnings, total - errorPart)
            // Errors at the bottom, so they line up, then warnings, then the rest.
            var y = bottom
            for ((category, h) in listOf(
                TimeHistogram.ERROR to errorPart, TimeHistogram.WARN to warningPart, TimeHistogram.OTHER to total - errorPart - warningPart,
            )) {
                if (h <= 0) continue
                g2.color = colors[category]
                g2.fillRect(x, y - h, drawn, h)
                y -= h
            }
        }

        selection?.let {
            val x1 = barX(bars, it.first)
            g2.color = withAlpha(accent, 0.35f)
            g2.fillRect(x1, 0, barX(bars, it.last + 1) - x1, bottom)
        }

        paintLabels(g2, bars, bottom)
    }

    private fun paintLabels(g2: Graphics2D, bars: Bars, chartBottom: Int) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = labelFont
        g2.color = UIManager.getColor("Label.disabledForeground")
        val metrics = g2.fontMetrics
        val y = chartBottom + metrics.ascent + UIScale.scale(1)
        val inset = UIScale.scale(4)
        val from = bars.start(0)
        val to = bars.start(bars.count)
        val formatter = formatter(from, to, bars.start(1) - from)
        val left = formatter.format(Instant.ofEpochSecond(0, from))
        val right = formatter.format(Instant.ofEpochSecond(0, to))
        g2.drawString(left, inset, y)
        val rightX = width - inset - metrics.stringWidth(right)
        if (rightX > inset + metrics.stringWidth(left) + inset * 4) g2.drawString(right, rightX, y)
    }

    /** Date only when [from] and [to] are on different days; fractions of seconds only for bars shorter than a second. */
    private fun formatter(from: Long, to: Long, barSpan: Long): DateTimeFormatter {
        val date = Instant.ofEpochSecond(0, from).atZone(zone).toLocalDate() != Instant.ofEpochSecond(0, to - 1).atZone(zone).toLocalDate()
        val time = if (barSpan < SECOND) "HH:mm:ss.SSS" else "HH:mm:ss"
        return DateTimeFormatter.ofPattern(if (date) "yyyy-MM-dd $time" else time).withZone(zone)
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val bars = bars() ?: return null
        val bar = barAt(bars, e.x)
        val from = bars.start(bar)
        val to = bars.start(bar + 1)
        val formatter = formatter(bars.start(0), bars.start(bars.count), to - from)
        val parts = buildList {
            add("%,d records".format(bars.total(bar)))
            bars.count(TimeHistogram.ERROR, bar).takeIf { it > 0 }?.let { add("%,d errors".format(it)) }
            bars.count(TimeHistogram.WARN, bar).takeIf { it > 0 }?.let { add("%,d warnings".format(it)) }
        }
        val range = "${formatter.format(Instant.ofEpochSecond(0, from))} – ${formatter.format(Instant.ofEpochSecond(0, to))}"
        return "<html>$range<br>${parts.joinToString(", ")}<br><small>Click to go there, drag to filter by time</small></html>"
    }

    private fun withAlpha(color: Color, alpha: Float) = Color(color.red, color.green, color.blue, (alpha * 255).toInt())
}
