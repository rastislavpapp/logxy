package io.github.rastislavpapp.logxy.ui

import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.DocumentFilter.FilterBypass

/** A flat button, as in a toolbar; not focusable unless [focusable], so clicking it leaves the focus where it was. */
fun toolBarButton(
    icon: Icon?,
    tooltip: String,
    text: String = "",
    focusable: Boolean = false,
    action: (JButton) -> Unit,
) =
    JButton(text, icon).apply {
        toolTipText = tooltip
        flat(focusable)
        addActionListener { action(this) }
    }

/** A flat, not focusable toggle whose on state is plainly visible: filled with the accent color. */
fun accentToggle(icon: Icon?, tooltip: String, text: String = ""): JToggleButton =
    look.accentToggle(text, icon).apply {
        toolTipText = tooltip
        isFocusable = false
    }

/** A small option toggle, such as match case, shown inside a text field by [withToggles]. */
fun fieldToggle(icon: String, tooltip: String) = accentToggle(Icons.get(icon), tooltip)

/** Shows [toggles] at the end of the field, inside it, like an editor's find options. */
fun JTextField.withToggles(vararg toggles: JToggleButton) = apply {
    val bar = JToolBar().apply {
        isFloatable = false
        isOpaque = false
        border = null
        toggles.forEach(::add)
    }
    look.trailingComponent(this, bar)
}

/**
 * Keeps only the first non-empty line of a multi-line text pasted or set into this field, cut to
 * [MAX_FIELD_INSERT] characters: Swing measures a field's whole line on every caret move.
 */
fun JTextField.limitPaste() = apply {
    // A replace (paste, setText) sees the text before the field's document turns line breaks into spaces.
    (document as AbstractDocument).documentFilter = object : DocumentFilter() {
        override fun insertString(fb: FilterBypass, offset: Int, text: String?, attr: AttributeSet?) =
            super.insertString(fb, offset, text?.let(::fieldInsert), attr)

        override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) =
            super.replace(fb, offset, length, text?.let(::fieldInsert), attrs)
    }
}

private fun fieldInsert(text: String): String {
    val line = if ('\n' in text || '\r' in text) text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() else text
    return line.take(MAX_FIELD_INSERT)
}

private const val MAX_FIELD_INSERT = 4000

internal fun AbstractButton.flat(focusable: Boolean = false) {
    look.flat(this)
    isFocusable = focusable
}
