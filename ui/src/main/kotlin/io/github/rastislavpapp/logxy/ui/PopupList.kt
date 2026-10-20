package io.github.rastislavpapp.logxy.ui

import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.UIManager

/** A button shown on a [PopupItem] while the mouse is over it, e.g. to edit or delete that item. */
class PopupTool(val icon: Icon, val tooltip: String, val action: () -> Unit)

/** One row of a [showPopupList]; [selected] rows get a check mark. */
class PopupItem(
    val text: String,
    val icon: Icon? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val tools: List<PopupTool> = emptyList(),
    val action: () -> Unit = {},
)

/**
 * A flat "text ⌄" button that opens a [showPopupList], like IntelliJ's run configuration widget. Without
 * [maxWidth] it never shrinks (no "All lev…"); [sizedFor] are all its texts, so its width stays put when the
 * text changes.
 */
fun dropDownButton(
    tooltip: String,
    maxWidth: Int? = null,
    sizedFor: List<String> = emptyList(),
    action: (JButton) -> Unit,
): JButton =
    object : JButton(Icons.get("general/chevronDown")) {
        override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
            val metrics = getFontMetrics(font)
            val widest = sizedFor.maxOfOrNull { metrics.stringWidth(it) } ?: 0
            width += maxOf(0, widest - metrics.stringWidth(text.orEmpty()))
            // Long texts would crowd the title bar; the list still shows them in full.
            if (maxWidth != null) width = minOf(width, look.scale(maxWidth))
        }

        override fun getMinimumSize(): Dimension = if (maxWidth == null) preferredSize else super.getMinimumSize()
    }.apply {
        horizontalTextPosition = SwingConstants.LEADING
        toolTipText = tooltip
        flat()
        addActionListener { action(this) }
    }

/**
 * Shows [items] in a popup under [invoker] (from [x] on), like IntelliJ's run configuration list: rows
 * rather than menu items, so that each can carry its own [PopupTool] buttons.
 */
fun showPopupList(invoker: JComponent, items: List<PopupItem>, x: Int = 0) {
    val popup = JPopupMenu()
    val hasChecks = items.any { it.selected }
    items.forEach { item ->
        if (item.separatorBefore && popup.componentCount > 0) popup.addSeparator()
        popup.add(PopupRow(item, popup, hasChecks))
    }
    popup.show(invoker, x, invoker.height)
}

private class PopupRow(private val item: PopupItem, private val popup: JPopupMenu, hasChecks: Boolean) :
    JPanel(MigLayout("insets 4 10 4 6, gap 6, fillx", "${if (hasChecks) "[16!]" else ""}[grow][]")) {

    private var hovered = false
    private val label = JLabel(item.text, item.icon, JLabel.LEADING).apply { isEnabled = item.enabled }
    private val tools = item.tools.map { tool ->
        toolBarButton(tool.icon, tool.tooltip) {
            popup.isVisible = false
            tool.action()
        }.apply {
            // Invisible, but still taking its space, until the row is hovered.
            isVisible = false
        }
    }

    init {
        isOpaque = false
        if (hasChecks) add(JLabel(if (item.selected) Icons.get("actions/checked") else null))
        add(label)
        tools.forEach { add(it) }
        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = updateHover()
            override fun mouseExited(e: MouseEvent) = updateHover()
        }
        addMouseListener(hover)
        tools.forEach { it.addMouseListener(hover) }
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!item.enabled || !contains(e.point)) return
                popup.isVisible = false
                item.action()
            }
        })
    }

    // Moving onto a tool button leaves the row itself, but the row stays hovered.
    private fun updateHover() {
        val now = item.enabled && getMousePosition(true) != null
        if (now == hovered) return
        hovered = now
        tools.forEach { it.isVisible = now }
        label.foreground = UIManager.getColor(if (now) "MenuItem.selectionForeground" else "MenuItem.foreground")
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!hovered) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIManager.getColor("MenuItem.selectionBackground")
            val inset = look.scale(4)
            val arc = look.scale(6)
            g2.fillRoundRect(inset, 0, width - 2 * inset, height, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}
