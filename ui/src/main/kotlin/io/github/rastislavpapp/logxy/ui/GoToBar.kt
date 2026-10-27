package io.github.rastislavpapp.logxy.ui

import net.miginfocom.swing.MigLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.AbstractAction
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.UIManager

private const val ARC = 10

/** How long the note on where Go to went stays before the bar closes. */
private const val NOTE_MILLIS = 5_000

/** What going to a typed time did. */
sealed interface GoToOutcome {
    /** The text is no time; the bar stays open and shows [message]. */
    data class Invalid(val message: String) : GoToOutcome

    /** A row was selected. [note] tells why it is not the record at the time, e.g. the filter hides that one. */
    data class Went(val note: String?) : GoToOutcome
}

/**
 * Goes to a typed time, in the slot of the find view: a box floating over the rows like [FindBar] if [floats], else a
 * bar above them. [onGo] goes to the time typed when Enter is pressed; [onClose] is called whenever the bar hides.
 * [pickerLog] gives what the field's time picker opens on.
 */
class GoToBar(
    private val floats: Boolean,
    private val onGo: (text: String) -> GoToOutcome,
    pickerLog: () -> TimePickerLog?,
    private val onClose: () -> Unit,
) : JPanel(MigLayout("insets 6 8 6 6, gap 2 2, hidemode 3, wrap 2", "[260!, fill]4[]")) {

    /** The field the time is typed in. */
    val input = JTextField().apply {
        look.placeholder(this, "Time, e.g. 14:32:05 or -10m")
        toolTipText = "<html>A time, such as 2026-10-09 14:32:05.250, or 14:32 on the selected row's day;<br>" +
            "or a time from the selected row's: 1h30m forward, -10s back (units s, m, h, d)</html>"
        withToggles(timePickerButton(this, pickerLog))
        limitPaste()
    }
    private val note = JLabel()
    private val noteTimer = Timer(NOTE_MILLIS) { close() }.apply { isRepeats = false }

    val isOpen get() = isVisible

    init {
        isOpaque = false
        isVisible = false
        note.isVisible = false
        add(input)
        add(toolBarButton(Icons.closeSmall, "Close (Esc)") { close() })
        add(note, "span, gapleft 4")
        bind("ENTER", "logxyGoTo") { go() }
        bind("ESCAPE", "logxyCloseGoTo") { close() }
        // Back in the field after a note, the user means to go on: the bar no longer closes on its own.
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = noteTimer.stop()
        })
    }

    // Floating, rounded like the find bar; otherwise a bar with a line under it, above the rows.
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIManager.getColor("Panel.background")
            if (floats) {
                g2.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
                g2.color = look.border
                g2.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
            } else {
                g2.fillRect(0, 0, width, height)
                g2.color = look.border
                g2.drawLine(0, height - 1, width, height - 1)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun bind(key: String, name: String, action: () -> Unit) {
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
        input.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    /** Opens the bar with [time] typed in, selected so that typing replaces it. */
    fun open(time: String) {
        noteTimer.stop()
        showNote(null)
        look.outlineError(input, false)
        input.text = time
        isVisible = true
        input.selectAll()
        input.requestFocusInWindow()
    }

    fun close() {
        noteTimer.stop()
        if (!isVisible) return
        isVisible = false
        onClose()
    }

    private fun go() {
        when (val outcome = onGo(input.text)) {
            is GoToOutcome.Invalid -> {
                look.outlineError(input, true)
                showNote(outcome.message, error = true)
            }
            is GoToOutcome.Went -> {
                look.outlineError(input, false)
                if (outcome.note == null) return close()
                // The rows have the focus now; the note stays a moment before the bar closes.
                showNote(outcome.note)
                noteTimer.restart()
            }
        }
    }

    private fun showNote(text: String?, error: Boolean = false) {
        note.text = text
        note.foreground = if (error) look.errorText else UIManager.getColor("Label.foreground")
        if (note.isVisible == (text != null)) return
        note.isVisible = text != null
        revalidate()
        repaint()
    }
}
