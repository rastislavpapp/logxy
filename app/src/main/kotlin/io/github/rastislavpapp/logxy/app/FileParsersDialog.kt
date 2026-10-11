package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.parse.LogFormat
import net.miginfocom.swing.MigLayout
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Chooses the parser of each of a merge's [files], starting from [current]; [formats] is null if cancelled. */
class FileParsersDialog(
    owner: Window,
    files: List<Path>,
    names: List<String>,
    descriptions: List<String>,
    current: List<LogFormat?>,
) : JDialog(owner, "Parser per File", ModalityType.APPLICATION_MODAL) {

    var formats: List<LogFormat?>? = null
        private set

    private val apply = JButton("Apply")
    private val parsers: FileParsersTable = FileParsersTable(files, names, descriptions, current) {
        apply.isEnabled = parsers.allFit
        apply.toolTipText = parsers.notReady
    }

    init {
        apply.isEnabled = false
        apply.apply {
            addActionListener {
                parsers.stopEditing()
                formats = parsers.formats
                dispose()
            }
        }
        contentPane = JPanel(MigLayout("fill, insets dialog, wrap 1", "[grow, fill]", "[][grow][]")).apply {
            add(JLabel("Parsers of ${files.size} files").apply { putClientProperty("FlatLaf.styleClass", "h3") })
            add(parsers.component, "grow")
            add(dialogButtons(apply, JButton("Cancel").apply { addActionListener { dispose() } }), "gaptop para")
        }
        rootPane.defaultButton = apply
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        pack()
        setLocationRelativeTo(owner)
        WindowState.install(this, "file-parsers")
    }

    override fun dispose() {
        parsers.dispose()
        super.dispose()
    }
}
