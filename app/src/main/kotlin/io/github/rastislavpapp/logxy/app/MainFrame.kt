package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGUtils
import io.github.rastislavpapp.logxy.core.LogFolders
import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.log.Level
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
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Base64
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
import javax.swing.JRadioButton
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.WindowConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

private const val CARD_PLACEHOLDER = "placeholder"
private const val CARD_TABS = "tabs"
private const val TAB_ARG = "--tab"
private const val SELECT_ARG = "--select"
private const val LEVEL_ARG = "--level"
private const val QUERY_ARG = "--query"
private const val LIVE_COLOR = "Actions.Green"
private const val FOLLOW_COLOR = "Component.accentColor"
private const val UNZIP_DIALOG_DELAY_MILLIS = 300

class MainFrame : JFrame("logxy") {

    private val shortcut = toolkit.menuShortcutKeyMaskEx
    private val tabs = JTabbedPane()
    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val statusBar = JPanel(MigLayout("insets 3 8 3 8, fill, gap 0, hidemode 3", "[grow][]"))
    private val fileSlot = Slot()
    private var lastDirectory: File? = AppState[AppState.LAST_DIRECTORY]?.let(::File)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    /** Where opened zip files are extracted to; made when the first one is opened. */
    private val spoolCreation = lazy { ZipSpool.forThisProcess(Settings.cacheDirectory()) }
    private val spool by spoolCreation

    private fun originOf(path: Path): ZipSpool.Origin? = if (spoolCreation.isInitialized()) spool.originOf(path) else null

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        iconImages = FlatSVGUtils.createWindowIconImages(Icons.LOGO_PATH)
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
            add(menuItem("Preferences", Icons.settings, KeyEvent.VK_S, shortcut or InputEvent.ALT_DOWN_MASK) {
                PreferencesDialog(this@MainFrame, previewFile = selectedTab()?.path).isVisible = true
            })
            addSeparator()
            add(menuItem("Exit", Icons.exit, null) { dispose() })
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
        add(JMenu("Help").apply {
            mnemonic = KeyEvent.VK_H
            add(menuItem("About logxy", Icons.logo(16), null) { showAbout(this@MainFrame) })
        })
        // The selected tab's controls; the space after them drags the window.
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

