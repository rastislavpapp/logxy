package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import net.miginfocom.swing.MigLayout
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.function.IntConsumer
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.WindowConstants

private const val CARD_PLACEHOLDER = "placeholder"
private const val CARD_TABS = "tabs"
private const val LIVE_COLOR = "Actions.Green"
private const val FOLLOW_COLOR = "Component.accentColor"

class MainFrame : JFrame("logxy") {

    private val shortcut = toolkit.menuShortcutKeyMaskEx
    private val tabs = JTabbedPane()
    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val statusBar = JPanel(MigLayout("insets 3 8 3 8, fill, gap 0, hidemode 3", "[grow][]"))
    private val fileSlot = Slot(shrinks = true)
    private val filterSlot = Slot(shrinks = false)
    private var lastDirectory: File? = AppState[AppState.LAST_DIRECTORY]?.let(::File)

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        iconImages = listOf(16, 32, 48, 64, 128).map { Icons.logFile.derive(it, it).image }
        jMenuBar = createMenuBar()
        // The title bar is full of controls, and the tab names the file; the title still names the window elsewhere.
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false)

        tabs.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        // Like IntelliJ's editor tabs: a darker strip, the selected tab in the content's color (joined to it), accent underline.
        tabs.putClientProperty(
            "FlatLaf.style",
            "tabHeight: 36; tabInsets: 4,14,4,10; showContentSeparator: true; " +
                "background: darken(\$Panel.background,4%); selectedBackground: \$Panel.background; " +
                "hoverColor: darken(\$Panel.background,8%); showTabSeparators: true; " +
                "underlineColor: \$Component.accentColor; inactiveUnderlineColor: \$Component.borderColor",
        )
        tabs.isOpaque = true
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true)
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_TOOLTIPTEXT, "Close (${keyText(KeyEvent.VK_W, shortcut)})")
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, IntConsumer { closeTab(it) })
        tabs.addChangeListener {
            fileSlot.show(selectedTab()?.fileControls)
            filterSlot.show(selectedTab()?.filterControls)
            updateStatus()
        }
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcut), "logxyEditFilter")
        rootPane.actionMap.put("logxyEditFilter", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                selectedTab()?.focusFilter()
            }
        })

        center.add(DropZone(), CARD_PLACEHOLDER)
        center.add(tabs, CARD_TABS)
        statusBar.putClientProperty("FlatLaf.style", "border: 1,0,0,0,\$Component.borderColor")
        contentPane = JPanel(MigLayout("insets 0, gap 0, fill", "[grow]", "[grow][]")).apply {
            add(center, "grow, wrap")
            add(statusBar, "growx")
        }
        rootPane.transferHandler = FileDropHandler(::openFiles)
        minimumSize = Dimension(480, 320)
        size = Dimension(1200, 800)
        setLocationRelativeTo(null)
        WindowState.install(this, WindowState.MAIN)
        updateStatus()
    }

    private fun selectedTab() = tabs.selectedComponent as LogTab?

    private fun keyText(key: Int, modifiers: Int = 0) = KeyStroke.getKeyStroke(key, modifiers).toString()
        .replace("pressed ", "").replace("ctrl", "Ctrl").replace("shift", "Shift").replace("meta", "⌘").replace(" ", "+")

    private fun menuItem(text: String, icon: Icon?, key: Int?, modifiers: Int = 0, action: () -> Unit) = JMenuItem(text, icon).apply {
        if (key != null) accelerator = KeyStroke.getKeyStroke(key, modifiers)
        addActionListener { action() }
    }

    private fun createMenuBar() = JMenuBar().apply {
        add(JMenu("File").apply {
            mnemonic = KeyEvent.VK_F
            add(menuItem("Open", Icons.open, KeyEvent.VK_O, shortcut) { chooseAndOpenFiles() })
            add(menuItem("Reload", Icons.refresh, KeyEvent.VK_F5) { selectedTab()?.reloadFile() })
            add(menuItem("Close Tab", Icons.close, KeyEvent.VK_W, shortcut) { if (tabs.selectedIndex >= 0) closeTab(tabs.selectedIndex) })
            addSeparator()
            add(menuItem("Exit", Icons.exit, null) { dispose() })
        })
        add(JMenu("Settings").apply {
            mnemonic = KeyEvent.VK_S
            add(menuItem("Parsers", Icons.settings, null) { showParsers() })
            add(menuItem("Log Level Mapping", Icons.levels, null) { LogLevelMappingDialog(this@MainFrame).isVisible = true })
            add(severalFilesMenu())
        })
        add(JMenu("View").apply {
            mnemonic = KeyEvent.VK_V
            add(themeMenu())
            add(JCheckBoxMenuItem("Integrated Title Bar", AppState[AppState.TITLE_BAR] != "false").apply {
                toolTipText = "Menu and toolbar inside the window title, IntelliJ-style (applies after a restart)"
                addActionListener {
                    AppState[AppState.TITLE_BAR] = isSelected.toString()
                    JOptionPane.showMessageDialog(this@MainFrame, "The title bar changes when logxy is started again.", "Title Bar", JOptionPane.INFORMATION_MESSAGE)
                }
            })
        })
        // The selected tab's controls; the space between them drags the window.
        add(Box.createHorizontalStrut(12))
        add(fileSlot)
        add(object : JPanel() {
            // Not glue: FlatLaf sizes glue in the title bar by itself, which crowds the controls in narrow windows.
            override fun getMinimumSize() = Dimension(0, 0)
            override fun getPreferredSize() = Dimension(0, 0)
            override fun getMaximumSize() = Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }.apply {
            isOpaque = false
            putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true)
        })
        add(filterSlot)
    }

    private fun themeMenu() = JMenu("Theme").apply {
        icon = Icons.theme
        val group = ButtonGroup()
        fun item(theme: ThemeInfo) = JRadioButtonMenuItem(theme.name, theme.className == Themes.current.className).apply {
            group.add(this)
            addActionListener { Themes.switchTo(theme) }
        }
        Themes.CORE.forEach { add(item(it)) }
        addSeparator()
        add(JMenu("More Dark Themes").apply { Themes.INTELLIJ.filter { it.dark }.forEach { add(item(it)) } })
        add(JMenu("More Light Themes").apply { Themes.INTELLIJ.filter { !it.dark }.forEach { add(item(it)) } })
    }

    private fun showParsers() {
        ParsersDialog(this, selectedTab()?.path, offerUse = false, select = null).isVisible = true
    }

    private fun chooseAndOpenFiles() {
        val chooser = object : JFileChooser(lastDirectory) {
            override fun createDialog(parent: Component?): JDialog =
                super.createDialog(parent).also { WindowState.install(it, "file-chooser") }
        }.apply {
            dialogTitle = "Open Log Files"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        lastDirectory = chooser.currentDirectory
        AppState[AppState.LAST_DIRECTORY] = chooser.currentDirectory.path
        openFiles(chooser.selectedFiles.map { it.toPath() })
    }

    /** Opens [paths]: one in a tab; several merged or in tabs of their own, as the user prefers. */
    fun openFiles(paths: List<Path>) {
        val openable = paths.map { it.toAbsolutePath().normalize() }.distinct().filter(::checkOpenable)
        when {
            openable.size == 1 -> openFile(openable.single())
            openable.size > 1 -> when (severalFilesChoice(openable.size)) {
                SeveralFiles.MERGE -> openMerged(openable)
                SeveralFiles.TABS -> openable.forEach(::openFile)
                null -> Unit
            }
        }
    }

    /** Opens each of [paths] in a tab of its own. */
    fun openEach(paths: List<Path>) = paths.forEach(::openFile)

    fun openFile(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        if (selectTab(listOf(absolute)) || !checkOpenable(absolute)) return
        addTab(LogTab(listOf(absolute), ::openFiles, ::openEach) { updateStatus() })
    }

    private fun openMerged(paths: List<Path>) {
        if (selectTab(paths)) return
        addTab(LogTab(paths, ::openFiles, ::openEach) { updateStatus() })
    }

    /** Selects the tab showing exactly [paths], if there is one. */
    private fun selectTab(paths: List<Path>): Boolean {
        val existing = (0..<tabs.tabCount).firstOrNull { (tabs.getComponentAt(it) as LogTab).paths.toSet() == paths.toSet() }
        if (existing != null) tabs.selectedIndex = existing
        return existing != null
    }

    private fun addTab(tab: LogTab) {
        tabs.addTab(tab.title, if (tab.isMerged) Icons.merged else Icons.logFile, tab, tab.description)
        tabs.selectedComponent = tab
        cards.show(center, CARD_TABS)
    }

    /** Tells the user why [path] cannot be opened, if it cannot. */
    private fun checkOpenable(path: Path): Boolean {
        val problem = when {
            !Files.exists(path) -> "File not found"
            Files.isDirectory(path) -> "Folders cannot be opened, only files"
            !Files.isRegularFile(path) -> "Not a regular file"
            !Files.isReadable(path) -> "No permission to read the file"
            else -> null
        }
        if (problem != null) JOptionPane.showMessageDialog(this, "$problem:\n$path", "Cannot Open File", JOptionPane.ERROR_MESSAGE)
        return problem == null
    }

    private enum class SeveralFiles(val key: String, val label: String) {
        MERGE("merge", "Merge into One View"),
        TABS("tabs", "Open in Separate Tabs"),
    }

    /** The remembered choice for opening several files, or the user's answer; null if cancelled. */
    private fun severalFilesChoice(count: Int): SeveralFiles? {
        SeveralFiles.entries.firstOrNull { it.key == AppState[AppState.SEVERAL_FILES] }?.let { return it }
        val remember = JCheckBox("Don't ask again (Settings → Opening Several Files changes it)")
        val options = SeveralFiles.entries.map { it.label }.toTypedArray()
        val answer = JOptionPane.showOptionDialog(
            this, arrayOf("Open $count files:", remember), "Open Files",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0],
        )
        val choice = SeveralFiles.entries.getOrNull(answer) ?: return null
        if (remember.isSelected) AppState[AppState.SEVERAL_FILES] = choice.key
        return choice
    }

    /** Settings → Opening Several Files: ask, merge, or separate tabs. */
    private fun severalFilesMenu() = JMenu("Opening Several Files").apply {
        val group = ButtonGroup()
        fun item(label: String, key: String?) = JRadioButtonMenuItem(label).apply {
            group.add(this)
            addActionListener { AppState[AppState.SEVERAL_FILES] = key }
        }
        val items = listOf(item("Ask Each Time", null) to null) + SeveralFiles.entries.map { item(it.label, it.key) to it.key }
        items.forEach { (item, _) -> add(item) }
        // The dialog's "Don't ask again" changes the setting too.
        addMenuListener(object : javax.swing.event.MenuListener {
            override fun menuSelected(e: javax.swing.event.MenuEvent) {
                val current = AppState[AppState.SEVERAL_FILES]
                items.forEach { (item, key) -> item.isSelected = key == current || (key == null && SeveralFiles.entries.none { it.key == current }) }
            }
            override fun menuDeselected(e: javax.swing.event.MenuEvent) = Unit
            override fun menuCanceled(e: javax.swing.event.MenuEvent) = Unit
        })
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getComponentAt(index) as LogTab
        tabs.removeTabAt(index)
        tab.dispose()
        if (tabs.tabCount == 0) cards.show(center, CARD_PLACEHOLDER)
        updateStatus()
    }

    private fun updateStatus() {
        for (i in 0..<tabs.tabCount) {
            val tab = tabs.getComponentAt(i) as LogTab
            tabs.setIconAt(i, tabIcon(tab))
            // A merge may have left out files without timestamps.
            tabs.setTitleAt(i, tab.title)
            tabs.setToolTipTextAt(i, tab.description)
        }
        val tab = selectedTab()
        title = if (tab == null) "logxy" else "${tab.title} — logxy"
        statusBar.removeAll()
        if (tab == null) {
            statusBar.add(JLabel("No file open"))
        } else {
            val status = tab.status
            val numbers = NumberFormat.getIntegerInstance()
            val location = if (tab.isMerged) MergedLog.sourceNames(tab.paths).joinToString(", ") else tab.path.toString()
            statusBar.add(JLabel(location).apply { toolTipText = "<html>" + tab.description.replace("\n", "<br>") + "</html>" }, "growx, wmin 0")
            fun segment(component: JComponent) {
                statusBar.add(JSeparator(SwingConstants.VERTICAL), "growy, gap 10 10")
                statusBar.add(component)
            }
            status.message?.let { segment(JLabel(it).apply { foreground = UIManager.getColor("Label.disabledForeground") }) }
            if (status.records != null) {
                val unit = status.unit + if (status.records == 1) "" else "s"
                val text = when {
                    status.filtering -> "Filtering ${numbers.format(status.records)} $unit…"
                    status.shown == null -> "${numbers.format(status.records)} $unit"
                    else -> "${numbers.format(status.shown)} of ${numbers.format(status.records)} $unit"
                }
                val filtered = status.filtering || status.shown != null
                segment(JLabel(text, if (filtered) Icons.filter else null, SwingConstants.LEADING).apply {
                    toolTipText = if (filtered) "A filter is active" else null
                })
            }
            status.size?.let { segment(JLabel(io.github.rastislavpapp.logxy.core.formatByteSize(it))) }
            if (status.records != null) {
                val text = when {
                    status.live -> "Live"
                    status.following -> "Static · following"
                    else -> "Static"
                }
                val icon = when {
                    status.live -> DotIcon(Icons.follow, LIVE_COLOR)
                    status.following -> DotIcon(Icons.follow, FOLLOW_COLOR)
                    else -> null
                }
                segment(JLabel(text, icon, SwingConstants.LEADING).apply {
                    toolTipText = when {
                        status.live -> "The file is being written to; new lines appear as they arrive"
                        status.following -> "The file is not changing; Follow keeps its end in view once it does"
                        else -> "The file is not changing"
                    }
                })
            }
            status.indexMillis?.let { segment(JLabel("indexed in ${numbers.format(it)} ms").apply { foreground = UIManager.getColor("Label.disabledForeground") }) }
        }
        statusBar.revalidate()
        statusBar.repaint()
    }

    // Green: the file is being written to; accent: followed, though nothing is written yet.
    private fun tabIcon(tab: LogTab): Icon {
        val icon = if (tab.isMerged) Icons.merged else Icons.logFile
        return when {
            tab.status.live -> DotIcon(icon, LIVE_COLOR)
            tab.status.following -> DotIcon(icon, FOLLOW_COLOR)
            else -> icon
        }
    }

    /** A place in the menu bar for one tab's controls at a time; when space is short, only one that [shrinks] gives way. */
    private class Slot(private val shrinks: Boolean) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
        }

        // The menu bar's box layout would otherwise stretch or squeeze it.
        override fun getMaximumSize(): Dimension = preferredSize
        override fun getMinimumSize(): Dimension = if (shrinks) super.getMinimumSize() else preferredSize

        fun show(controls: JComponent?) {
            removeAll()
            if (controls != null) add(controls)
            revalidate()
            repaint()
        }
    }

    /** What an empty window shows: a place to drop files, and a button to open them. */
    private inner class DropZone : JPanel(MigLayout("fill", "[center]", "[center]")) {
        init {
            add(JPanel(MigLayout("insets 32 48 32 48, wrap 1, gapy 12", "[center]")).apply {
                isOpaque = false
                add(JLabel(Icons.get("fileTypes/text", 64)))
                add(JLabel("Drop log files here").apply { putClientProperty("FlatLaf.styleClass", "h2") })
                add(JLabel("or").apply { foreground = UIManager.getColor("Label.disabledForeground") })
                add(JButton("Open File", Icons.open).apply {
                    toolTipText = keyText(KeyEvent.VK_O, shortcut)
                    addActionListener { chooseAndOpenFiles() }
                })
            }.also { it.border = DashedBorder })
        }
    }

    private object DashedBorder : javax.swing.border.AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = UIManager.getColor("Component.borderColor")
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(8f, 6f), 0f)
                g2.drawRoundRect(x + 1, y + 1, width - 3, height - 3, 16, 16)
            } finally {
                g2.dispose()
            }
        }
    }
}
