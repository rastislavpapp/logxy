package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.formatByteSize
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.WindowConstants

class MainFrame : JFrame("logxy") {

    private val statusPathLabel = JLabel("No file open")
    private val statusSizeLabel = JLabel()
    private var lastDirectory: File? = null

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        jMenuBar = createMenuBar()
        contentPane = JPanel(MigLayout("insets 0, gap 0, fill", "[grow]", "[grow][]")).apply {
            add(createPlaceholder(), "grow, wrap")
            add(createStatusBar(), "growx")
        }
        minimumSize = Dimension(480, 320)
        size = Dimension(1200, 800)
        setLocationRelativeTo(null)
    }

    private fun createMenuBar() = JMenuBar().apply {
        add(JMenu("File").apply {
            mnemonic = KeyEvent.VK_F
            add(JMenuItem("Open…").apply {
                mnemonic = KeyEvent.VK_O
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, toolkit.menuShortcutKeyMaskEx)
                addActionListener { chooseAndOpenFile() }
            })
            addSeparator()
            add(JMenuItem("Exit").apply {
                mnemonic = KeyEvent.VK_X
                addActionListener { dispose() }
            })
        })
        add(JMenu("View").apply {
            mnemonic = KeyEvent.VK_V
            add(JCheckBoxMenuItem("Dark Theme", Themes.current == Theme.DARK).apply {
                mnemonic = KeyEvent.VK_D
                addActionListener { Themes.switchTo(if (isSelected) Theme.DARK else Theme.LIGHT) }
            })
        })
    }

    private fun createPlaceholder() = JLabel("Open a log file with File → Open…", SwingConstants.CENTER).apply {
        putClientProperty("FlatLaf.styleClass", "h3")
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    private fun createStatusBar() = JPanel(MigLayout("insets 3 8 3 8, fill", "[grow][]")).apply {
        putClientProperty("FlatLaf.style", "border: 1,0,0,0,\$Component.borderColor")
        add(statusPathLabel, "growx, wmin 0")
        add(statusSizeLabel)
    }

    private fun chooseAndOpenFile() {
        val chooser = JFileChooser(lastDirectory).apply {
            dialogTitle = "Open Log File"
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        lastDirectory = file.parentFile
        openFile(file.toPath())
    }

    private fun openFile(path: Path) {
        val size = try {
            Files.size(path)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this, "Cannot open $path:\n${e.message}", "Open Failed", JOptionPane.ERROR_MESSAGE
            )
            return
        }
        title = "${path.fileName} — logxy"
        val exactSize = NumberFormat.getIntegerInstance().format(size)
        statusPathLabel.text = path.toString()
        statusPathLabel.toolTipText = path.toString()
        statusSizeLabel.text = "${formatByteSize(size)} ($exactSize bytes)"
    }
}
