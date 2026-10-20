package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.extras.FlatSVGIcon
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.UIManager

/** logxy's own logo, for the window, the taskbar and About. */
object Logo {
    const val PATH = "/logxy.svg"

    fun icon(size: Int) = FlatSVGIcon(PATH.removePrefix("/"), size, size)
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
