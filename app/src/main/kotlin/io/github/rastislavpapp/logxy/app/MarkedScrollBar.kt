package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.ui.FlatScrollBarUI
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.abs

private const val MARK_HEIGHT = 2
private const val THUMB_ALPHA = 0.7f

/** How strongly rows are marked that are no denser in warnings or errors than the rows overall. */
private const val MIN_MARK_ALPHA = 0.3f

/** How far from a mark, in pixels, a click still goes to it. */
private const val CLICK_TOLERANCE = 3

/** What to mark along a scroll bar over the rows it scrolls. */
interface RowMarks {
    /** Rows of [severity] ([SEVERITY_ERROR] or [SEVERITY_WARN]) from [from] to [to], exclusive. */
    fun count(from: Int, to: Int, severity: Int): Int

    /** The first row from [from] to [to], exclusive, of [severity]; -1 if none. */
    fun first(from: Int, to: Int, severity: Int): Int
}

/**
 * A vertical scroll bar that marks rows with warnings and errors, in the table's level colors; the more
 * of them there are at a spot compared to the whole, the stronger the mark, so clusters stand out.
 * Clicking a mark calls [onMarkClicked] with its first row.
 */
class MarkedScrollBar : JScrollBar(VERTICAL) {

    var marks: RowMarks? = null
        set(value) {
            field = value
            repaint()
        }

    var onMarkClicked: (row: Int) -> Unit = {}

    override fun updateUI() {
        setUI(MarkedUI())
    }

    private inner class MarkedUI : FlatScrollBarUI() {
        override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) {
            super.paintTrack(g, c, trackBounds)
            paintMarks(g, trackBounds)
        }

        // See-through, so marks stay visible under it.
        override fun paintThumb(g: Graphics, c: JComponent, thumbBounds: Rectangle) {
            val g2 = g.create() as Graphics2D
            try {
                if (marks != null) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, THUMB_ALPHA)
                super.paintThumb(g2, c, thumbBounds)
            } finally {
                g2.dispose()
            }
        }

        override fun createTrackListener(): TrackListener = MarkTrackListener()

        /** The rows that pixel [y] of [track] stands for, from and to (exclusive). */
        private fun rowsAt(y: Int, track: Rectangle): Pair<Int, Int> {
            val total = maximum - minimum
            val from = (y.toLong() * total / track.height).toInt()
            val to = maxOf(from + 1, ((y + 1).toLong() * total / track.height).toInt())
            return minimum + from to minimum + to
        }

        private fun paintMarks(g: Graphics, track: Rectangle) {
            val marks = marks ?: return
            if (maximum - minimum <= 0 || track.height <= 0) return
            // Errors last, so they are not covered by a warning next to them.
            for ((severity, color) in listOf(SEVERITY_WARN to "Actions.Yellow", SEVERITY_ERROR to "Actions.Red")) {
                val shades = shades(marks, severity, track)
                val base = UIManager.getColor(color)
                for (y in shades.indices) {
                    // A mark reaches MARK_HEIGHT pixels down, so single rows stay visible; overlaps take the stronger shade.
                    var alpha = 0f
                    for (above in maxOf(0, y - MARK_HEIGHT + 1)..y) alpha = maxOf(alpha, shades[above])
                    if (alpha <= 0f) continue
                    g.color = Color(base.red, base.green, base.blue, (alpha * 255).toInt())
                    g.fillRect(track.x, track.y + y, track.width, 1)
                }
            }
        }

        /** Per pixel of [track], how strongly to mark [severity]: 0 where there is none, more where it is denser than overall. */
        private fun shades(marks: RowMarks, severity: Int, track: Rectangle): FloatArray {
            val counts = IntArray(track.height)
            val sizes = IntArray(track.height)
            var allCounts = 0L
            var allRows = 0L
            var densest = 0.0
            for (y in counts.indices) {
                val (from, to) = rowsAt(y, track)
                counts[y] = marks.count(from, to, severity)
                sizes[y] = to - from
                allCounts += counts[y]
                allRows += sizes[y]
                densest = maxOf(densest, counts[y].toDouble() / sizes[y])
            }
            val overall = allCounts.toDouble() / maxOf(1L, allRows)
            return FloatArray(track.height) { y ->
                if (counts[y] == 0) return@FloatArray 0f
                val density = counts[y].toDouble() / sizes[y]
                val above = if (densest > overall) ((density - overall) / (densest - overall)).coerceIn(0.0, 1.0) else 0.0
                MIN_MARK_ALPHA + (1 - MIN_MARK_ALPHA) * above.toFloat()
            }
        }

        private fun severityAt(pixel: Int, track: Rectangle): Int {
            val marks = marks ?: return 0
            val (from, to) = rowsAt(pixel, track)
            return when {
                marks.count(from, to, SEVERITY_ERROR) > 0 -> SEVERITY_ERROR
                marks.count(from, to, SEVERITY_WARN) > 0 -> SEVERITY_WARN
                else -> 0
            }
        }

        /** The first row of the most severe mark near [y] (scroll bar coordinates), the nearest one if several; -1 if none. */
        private fun markedRowNear(y: Int): Int {
            val marks = marks ?: return -1
            val track = trackBounds
            if (maximum - minimum <= 0 || track.height <= 0) return -1
            var best = -1
            var bestSeverity = 0
            // A mark is drawn below its pixel, so look further up than down.
            for (pixel in (y - track.y - CLICK_TOLERANCE - MARK_HEIGHT + 1..y - track.y + CLICK_TOLERANCE).sortedBy { abs(it - (y - track.y)) }) {
                if (pixel !in 0..<track.height) continue
                val severity = severityAt(pixel, track)
                if (severity > bestSeverity) {
                    bestSeverity = severity
                    best = pixel
                }
            }
            if (best < 0) return -1
            val (from, to) = rowsAt(best, track)
            return marks.first(from, to, bestSeverity)
        }

        private fun onMark(e: MouseEvent) = !thumbBounds.contains(e.point) && markedRowNear(e.y) >= 0

        private inner class MarkTrackListener : TrackListener() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && !thumbBounds.contains(e.point)) {
                    val row = markedRowNear(e.y)
                    if (row >= 0) return onMarkClicked(row)
                }
                super.mousePressed(e)
            }

            override fun mouseMoved(e: MouseEvent) {
                super.mouseMoved(e)
                cursor = if (onMark(e)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
            }

            override fun mouseExited(e: MouseEvent) {
                super.mouseExited(e)
                cursor = null
            }
        }
    }
}
