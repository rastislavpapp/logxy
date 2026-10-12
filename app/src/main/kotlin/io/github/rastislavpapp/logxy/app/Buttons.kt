package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.JToolBar

/** A flat button, as in a toolbar; not focusable unless [focusable], so clicking it leaves the focus where it was. */
fun toolBarButton(icon: Icon?, tooltip: String, text: String = "", focusable: Boolean = false, action: (JButton) -> Unit) =
    JButton(text, icon).apply {
        toolTipText = tooltip
        flat(focusable)
        addActionListener { action(this) }
    }

/** A flat, not focusable toggle whose on state is plainly visible: filled with the accent color. */
fun accentToggle(icon: Icon?, tooltip: String, text: String = "") = JToggleButton(text, icon).apply {
    toolTipText = tooltip
    putClientProperty("FlatLaf.style", "selectedBackground: \$Component.accentColor; selectedForeground: #ffffff")
    flat(focusable = false)
}

/** A small option toggle, such as match case, shown inside a text field by [withToggles]. */
fun fieldToggle(icon: String, tooltip: String) = accentToggle(Icons.get(icon), tooltip).apply {
    putClientProperty("JButton.buttonType", "toolBarButton")
}

/** Shows [toggles] at the end of the field, inside it, like an editor's find options. */
fun JTextField.withToggles(vararg toggles: JToggleButton) = apply {
    putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, JToolBar().apply {
        isFloatable = false
        isOpaque = false
        border = null
        toggles.forEach(::add)
    })
}

internal fun AbstractButton.flat(focusable: Boolean = false) {
    putClientProperty("JButton.buttonType", "toolBarButton")
    isFocusable = focusable
}
