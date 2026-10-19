package io.github.rastislavpapp.logxy.app

import java.awt.Dimension
import javax.swing.JTextArea
import javax.swing.UIManager

/**
 * An explanatory text in the disabled-label color that wraps to the width it is given. Unlike a [javax.swing.JLabel]
 * it has no minimum width, so a long note cannot widen the page it is on; add it to a MigLayout with `growx`.
 */
class Note(text: String) : JTextArea(text) {
    init {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
}
