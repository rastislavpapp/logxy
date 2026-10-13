package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.extras.FlatSVGIcon
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.UIManager

/**
 * IntelliJ Platform "new UI" icons (Apache 2.0), from the IntelliJ icons artifact. FlatSVGIcon picks
 * the `_dark` variant in dark themes by itself.
 */
object Icons {

    fun get(path: String, size: Int = 16): FlatSVGIcon = FlatSVGIcon("expui/$path.svg", size, size)

    const val LOGO_PATH = "/logxy.svg"
    fun logo(size: Int) = FlatSVGIcon(LOGO_PATH.removePrefix("/"), size, size)

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
    val group get() = get("general/groups")
    val star get() = get("nodes/star")
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
}

/** [icon] with a small dot in its lower right corner, e.g. to mark a file that is being written to. */
class DotIcon(private val icon: Icon, private val colorKey: String) : Icon {
    override fun getIconWidth() = icon.iconWidth
    override fun getIconHeight() = icon.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        icon.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = iconWidth / 2
            val dotX = x + iconWidth - size
            val dotY = y + iconHeight - size
            g2.color = c?.background ?: UIManager.getColor("Panel.background")
            g2.fillOval(dotX - 1, dotY - 1, size + 2, size + 2)
            g2.color = UIManager.getColor(colorKey) ?: Color(0x5FB865)
            g2.fillOval(dotX, dotY, size, size)
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
