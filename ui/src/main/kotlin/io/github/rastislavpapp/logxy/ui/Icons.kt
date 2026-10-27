package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.config.PaletteColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** IntelliJ Platform "new UI" icons (Apache 2.0), from the IntelliJ icons artifact, loaded by the [Look]. */
object Icons {

    fun get(path: String, size: Int = 16): Icon = look.icon(path, size)

    val open get() = get("general/open")
    val close get() = get("general/close")
    val closeSmall get() = get("general/closeSmall")
    val exit get() = get("general/exit")
    val copy get() = get("general/copy")
    val refresh get() = get("general/refresh")
    val follow get() = get("general/scrollDown")
    val filter get() = get("general/filter")
    val settings get() = get("general/settings")
    val levels get() = get("nodes/sortBySeverity")
    val memory get() = get("actions/profileMemory")
    val add get() = get("general/add")
    val remove get() = get("general/remove")
    val group get() = get("general/groups")
    val star get() = get("nodes/star")
    val rowHighlights get() = get("toolwindows/palette")
    val logFile get() = get("fileTypes/text")
    val merged get() = get("vcs/merge")
    val theme get() = get("general/layout")
    val run get() = get("run/run")
    val pause get() = get("run/pause")
    val clear get() = get("general/delete")
    val advanced get() = get("general/edit")
    val columns get() = get("nodes/dataColumn")
    val warning get() = get("status/warning")
    val fits get() = get("general/greenCheckmark")

    // IntelliJ's editor fold icons, at their own 9 px: expand points right, collapse down.
    val expand get() = get("gutter/unfold", 9)
    val collapse get() = get("gutter/fold", 9)
}

/** A square in the row background of [color], as rows with a row highlight of that color show it. */
class SwatchIcon(private val color: PaletteColor) : Icon {
    override fun getIconWidth() = look.scale(SIZE)
    override fun getIconHeight() = look.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val size = iconWidth
        val arc = look.scale(ARC)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = look.rowHighlight(color)
            g2.fillRoundRect(x, y, size - 1, size - 1, arc, arc)
            // The light colors are close to a light menu's background: an edge keeps them apart.
            g2.color = look.border
            g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val SIZE = 14
        const val ARC = 4
    }
}

/**
 * A small calendar page, drawn in the gray of the "new UI" icons, as the icons artifact has none: a frame with a band
 * on top, two rings, and a few days.
 */
object CalendarIcon : Icon {
    private const val SIZE = 16
    private const val LIGHT = 0x6C707E
    private const val DARK = 0xCED0D6

    override fun getIconWidth() = look.scale(SIZE)
    override fun getIconHeight() = look.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(x, y)
            // Drawn on a 16 px grid, scaled as the screen is.
            val scale = iconWidth / SIZE.toDouble()
            g2.scale(scale, scale)
            g2.color = Color(if (look.isDark) DARK else LIGHT)
            g2.drawRoundRect(2, 3, 12, 11, 3, 3)
            g2.drawLine(2, 6, 14, 6)
            g2.drawLine(5, 1, 5, 4)
            g2.drawLine(11, 1, 11, 4)
            for (row in 0..1) {
                for (column in 0..2) g2.fillRect(4 + column * 3, 8 + row * 3, 2, 2)
            }
        } finally {
            g2.dispose()
        }
    }
}

/** [icon] followed by a small down arrow, for buttons that open a menu. */
class DropDownIcon(private val icon: Icon) : Icon {
    private val arrow = Icons.get("general/chevronDown", 12)

    override fun getIconWidth() = icon.iconWidth + arrow.iconWidth
    override fun getIconHeight() = maxOf(icon.iconHeight, arrow.iconHeight)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        icon.paintIcon(c, g, x, y + (iconHeight - icon.iconHeight) / 2)
        arrow.paintIcon(c, g, x + icon.iconWidth, y + (iconHeight - arrow.iconHeight) / 2)
    }
}
