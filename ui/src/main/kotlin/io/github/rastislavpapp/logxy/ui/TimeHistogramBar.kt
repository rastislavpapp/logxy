package io.github.rastislavpapp.logxy.ui

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
import java.time.ZoneId
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.sqrt

/** The chart's height in unscaled pixels, without the time labels under it. */
internal const val HISTOGRAM_HEIGHT = 40

/** The space above the chart in unscaled pixels; as much is left under it when the time axis is not right below. */
internal const val HISTOGRAM_PADDING = 4
private const val MATCH_STRIP_HEIGHT = 3

/** Pixels per slice of time: wide enough that counts are not mostly noise, narrow enough to look continuous. */
private const val SLICE_WIDTH = 2

/** How far the mouse must move, in pixels, before a press becomes a drag. */
internal const val DRAG_THRESHOLD = 3

/**
 * Shown records over time: their volume as an area, with lines for warnings and errors over it on the same
 * scale, so heights compare truthfully. The scale is the square root, so one spike does not flatten the rest
 * and a burst that is a small share of all records still rises well off the bottom. The selected rows, or the
 * top row in view, are marked. The records a search found are a strip along the bottom, in every slice with a match;
 * [found] counts them on bins of their own, which are mapped onto the slices by time.
 * Clicking calls [onJump] with the slice there; dragging calls [onRange] with the times covered; the
 * context menu calls [onShowAllTimes] when [hasTimeRange].
 *
 * At the [pointer]'s time, set while the mouse is over this bar or another on its [timeline], a line crosses
 * the chart with the counts of the slice there beside it; [onPointer] is told where the mouse points on this bar.
 * Likewise [draggedTimes] shows a drag over such a bar, and [onDragged] is told of a drag on this one.
 *
 * A [TimeAxis] labels the times, under this bar or under the charts below it.
 */
