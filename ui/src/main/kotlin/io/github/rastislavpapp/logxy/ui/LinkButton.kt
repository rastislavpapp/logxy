package io.github.rastislavpapp.logxy.ui

import java.awt.Cursor
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import javax.swing.BorderFactory
import javax.swing.JButton

/** A borderless button in the link color, with a hand cursor and an underline while the mouse is over it. */
class LinkButton(label: String, action: () -> Unit) : JButton(label) {
    init {
        isContentAreaFilled = false
        look.link(this)
        border = BorderFactory.createEmptyBorder()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
        val plain = font
        // An underline attribute leaves the text as wide as it was, unlike an HTML <u>.
        val underlined = plain.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
        // Underlined while the mouse is over it or it has the keyboard focus, which replaces a focus ring.
        var hovered = false
        fun update() {
            font = if (hovered || isFocusOwner) underlined else plain
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                update()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                update()
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = update()
            override fun focusLost(e: FocusEvent) = update()
        })
    }
}
