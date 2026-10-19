package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.util.UIScale
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.UIManager

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val READOUT_PADDING = 4

/** Wide enough to keep the dots of a slot chart's slice, beside the pointer, clear of the box. */
private const val READOUT_GAP = 10

/**
 * The times a histogram bar spans as painted: [count] slices of [sliceWidth] nanoseconds from [from], over the
 * [width] pixels of the bar. The slot chart under it paints on the same times, so the two line up.
 */
data class Timeline(val from: Long, val sliceWidth: Long, val count: Int, val width: Int) {
    val to: Long get() = from + sliceWidth * count

    fun x(time: Long): Double = (time - from).toDouble() / (to - from) * width

    fun timeAt(x: Int): Long = from + ((to - from) * (x.coerceIn(0, width - 1).toDouble() / width)).toLong()
}

/** The pointer's place over a timeline: a thin line across from [top] to [bottom]. */
internal fun paintPointerLine(g2: Graphics2D, x: Int, top: Int, bottom: Int) {
    g2.color = UIManager.getColor("Label.foreground").let { Color(it.red, it.green, it.blue, 140) }
    g2.fillRect(x, top, 1, bottom - top)
}

/**
 * [lines] of text, each in its color, in a box beside [x] from [top]: on its right, or on its left if it would
 * not fit in [width].
 */
internal fun paintReadout(g2: Graphics2D, x: Int, top: Int, width: Int, lines: List<Pair<String, Color>>) {
    if (lines.isEmpty()) return
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    val metrics = g2.fontMetrics
    val padding = UIScale.scale(READOUT_PADDING)
    val boxWidth = lines.maxOf { metrics.stringWidth(it.first) } + 2 * padding
    val boxHeight = lines.size * metrics.height + padding
    val gap = UIScale.scale(READOUT_GAP)
    val left = if (x + gap + boxWidth <= width) x + gap else maxOf(0, x - gap - boxWidth)
    val background = UIManager.getColor("Table.background")
    g2.color = Color(background.red, background.green, background.blue, 230)
    g2.fillRect(left, top, boxWidth, boxHeight)
    g2.color = UIManager.getColor("Component.borderColor")
    g2.drawRect(left, top, boxWidth - 1, boxHeight - 1)
    var y = top + padding / 2 + metrics.ascent
    for ((text, color) in lines) {
        g2.color = color
        g2.drawString(text, left + padding, y)
        y += metrics.height
    }
}

/** The time from [from] to [to] (nanoseconds), to the millisecond if shorter than a second; dated if [multiDay]. */
internal fun formatTimeRange(from: Long, to: Long, zone: ZoneId, multiDay: Boolean): String {
    val time = if (to - from < NANOS_PER_SECOND) "HH:mm:ss.SSS" else "HH:mm:ss"
    val formatter = DateTimeFormatter.ofPattern(if (multiDay) "yyyy-MM-dd $time" else time).withZone(zone)
    return "${formatter.format(Instant.ofEpochSecond(0, from))} – ${formatter.format(Instant.ofEpochSecond(0, to))}"
}
