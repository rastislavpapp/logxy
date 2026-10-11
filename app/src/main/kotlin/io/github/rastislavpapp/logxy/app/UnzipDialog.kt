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

/** How far unzipping got, with a Cancel button that calls [onCancel]. [text] and [fraction] may be set from any thread. */
internal class UnzipDialog(owner: Window, private val onCancel: () -> Unit) :
    JDialog(owner, "Opening Zip Files", ModalityType.APPLICATION_MODAL) {

    @Volatile
    var text = " "

    @Volatile
    var fraction = 0.0

    private val label = JLabel(" ")
    private val bar = JProgressBar(0, 1000)
    private val timer = Timer(100) {
        label.text = text
        bar.value = (fraction * 1000).toInt()
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

    override fun dispose() {
        timer.stop()
        super.dispose()
    }
}
