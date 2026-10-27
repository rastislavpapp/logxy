package io.github.rastislavpapp.logxy.ui

import net.miginfocom.swing.MigLayout
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.UIManager

/** What going to a typed time did. */
sealed interface GoToOutcome {
    /** The text is no time; the dialog stays open and shows [message]. */
    data class Invalid(val message: String) : GoToOutcome

    /** A row was selected. [note] tells why it is not the record at the time, e.g. the filter hides that one. */
    data class Went(val note: String?) : GoToOutcome
}

/**
 * Asks for a time to go to, as an editor's Go to Line dialog asks for a line. It opens with [time] typed in, selected
 * so that typing replaces it. OK goes there with [onGo] and closes, unless the text is no time: then the dialog says
 * why and stays open. [pickerLog] gives what the field's time picker opens on. Once closed, [note] tells why the row
 * gone to is not the record at the time, if it is not.
 */
class GoToDialog(
    owner: Window?,
    time: String,
    private val onGo: (text: String) -> GoToOutcome,
    pickerLog: () -> TimePickerLog?,
) : JDialog(owner, "Go to Time", ModalityType.DOCUMENT_MODAL) {

    var note: String? = null
        private set

    private val input = JTextField(time, 28).apply {
        look.placeholder(this, "Time, e.g. 14:32:05 or -10m")
        toolTipText = "<html>A time, such as 2026-10-09 14:32:05.250, or 14:32 on the selected row's day;<br>" +
            "or a time from the selected row's: 1h30m forward, -10s back (units s, m, h, d)</html>"
        limitPaste()
        // As in a filter's time value, a click opens a time picker.
        picksTimeOnClick(pickerLog) { true }
    }

    /** Under the field: what can be typed, or why the text is no time. */
    private val message = JLabel(HINT).apply { foreground = UIManager.getColor("Label.disabledForeground") }

    init {
        val ok = JButton("OK").apply { addActionListener { go() } }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        // The same width, never narrower than their text, whatever the look's font.
        val buttons = JPanel(MigLayout("insets 0", "push[]6[]")).apply {
            add(ok, "sg button, wmin pref")
            add(cancel, "sg button, wmin pref")
        }
        contentPane = JPanel(MigLayout("insets dialog, wrap 2", "[]6[grow, fill]")).apply {
            add(JLabel("Time:"))
            add(input)
            add(message, "skip 1, gapbottom para")
            add(buttons, "span, growx")
        }
        rootPane.defaultButton = ok
        // An open time picker takes Esc first.
        val escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        rootPane.registerKeyboardAction({ dispose() }, escape, JComponent.WHEN_IN_FOCUSED_WINDOW)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = false
        pack()
        setLocationRelativeTo(owner)
        input.selectAll()
    }

    private fun go() {
        when (val outcome = onGo(input.text)) {
            is GoToOutcome.Invalid -> {
                look.outlineError(input, true)
                message.text = outcome.message
                message.foreground = look.errorText
                pack()
                input.requestFocusInWindow()
            }
            is GoToOutcome.Went -> {
                note = outcome.note
                dispose()
            }
        }
    }

    private companion object {
        const val HINT = "e.g. 2026-10-09 14:32:05, 14:32, 1h30m or -10s"
    }
}