    private fun chooseAndOpenFiles() {
        val chooser = object : JFileChooser(lastDirectory) {
            override fun createDialog(parent: Component?): JDialog =
                super.createDialog(parent).also { WindowState.install(it, "file-chooser") }
        }.apply {
            dialogTitle = "Open Log Files"
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isMultiSelectionEnabled = true
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        lastDirectory = chooser.currentDirectory
        AppState[AppState.LAST_DIRECTORY] = chooser.currentDirectory.path
        openFiles(chooser.selectedFiles.map { it.toPath() })
    }

    /** A file to open; [inSubfolder] if it was found in a subfolder of an opened folder. */
    private class Found(val path: Path, val inSubfolder: Boolean = false)

    /**
     * Opens [paths], folders as the log files in them and zip files as the files in them: a single file
     * into a tab if none is open; otherwise the user chooses between merging them, with the current tab
     * or into a new one, and tabs of their own.
     */
    fun openFiles(paths: List<Path>) {
        val existing = paths.map { it.toAbsolutePath().normalize() }.distinct().filter(::checkOpenable)
        val empty = ArrayList<Path>()
        val found = existing.flatMap { path ->
            if (!Files.isDirectory(path)) return@flatMap listOf(Found(path))
            LogFolders.files(path).map { Found(it, it.parent != path) }.also { if (it.isEmpty()) empty.add(path) }
        }
        val zips = found.map { it.path }.filter(ZipSpool::isZip)
        if (zips.isEmpty()) {
            tellNothingIn(empty)
            return openFound(found)
        }
        unzipThen(zips) { files ->
            tellNothingIn(empty + zips.filter { files[it].isNullOrEmpty() })
            openFound(found.flatMap { item -> files[item.path]?.map { Found(it, item.inSubfolder) } ?: listOf(item) })
        }
    }

    private fun tellNothingIn(folders: List<Path>) {
        if (folders.isEmpty()) return
        JOptionPane.showMessageDialog(
            this, "No log files in:\n${folders.joinToString("\n")}", "Nothing to Open", JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun openFound(found: List<Found>) {
        val current = selectedTab()
        // Files the current tab already shows have nothing to add.
        val candidates = found.distinctBy { it.path }.filter { current == null || it.path !in current.paths }
        if (candidates.isEmpty()) return
        val all = candidates.map { it.path }
        if (current == null && all.size == 1) return openFile(all.single())
        val outsideSubfolders = candidates.filter { !it.inSubfolder }.map { it.path }.takeIf { it.size < all.size }
        val (choice, subfolders) = openChoice(all, outsideSubfolders, current?.paths) ?: return
        val openable = if (subfolders) all else outsideSubfolders!!
        if (openable.isEmpty()) return
        when (choice) {
            OpenChoice.MERGE_INTO_CURRENT -> mergeWithCurrent(current!!, openable, keepCurrent = false)
            OpenChoice.MERGE_WITH_CURRENT_IN_NEW -> mergeWithCurrent(current!!, openable, keepCurrent = true)
            OpenChoice.MERGE -> openMerged(openable)
            OpenChoice.TABS -> openable.forEach(::openFile)
        }
    }

    /** Arguments for [openArgs] that reopen the open tabs with their filters, e.g. after a restart. */
    fun tabArgs(): List<String> = buildList {
        for (i in 0..<tabs.tabCount) {
            val tab = tabs.getComponentAt(i) as LogTab
            add(TAB_ARG)
            // Extracted files by their zip, as the next app extracts them anew.
            tab.paths.forEach { add((originOf(it) ?: it).toString()) }
            tab.filter.minimumLevel?.let { add(LEVEL_ARG); add(it.name) }
            // Encoded, as Windows command lines do not reliably keep the quotes queries are full of.
            if (tab.filter.query.isNotBlank()) {
                add(QUERY_ARG)
                add(Base64.getUrlEncoder().encodeToString(tab.filter.query.toByteArray()))
            }
        }
        if (tabs.selectedIndex >= 0) add(SELECT_ARG).also { add(tabs.selectedIndex.toString()) }
    }

    /** Opens files given on the command line, or the tabs from [tabArgs]: each file group in a tab, merged if several. */
    fun openArgs(args: List<String>) {
        if (args.firstOrNull() != TAB_ARG) return openFiles(args.map { Path.of(it) })
        class Group(val paths: MutableList<String> = ArrayList(), var level: Level? = null, var query: String = "") {
            fun filter() = SavedFilter("", level, query).takeIf { it.minimumLevel != null || it.query.isNotBlank() }
        }
        val groups = ArrayList<Group>()
        var selected = 0
        val iterator = args.iterator()
        fun value() = if (iterator.hasNext()) iterator.next() else ""
        while (iterator.hasNext()) when (val arg = iterator.next()) {
            TAB_ARG -> groups += Group()
            LEVEL_ARG -> value().let { level -> groups.last().level = Level.entries.firstOrNull { it.name == level } }
            QUERY_ARG -> groups.last().query = try {
                String(Base64.getUrlDecoder().decode(value()))
            } catch (_: IllegalArgumentException) {
                ""
            }
            SELECT_ARG -> selected = value().toIntOrNull() ?: 0
            else -> groups.last().paths.add(arg)
        }
        val zips = groups.flatMap { it.paths }.mapNotNull(ZipSpool::parseOrigin).map { it.zip }.distinct()
        if (zips.isEmpty()) return openGroups(groups.map { it.paths.map(Path::of) to it.filter() }, selected)
        unzipThen(zips) {
            val paths = { group: Group -> group.paths.map { arg -> ZipSpool.parseOrigin(arg)?.let(spool::extractedFile) ?: Path.of(arg) } }
            openGroups(groups.map { paths(it) to it.filter() }, selected)
        }
    }

    /** Opens a tab for each group of files with its filter, then selects the tab at [selected]. */
    private fun openGroups(groups: List<Pair<List<Path>, SavedFilter?>>, selected: Int) {
        for ((paths, filter) in groups) {
            val openable = paths.filter(::checkOpenable)
            if (openable.isEmpty() || selectTab(openable)) continue
            addTab(LogTab(openable, ::openFiles, ::openEach, filter, ::originOf) { updateStatus() })
        }
        if (tabs.tabCount > 0) tabs.selectedIndex = selected.coerceIn(0, tabs.tabCount - 1)
    }

    /**
     * Unzips [zips] while showing how far it got, then calls [then] with the files of each zip; nothing
     * more if the user cancels or a zip cannot be read.
     */
    private fun unzipThen(zips: List<Path>, then: (Map<Path, List<Path>>) -> Unit) {
        lateinit var job: Job
        var unzipped = false
        val dialog = UnzipDialog(this) { job.cancel() }
        job = scope.launch {
            val files = LinkedHashMap<Path, List<Path>>()
            try {
                for ((i, zip) in zips.withIndex()) {
                    dialog.text = "Unzipping ${zip.fileName}…"
                    files[zip] = spool.extract(zip) { dialog.fraction = (i + it) / zips.size }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                dialog.dispose()
                val zip = zips.getOrNull(files.size) ?: zips.last()
                JOptionPane.showMessageDialog(
                    this@MainFrame, "Cannot unzip $zip:\n${e.message ?: e.javaClass.simpleName}", "Cannot Open File", JOptionPane.ERROR_MESSAGE,
                )
                return@launch
            } finally {
                unzipped = true
                dialog.dispose()
            }
            then(files)
        }
        // Quick ones finish before the dialog would only flash.
        // Not job.isActive: the job is still running while [then] asks how to open the files.
        Timer(UNZIP_DIALOG_DELAY_MILLIS) { if (!unzipped) dialog.isVisible = true }.apply { isRepeats = false }.start()
    }

    /** Opens each of [paths] in a tab of its own. */
    fun openEach(paths: List<Path>) = paths.forEach(::openFile)

    fun openFile(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        if (selectTab(listOf(absolute)) || !checkOpenable(absolute)) return
        addTab(LogTab(listOf(absolute), ::openFiles, ::openEach, origin = ::originOf) { updateStatus() })
    }

    private fun openMerged(paths: List<Path>) {
        if (selectTab(paths)) return
        addTab(LogTab(paths, ::openFiles, ::openEach, origin = ::originOf) { updateStatus() })
    }

    /**
     * Merges [tab]'s files with [paths] under its filter: in a new tab next to it if [keepCurrent],
     * else in its place.
     */
    private fun mergeWithCurrent(tab: LogTab, paths: List<Path>, keepCurrent: Boolean) {
        val merged = tab.paths + paths
        if (selectTab(merged)) return
        val index = tabs.indexOfComponent(tab)
        val mergedTab = LogTab(merged, ::openFiles, ::openEach, tab.filter, ::originOf) { updateStatus() }
        val at = if (keepCurrent) index + 1 else index
        tabs.insertTab(mergedTab.title, Icons.merged, mergedTab, mergedTab.description, at)
        tabs.selectedIndex = at
        if (!keepCurrent) closeTab(index + 1)
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
            !Files.isRegularFile(path) && !Files.isDirectory(path) -> "Not a regular file"
            !Files.isReadable(path) -> "No permission to read the file"
            else -> null
        }
        if (problem != null) JOptionPane.showMessageDialog(this, "$problem:\n$path", "Cannot Open File", JOptionPane.ERROR_MESSAGE)
        return problem == null
    }

    private enum class OpenChoice { MERGE_INTO_CURRENT, MERGE_WITH_CURRENT_IN_NEW, MERGE, TABS }

    /**
     * Asks how to open [paths], offering to merge them with the [current] tab's files if there is one, and
     * whether to include files in subfolders if [outsideSubfolders] (the files without those) is given;
     * null if cancelled. Merges of more files than [MergedLog.MAX_PARTS] are not offered.
     */
    private fun openChoice(paths: List<Path>, outsideSubfolders: List<Path>?, current: List<Path>?): Pair<OpenChoice, Boolean>? {
        val subfolders = outsideSubfolders?.let { JCheckBox("Include subfolders", true) }
        val message = JLabel()
        val options = JPanel(MigLayout("insets 8 0 0 0, wrap 1, gapy 2"))
        var choices = emptyList<Pair<OpenChoice, String>>()
        var radios = emptyList<JRadioButton>()
        // Only what the user clicked; a choice that was the only one left was not picked.
        var picked: OpenChoice? = null
        // The choices depend on how many files there are, which the checkbox changes.
        fun update() {
            val files = if (subfolders?.isSelected == false) outsideSubfolders else paths
            choices = openChoices(files, current)
            val group = ButtonGroup()
            radios = choices.map { (choice, label) ->
                JRadioButton(label, choice == picked).also { radio ->
                    group.add(radio)
                    radio.addActionListener { picked = choice }
                }
            }
            if (radios.none { it.isSelected }) radios.firstOrNull()?.isSelected = true
            options.removeAll()
            radios.forEach(options::add)
            message.text = when (files.size) {
                0 -> "No files outside subfolders."
                1 -> "Open ${files.single().fileName}:"
                else -> "Open ${files.size} files:"
            }
            SwingUtilities.getWindowAncestor(options)?.pack()
        }
        update()
        subfolders?.addActionListener { update() }
        val answer = JOptionPane.showConfirmDialog(
            this, listOfNotNull<JComponent>(message, options, subfolders).toTypedArray(), "Open Files", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
        )
        if (answer != JOptionPane.OK_OPTION) return null
        return choices[radios.indexOfFirst { it.isSelected }].first to (subfolders?.isSelected != false)
    }

    private fun openChoices(paths: List<Path>, current: List<Path>?): List<Pair<OpenChoice, String>> = buildList {
        val several = paths.size > 1
        if (current != null && current.size + paths.size <= MergedLog.MAX_PARTS) {
            add(OpenChoice.MERGE_INTO_CURRENT to "Merge into current tab")
            add(OpenChoice.MERGE_WITH_CURRENT_IN_NEW to "Merge with current tab in a new tab")
        }
        if (several && paths.size <= MergedLog.MAX_PARTS) add(OpenChoice.MERGE to if (current != null) "Merge only these files" else "Merge into one view")
        add(OpenChoice.TABS to if (several) "Open in separate tabs" else "Open in a new tab")
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
            val location = if (tab.isMerged) tab.sourceNames.joinToString(", ") else (originOf(tab.path) ?: tab.path).toString()
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
            tab.selectionSpan?.let {
                segment(JLabel("Selection: ${io.github.rastislavpapp.logxy.core.formatTimeSpan(it)}").apply {
                    toolTipText = "Time from the first selected row to the last"
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

    /** A place in the menu bar for one tab's controls at a time; it gives way when space is short. */
    private class Slot : JPanel(BorderLayout()) {
        init {
            isOpaque = false
        }

        // The menu bar's box layout would otherwise stretch or squeeze it.
        override fun getMaximumSize(): Dimension = preferredSize

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
                add(JLabel("Drop log files or folders here").apply { putClientProperty("FlatLaf.styleClass", "h2") })
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
