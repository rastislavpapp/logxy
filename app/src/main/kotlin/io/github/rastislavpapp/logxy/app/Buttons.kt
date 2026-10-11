package io.github.rastislavpapp.logxy.app

import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JToggleButton

/** A flat button, as in a toolbar; not focusable unless [focusable], so clicking it leaves the focus where it was. */
fun toolBarButton(icon: Icon?, tooltip: String, text: String = "", focusable: Boolean = false, action: (JButton) -> Unit) =
    JButton(text, icon).apply {
        toolTipText = tooltip
        flat(focusable)
        addActionListener { action(this) }
    }

/** A toggle whose on state is plainly visible: filled with the accent color. Flat and not focusable unless not [flat]. */
fun accentToggle(icon: Icon?, tooltip: String, text: String = "", flat: Boolean = true) = JToggleButton(text, icon).apply {
    toolTipText = tooltip
    putClientProperty("FlatLaf.style", "selectedBackground: \$Component.accentColor; selectedForeground: #ffffff")
    if (flat) flat(focusable = false)
}

internal fun AbstractButton.flat(focusable: Boolean = false) {
    putClientProperty("JButton.buttonType", "toolBarButton")
    isFocusable = focusable
}
