package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.IntConsumer
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.WindowConstants

private const val CARD_PLACEHOLDER = "placeholder"
private const val CARD_TABS = "tabs"

class MainFrame : JFrame("logxy") {

    private val statusPathLabel = JLabel()
    private val statusInfoLabel = JLabel()
    private val tabs = JTabbedPane()
    private val cards = CardLayout()
    private val center = JPanel(cards)
    private var lastDirectory: File? = null

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        jMenuBar = createMenuBar()

        tabs.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true)
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_TOOLTIPTEXT, "Close")
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, IntConsumer { closeTab(it) })
        tabs.addChangeListener { updateStatus() }

        center.add(createPlaceholder(), CARD_PLACEHOLDER)
        center.add(tabs, CARD_TABS)
        contentPane = JPanel(MigLayout("insets 0, gap 0, fill", "[grow]", "[grow][]")).apply {
            add(center, "grow, wrap")
            add(createStatusBar(), "growx")
        }
        rootPane.transferHandler = FileDropHandler(::openFiles)
        minimumSize = Dimension(480, 320)
        size = Dimension(1200, 800)
        setLocationRelativeTo(null)
        updateStatus()
    }

    private fun createMenuBar() = JMenuBar().apply {
        val shortcut = toolkit.menuShortcutKeyMaskEx
        add(JMenu("File").apply {
            mnemonic = KeyEvent.VK_F
            add(JMenuItem("Open…").apply {
                mnemonic = KeyEvent.VK_O
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut)
                addActionListener { chooseAndOpenFiles() }
            })
            add(JMenuItem("Close Tab").apply {
                mnemonic = KeyEvent.VK_C
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcut)
                addActionListener { if (tabs.selectedIndex >= 0) closeTab(tabs.selectedIndex) }
            })
            addSeparator()
            add(JMenuItem("Exit").apply {
                mnemonic = KeyEvent.VK_X
                addActionListener { dispose() }
            })
        })
        add(JMenu("Settings").apply {
            mnemonic = KeyEvent.VK_S
            add(JMenuItem("Formats and Level Names…").apply {
                mnemonic = KeyEvent.VK_F
                addActionListener {
                    val tab = tabs.selectedComponent as LogTab?
                    FormatsDialog(this@MainFrame, tab?.path, offerUse = false, select = null).isVisible = true
                }
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
        add(statusInfoLabel)
    }

    private fun chooseAndOpenFiles() {
        val chooser = JFileChooser(lastDirectory).apply {
            dialogTitle = "Open Log Files"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        lastDirectory = chooser.currentDirectory
        openFiles(chooser.selectedFiles.map { it.toPath() })
    }

    fun openFiles(paths: List<Path>) = paths.forEach(::openFile)

    fun openFile(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        val existing = (0..<tabs.tabCount).firstOrNull { (tabs.getComponentAt(it) as LogTab).path == absolute }
        if (existing != null) {
            tabs.selectedIndex = existing
            return
        }
        val problem = when {
            !Files.exists(absolute) -> "File not found"
            Files.isDirectory(absolute) -> "Folders cannot be opened, only files"
            !Files.isRegularFile(absolute) -> "Not a regular file"
            !Files.isReadable(absolute) -> "No permission to read the file"
            else -> null
        }
        if (problem != null) {
            JOptionPane.showMessageDialog(this, "$problem:\n$absolute", "Cannot Open File", JOptionPane.ERROR_MESSAGE)
            return
        }
        val tab = LogTab(absolute, ::openFiles) { updateStatus() }
        tabs.addTab(absolute.fileName.toString(), null, tab, absolute.toString())
        tabs.selectedComponent = tab
        cards.show(center, CARD_TABS)
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getComponentAt(index) as LogTab
        tabs.removeTabAt(index)
        tab.dispose()
        if (tabs.tabCount == 0) cards.show(center, CARD_PLACEHOLDER)
        updateStatus()
    }

    private fun updateStatus() {
        val tab = tabs.selectedComponent as LogTab?
        title = if (tab == null) "logxy" else "${tab.path.fileName} — logxy"
        statusPathLabel.text = tab?.path?.toString() ?: "No file open"
        statusPathLabel.toolTipText = tab?.path?.toString()
        statusInfoLabel.text = tab?.statusText ?: ""
    }
}
