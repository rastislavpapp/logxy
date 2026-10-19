package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import net.miginfocom.swing.MigLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JToolBar
import javax.swing.UIManager

/**
 * [view] (a list or table) with a row of icon buttons attached to its top edge, in one shared border, like IntelliJ's
 * toolbar decorator. The border is the panel's, not the scroll pane's, so focusing the view draws no ring around it.
 */
class ListToolbar(view: JComponent) : JPanel(MigLayout("insets 0, gap 0, fill", "[grow]", "[][][grow]")) {

    private val toolbar = JToolBar().apply {
        isFloatable = false
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
    }

    init {
        border = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"))
        add(toolbar, "growx, wrap")
        add(JSeparator(), "growx, wrap")
        add(JScrollPane(view).apply { border = null }, "grow")
    }

    /** Adds a button with [icon] and [tooltip] at the end of the toolbar. */
    fun addButton(icon: Icon, tooltip: String, action: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isFocusable = false
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            addActionListener { action() }
            toolbar.add(this)
        }
}

/** A borderless button in FlatLaf's link color, with a hand cursor and an underline while the mouse is over it. */
class LinkButton(label: String, action: () -> Unit) : JButton(label) {
    init {
        isContentAreaFilled = false
        isFocusPainted = false
        // FlatLaf's button border and minimum width would pad and clip a text this plain.
        border = BorderFactory.createEmptyBorder()
        putClientProperty(FlatClientProperties.MINIMUM_WIDTH, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIManager.getColor("Component.linkColor")
        addActionListener { action() }
        val plain = font
        // An underline attribute leaves the text as wide as it was, unlike an HTML <u>.
        val underlined = plain.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                font = underlined
            }

            override fun mouseExited(e: MouseEvent) {
                font = plain
            }
        })
    }
}
