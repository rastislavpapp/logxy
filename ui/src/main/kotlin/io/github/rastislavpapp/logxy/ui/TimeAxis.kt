package io.github.rastislavpapp.logxy.ui

import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.UIManager

private const val TICK_HEIGHT = 3

/** Space a time label needs around it, in pixels. */
private const val LABEL_GAP = 24

/** In nanoseconds. */
internal const val MILLI = 1_000_000L
internal const val SECOND = 1_000 * MILLI
internal const val MINUTE = 60 * SECOND
internal const val HOUR = 60 * MINUTE
internal const val DAY = 24 * HOUR

private val TICK_STEPS = longArrayOf(
    MILLI, 2 * MILLI, 5 * MILLI, 10 * MILLI, 20 * MILLI, 50 * MILLI, 100 * MILLI, 200 * MILLI, 500 * MILLI,
    SECOND, 2 * SECOND, 5 * SECOND, 10 * SECOND, 15 * SECOND, 30 * SECOND,
    MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
    HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY, 7 * DAY, 30 * DAY,
)

/**
 * Ticks at round times of [timeline] at this strip's width, labeled as far apart as the labels need: one strip under
 * the histogram and the charts above it, which share its width and so its times.
 */
class TimeAxis(private val timeline: (width: Int) -> Timeline?) : JComponent() {

    private val zone = ZoneId.systemDefault()
    private val labelFont get() = look.smallFont

    override fun getPreferredSize(): Dimension {
        val labels = getFontMetrics(labelFont).height
        return Dimension(0, look.scale(TICK_HEIGHT) + labels + look.scale(2))
    }

    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIManager.getColor("Table.background")
            g2.fillRect(0, 0, width, height)
            g2.color = look.border
            g2.fillRect(0, height - 1, width, 1)
            timeline(width)?.let { paintTicks(g2, it) }
        } finally {
            g2.dispose()
        }
    }

    private fun paintTicks(g2: Graphics2D, timeline: Timeline) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = labelFont
        val metrics = g2.fontMetrics
        val from = timeline.from
        val to = timeline.to
        val multiDay = localDay(from) != localDay(to - 1)
        val span = to - from
        var step = TICK_STEPS.last()
        var formatter = tickFormatter(step, multiDay)
        for (candidate in TICK_STEPS) {
            val candidateFormatter = tickFormatter(candidate, multiDay)
            val label = candidateFormatter.format(Instant.ofEpochSecond(0, from))
            val labelWidth = metrics.stringWidth(label) + look.scale(LABEL_GAP)
            if (candidate.toDouble() / span * width >= labelWidth) {
                step = candidate
                formatter = candidateFormatter
                break
            }
        }
        // Round in local time, so hours and days start where the clock says.
        val offset = zone.rules.getOffset(Instant.ofEpochSecond(0, from)).totalSeconds * SECOND
        var tick = Math.floorDiv(from + offset + step - 1, step) * step - offset
        val y = look.scale(TICK_HEIGHT) + metrics.ascent
        while (tick < to) {
            val x = timeline.x(tick).toInt()
            g2.color = look.border
            g2.fillRect(x, 0, 1, look.scale(TICK_HEIGHT))
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
}
