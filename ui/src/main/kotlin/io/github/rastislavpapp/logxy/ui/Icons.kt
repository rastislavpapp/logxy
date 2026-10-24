package io.github.rastislavpapp.logxy.ui

import java.awt.Component
import java.awt.Graphics
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
