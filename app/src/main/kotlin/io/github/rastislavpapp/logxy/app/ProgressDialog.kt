package io.github.rastislavpapp.logxy.app

import net.miginfocom.swing.MigLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.Timer

/** Work quicker than this shows no dialog. */
private const val SHOW_DELAY_MILLIS = 300

/**
 * Shows the progress of slow work, such as opening files or copying many rows. [text] says what is being done and
 * [fraction] how far it got; both may be set from any thread, or [progress] read instead of [fraction]. An
 * [indeterminate] bar is for work of unknown size, such as walking a folder. Cancel and closing the dialog call
 * [onCancel].
 */
internal class ProgressDialog(
    owner: Window,
    title: String,
    indeterminate: Boolean = false,
    private val progress: (() -> Double)? = null,
    private val onCancel: () -> Unit,
) : JDialog(owner, title, ModalityType.APPLICATION_MODAL) {

    @Volatile
    var text = " "

    @Volatile
    var fraction = 0.0

    private val label = JLabel(" ")
    private val bar = JProgressBar(0, 1000).apply { isIndeterminate = indeterminate }
    private val timer = Timer(100) {
        label.text = text
        bar.value = ((progress?.invoke() ?: fraction) * 1000).toInt()
    }

    init {
        contentPane = JPanel(MigLayout("insets dialog, wrap 1", "[400!]")).apply {
            add(label, "wmin 0")
            add(bar, "growx")
            add(JButton("Cancel").apply { addActionListener { onCancel() } }, "right")
        }
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = onCancel()
        })
        pack()
        setLocationRelativeTo(owner)
        timer.start()
    }

    /** Shows the dialog after a moment unless the work is [finished] by then: quick work would only make it flash. */
    fun showLater(finished: () -> Boolean) {
        val timer = Timer(SHOW_DELAY_MILLIS) { if (!finished()) isVisible = true }
        timer.isRepeats = false
        timer.start()
    }

    override fun dispose() {
        timer.stop()
        super.dispose()
    }
}