class TimeHistogramBar(
    private val histogram: () -> TimeHistogram?,
    private val found: () -> TimeHistogram? = { null },
) : JComponent() {

    /**
     * Called with the slice clicked, from `from` to `to`, exclusive, and the most severe [TimeHistogram] category
     * in it.
     */
    var onJump: (from: Long, to: Long, category: Int) -> Unit = { _, _, _ -> }

    /** Called with the times dragged over, from `from` to `to`, exclusive. */
    var onRange: (from: Long, to: Long) -> Unit = { _, _ -> }

    /** The times being dragged over, or null once dropped. */
    var onDragged: (times: Pair<Long, Long>?) -> Unit = {}
    var onShowAllTimes: () -> Unit = {}
    var hasTimeRange: () -> Boolean = { false }

    /** Times of the first and last marked row; null if unknown. */
    var markedTimes: Pair<Long, Long>? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    /** The times being dragged over on a bar under this one, from and to (exclusive); null if none. */
    var draggedTimes: Pair<Long, Long>? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    /** The time the mouse points at, here or on a bar under it; null if none. */
    var pointer: Long? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    var onPointer: (time: Long?) -> Unit = {}

    private val zone = ZoneId.systemDefault()
    private var pressX = -1
    private var dragging = false
    private var selection: IntRange? = null

    /** The histogram as painted, sliced by [layout]. */
    private class Slices(val histogram: TimeHistogram, layout: SliceLayout, found: TimeHistogram?) {
        val first = layout.first
        val group = layout.group
        val count = layout.count
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
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                if (!SwingUtilities.isLeftMouseButton(e)) return
                pressX = e.x
                dragging = false
            }

            override fun mouseMoved(e: MouseEvent) = point(e.x)

            override fun mouseExited(e: MouseEvent) {
                pointer = null
                onPointer(null)
            }

            override fun mouseDragged(e: MouseEvent) {
                point(e.x)
                if (pressX < 0) return
                if (abs(e.x - pressX) > DRAG_THRESHOLD) dragging = true
                if (!dragging) return
                val slices = slices() ?: return
                val a = sliceAt(slices, pressX)
                val b = sliceAt(slices, e.x)
                val range = minOf(a, b)..maxOf(a, b)
                selection = range
                onDragged(slices.start(range.first) to slices.start(range.last + 1))
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                if (pressX < 0) return
                val slices = slices()
                val range = selection
                pressX = -1
                selection = null
                onDragged(null)
                repaint()
                if (slices == null) return
                if (dragging && range != null) {
                    onRange(slices.start(range.first), slices.start(range.last + 1))
                } else if (!dragging) {
                    val slice = sliceAt(slices, e.x)
                    val category = when {
                        slices.errors[slice] > 0 -> TimeHistogram.ERROR
                        slices.warnings[slice] > 0 -> TimeHistogram.WARN
                        else -> TimeHistogram.OTHER
                    }
                    onJump(slices.start(slice), slices.start(slice + 1), category)
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun point(x: Int) {
        val time = timeline(width)?.timeAt(x)
        pointer = time
        onPointer(time)
    }

    private fun showMenu(e: MouseEvent) {
        val popup = JPopupMenu()
        popup.add(JMenuItem("Show All Times").apply {
            isEnabled = hasTimeRange()
            addActionListener { onShowAllTimes() }
        })
        popup.show(this, e.x, e.y)
    }

    private val labelFont get() = look.smallFont

    private val chartTop get() = look.scale(HISTOGRAM_PADDING)
    private val chartBottom get() = chartTop + look.scale(HISTOGRAM_HEIGHT)

    override fun getPreferredSize() = Dimension(0, chartBottom)

    override fun getMinimumSize() = preferredSize

    /**
     * The times as this bar slices them when [width] pixels wide, also while it is hidden, so a chart of the same
     * width lines up with it; null while there is no histogram.
     */
    fun timeline(width: Int): Timeline? {
        val histogram = histogram() ?: return null
        val (first, group, count) = layout(histogram, width) ?: return null
        val from = histogram.binStart(first)
        return Timeline(from, histogram.binStart(first + group) - from, count, width)
    }

    private fun slices(): Slices? {
        val histogram = histogram() ?: return null
        val layout = layout(histogram, width) ?: return null
        return Slices(histogram, layout, found())
    }

    /** [count] slices of [group] bins each, the first starting at bin [first]. */
    private data class SliceLayout(val first: Int, val group: Int, val count: Int)

    /** The slices of [histogram] over [width] pixels; null if none. */
    private fun layout(histogram: TimeHistogram, width: Int): SliceLayout? {
        if (histogram.isEmpty || width <= 0) return null
        val most = maxOf(1, width / look.scale(SLICE_WIDTH))
        var group = 1
        fun count() = histogram.lastBin / group - histogram.firstBin / group + 1
        while (count() > most) group *= 2
        return SliceLayout(histogram.firstBin / group * group, group, count())
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
            val slices = slices() ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // One scale for all, so a line's height means as many records as the area's.
            val peak = sqrt(maxOf(1, slices.totals.max()).toDouble())
            paintVolume(g2, slices, peak)
            paintMatches(g2, slices)
            paintLine(g2, slices, slices.warnings, peak, look.warning)
            paintLine(g2, slices, slices.errors, peak, look.error)
            paintPeak(g2, slices)
            val accent = look.accent
            markedTimes?.let { (from, to) ->
                val x1 = timeX(slices, from)
                paintRegion(g2, x1, maxOf(x1 + look.scale(2), timeX(slices, to)), accent, 0.12f)
            }
            selection?.let {
                val from = sliceX(slices, it.first).toDouble()
                paintRegion(g2, from, sliceX(slices, it.last + 1).toDouble(), accent, 0.3f)
            }
            draggedTimes?.let { (from, to) -> paintRegion(g2, timeX(slices, from), timeX(slices, to), accent, 0.3f) }
            pointer?.let { paintPointer(g2, slices, it) }
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
        val strip = look.scale(MATCH_STRIP_HEIGHT)
        for (slice in 0..<slices.count) {
            if (matches[slice] == 0) continue
            val x = sliceX(slices, slice)
            g2.fillRect(x, chartBottom - strip, maxOf(1, sliceX(slices, slice + 1) - x), strip)
        }
    }

    /**
     * [counts] as a line on the volume's square-root scale, which lifts small counts off the bottom. Where the
     * count stays 0 the line is left out, so it does not lie along the bottom of the chart.
     */
    private fun paintLine(g2: Graphics2D, slices: Slices, counts: IntArray, peak: Double, color: Color) {
        if (counts.max() == 0) return
        g2.color = color
        g2.stroke = BasicStroke(look.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(path(slices, { counts[it] == 0 }) { sqrt(counts[it].toDouble()) / peak })
    }

    /** The count the top of the chart stands for, e.g. `1,234 / 2 min`, so the height can be read. */
    private fun paintPeak(g2: Graphics2D, slices: Slices) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = labelFont
        g2.color = UIManager.getColor("Label.disabledForeground")
        val text = "%,d / %s".format(slices.totals.max(), shortSpan(slices.start(1) - slices.start(0)))
        g2.drawString(text, look.scale(4), chartTop + g2.fontMetrics.ascent)
    }

    private fun shortSpan(nanos: Long): String = when {
        nanos % DAY == 0L -> "${nanos / DAY} d"
        nanos % HOUR == 0L -> "${nanos / HOUR} h"
        nanos % MINUTE == 0L -> "${nanos / MINUTE} min"
        nanos % SECOND == 0L -> "${nanos / SECOND} s"
        nanos % MILLI == 0L -> "${nanos / MILLI} ms"
        else -> "$nanos ns"
    }

    /**
     * A line through the middle of each slice at [fraction] of the chart's height, held level to both edges; a
     * stretch between two slices that are both [left out][leftOut] is not drawn.
     */
    private fun path(
        slices: Slices,
        leftOut: (Int) -> Boolean = { false },
        fraction: (Int) -> Double,
    ): Path2D.Double {
        val height = (chartBottom - chartTop).toDouble()
        fun y(slice: Int) = chartBottom - fraction(slice) * height
        val sliceWidth = width.toDouble() / slices.count
        val last = slices.count - 1
        return Path2D.Double().apply {
            moveTo(0.0, y(0))
            for (i in 0..last) {
                val x = (i + 0.5) * sliceWidth
                // The edge to the left of the first slice counts as that slice.
                if (leftOut(i) && leftOut(maxOf(0, i - 1))) moveTo(x, y(i)) else lineTo(x, y(i))
            }
            if (leftOut(last)) moveTo(width.toDouble(), y(last)) else lineTo(width.toDouble(), y(last))
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

    /** The line at [time], and beside it the slice's time and counts. */
    private fun paintPointer(g2: Graphics2D, slices: Slices, time: Long) {
        val slice = Math.floorDiv(time - slices.start(0), slices.start(1) - slices.start(0))
        if (slice !in 0..<slices.count) return
        val i = slice.toInt()
        val x = timeX(slices, time).toInt()
        paintPointerLine(g2, x, 0, chartBottom)
        g2.font = labelFont
        val multiDay = spansDays(slices.start(0), slices.start(slices.count), zone)
        val counts = buildList {
            add(count(slices.totals[i], "record"))
            slices.errors[i].takeIf { it > 0 }?.let { add(count(it, "error")) }
            slices.warnings[i].takeIf { it > 0 }?.let { add(count(it, "warning")) }
            slices.matches?.get(i)?.takeIf { it > 0 }?.let { add(count(it, "match", "matches")) }
        }
        val lines = listOf(
            formatTimeRange(slices.start(i), slices.start(i + 1), zone, multiDay) to
                UIManager.getColor("Label.disabledForeground"),
            counts.joinToString(" · ") to UIManager.getColor("Label.foreground"),
        )
        paintReadout(g2, x, chartTop, width, lines)
    }

    private fun withAlpha(color: Color, alpha: Float) =
        Color(color.red, color.green, color.blue, (alpha.coerceIn(0f, 1f) * 255).toInt())
}
