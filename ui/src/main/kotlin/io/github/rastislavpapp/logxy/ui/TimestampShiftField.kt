package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.merge.TimestampShift
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * A field for a file's timestamp shift, as [TimestampShift] reads it: `2` or `-5` hours, `+5:30` with minutes, empty
 * for none. Up and Down step it by an hour; text that is no shift is outlined as an error.
 */
class TimestampShiftField(shift: Long = 0) : JTextField(TimestampShift.format(shift), 8) {

    /** The shift typed, in nanoseconds; null while the text is no shift. */
    val shift: Long? get() = TimestampShift.parse(text)

    init {
        look.placeholder(this, "None")
        toolTipText = "Hours added to the file's times, e.g. 2 or -5, or with minutes, e.g. +5:30; " +
            "Up and Down step by an hour"
        bind("UP", "logxyShiftUp") { step(up = true) }
        bind("DOWN", "logxyShiftDown") { step(up = false) }
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = check()
            override fun removeUpdate(e: DocumentEvent) = check()
            override fun changedUpdate(e: DocumentEvent) = check()
        })
    }

    private fun bind(key: String, name: String, action: () -> Unit) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    // Text that is no shift steps from none.
    private fun step(up: Boolean) {
        text = TimestampShift.format(TimestampShift.step(shift ?: 0, up))
    }

    private fun check() = look.outlineError(this, shift == null)
}
