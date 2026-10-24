package io.github.rastislavpapp.logxy.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.math.ceil

/** Pixels between the icon and the text, before scaling. */
private const val GAP = 5

/**
 * The icon at the start of a row's cell that expands or collapses the row. It uses IntelliJ's editor fold icons at
 * their own size. Every row of the column leaves [indent] for it, so the text lines up whether a row expands or not.
 */
internal class ExpandIcon {
    private var expand: Icon = Icons.expand
    private var collapse: Icon = Icons.collapse

    /**
     * Recoloured copies of the icons for selected rows: [0] of the expand icon, [1] of the collapse icon. A copy is
     * drawn again when the colour or the screen's scale changes.
     */
    private val tinted = arrayOfNulls<Tinted>(2)

    private class Tinted(val color: Color, val scale: Double, val image: BufferedImage)

    /** Loads the icons again, e.g. at another scale. */
    fun reload() {
        expand = Icons.expand
        collapse = Icons.collapse
        tinted.fill(null)
    }

    /** Pixels the icon and its gap take before the text. */
    fun indent(): Int = expand.iconWidth + look.scale(GAP)

    /**
     * The area of the cell where a click expands or collapses the row, when the icon is drawn at [x] and the row's
     * first line is [rowHeight] high. As in IntelliJ's editor, it is the whole first line from the cell's left edge
     * to the text, not only the icon's 9 pixels.
     */
    fun hitArea(x: Int, rowHeight: Int) = Rectangle(0, 0, x + indent(), rowHeight)

    /**
     * Paints the collapse icon if the row is [expanded], else the expand icon, at [x], centred vertically in the
     * row's first line, [rowHeight] high. In a [selected] row the icon is painted in [color], the row's text colour,
     * because its grey would vanish on the selection's background; IntelliJ's trees do the same.
     */
    fun paint(c: Component, g: Graphics, x: Int, rowHeight: Int, expanded: Boolean, selected: Boolean, color: Color) {
        val icon = if (expanded) collapse else expand
        val y = (rowHeight - icon.iconHeight) / 2
        if (!selected) return icon.paintIcon(c, g, x, y)
        val image = tinted(c, icon, (g as Graphics2D).transform.scaleX, color, expanded)
        g.drawImage(image, x, y, icon.iconWidth, icon.iconHeight, null)
    }

    private fun tinted(c: Component, icon: Icon, scale: Double, color: Color, expanded: Boolean): BufferedImage {
        val slot = if (expanded) 1 else 0
        tinted[slot]?.let { if (it.color == color && it.scale == scale) return it.image }
        // At the screen's resolution, so it stays as sharp as the icon itself on a scaled screen.
        val width = ceil(icon.iconWidth * scale).toInt()
        val height = ceil(icon.iconHeight * scale).toInt()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(scale, scale)
        icon.paintIcon(c, g, 0, 0)
        g.transform = AffineTransform()
        g.composite = AlphaComposite.SrcIn
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        tinted[slot] = Tinted(color, scale, image)
        return image
    }
}
