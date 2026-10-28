package io.github.rastislavpapp.logxy.ui

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.UIManager
import kotlin.math.PI
import kotlin.math.sin

/** The height of a panel's progress strip, painted over its top inset. */
internal const val PROGRESS_HEIGHT = 3

/**
 * How many milliseconds the progress strip's segment takes to cross the panel while the progress is unknown, and the
 * placeholders take to pulse once.
 */
internal const val CYCLE_MILLIS = 1200.0

/**
 * Paints a progress strip [width] wide at the top of [g]: filled to [fraction], from 0 to 1, or a segment crossing it
 * while the progress is unknown, [fraction] below 0.
 */
internal fun paintProgressStrip(g: Graphics, width: Int, fraction: Double) {
    g.color = look.accent
    if (fraction >= 0) {
        g.fillRect(0, 0, (width * fraction.coerceIn(0.0, 1.0)).toInt(), PROGRESS_HEIGHT)
    } else {
        val segment = width / 4
        val phase = System.currentTimeMillis() % CYCLE_MILLIS.toLong() / CYCLE_MILLIS
        val x = (phase * (width + segment)).toInt() - segment
        g.fillRect(x, 0, segment, PROGRESS_HEIGHT)
    }
}

/**
 * Pulsing boxes where the rows of [table] will be, while they are counted: a short one where its numbers go and a
 * longer one after it.
 */
internal class PlaceholderRows(private val table: JTable) : JComponent() {
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = table.background
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val pulse = (1 + sin(System.currentTimeMillis() / CYCLE_MILLIS * 2 * PI)) / 2
            val base = UIManager.getColor("Label.disabledForeground")
            g2.color = Color(base.red, base.green, base.blue, (40 + 40 * pulse).toInt())
            val metrics = table.getFontMetrics(table.font)
            val rowHeight = table.rowHeight
            val boxHeight = rowHeight / 2
            val countWidth = metrics.stringWidth("000,000")
            val textX = metrics.stringWidth("0,000,000,000") + 24
            val arc = look.scale(6)
            var y = table.tableHeader.preferredSize.height + (rowHeight - boxHeight) / 2
            var row = 0
            while (y < height) {
                // Varied, but the same on every repaint, so the boxes do not jump about.
                val shape = ((row + 1) * -7046029254386353131L ushr 56) / 255.0
                val count = (countWidth * (0.5 + shape / 2)).toInt()
                g2.fillRoundRect(textX - 24 - count, y, count, boxHeight, arc, arc)
                val text = ((width - textX - 16) * (0.3 + 0.55 * shape)).toInt()
                if (text > 0) g2.fillRoundRect(textX, y, text, boxHeight, arc, arc)
                y += rowHeight
                row++
            }
        } finally {
            g2.dispose()
        }
    }
}
