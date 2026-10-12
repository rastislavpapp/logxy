package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGUtils
import io.github.rastislavpapp.logxy.core.LogFolders
import io.github.rastislavpapp.logxy.core.archive.ArchiveOrigin
import io.github.rastislavpapp.logxy.core.archive.CompressedSpool
import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import net.miginfocom.swing.MigLayout
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Base64
import java.util.function.IntConsumer
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
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
private const val EXTRACT_DIALOG_DELAY_MILLIS = 300

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

    /** Where opened .gz and .zst files are decompressed to; made when the first one is opened. */
    private val compressedSpoolCreation = lazy { CompressedSpool.forThisProcess(Settings.cacheDirectory()) }
    private val compressedSpool by compressedSpoolCreation

    private fun originOf(path: Path): ArchiveOrigin? =
        (if (spoolCreation.isInitialized()) spool.originOf(path) else null)
            ?: if (compressedSpoolCreation.isInitialized()) compressedSpool.originOf(path) else null

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
        add(JMenu("Edit").apply {
            mnemonic = KeyEvent.VK_E
            add(menuItem("Find", Icons.get("general/search"), KeyEvent.VK_F, shortcut) { selectedTab()?.showFind() })
            add(menuItem("Find Next", null, KeyEvent.VK_F3) { selectedTab()?.findNext(forward = true) })
            add(menuItem("Find Previous", null, KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK) { selectedTab()?.findNext(forward = false) })
            addSeparator()
            add(menuItem("Edit Filter", Icons.filter, KeyEvent.VK_F, shortcut or InputEvent.SHIFT_DOWN_MASK) { selectedTab()?.focusFilter() })
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
     * Opens [paths], folders as the log files in them, zip files as the files in them, and compressed
     * files (.gz, .zst) as their decompressed content: a single file into a tab if none is open;
     * otherwise the user chooses between merging them, with the current tab or into a new one,
     * and tabs of their own.
     */
    fun openFiles(paths: List<Path>) {
        val existing = paths.map { it.toAbsolutePath().normalize() }.distinct().filter(::checkOpenable)
        val empty = ArrayList<Path>()
        val found = existing.flatMap { path ->
            if (!Files.isDirectory(path)) return@flatMap listOf(Found(path))
            LogFolders.files(path).map { Found(it, it.parent != path) }.also { if (it.isEmpty()) empty.add(path) }
        }
        val archives = found.map { it.path }.filter(::isArchive)
        if (archives.isEmpty()) {
            tellNothingIn(empty)
            return openFound(found)
        }
        extractThen(archives) { files ->
            tellNothingIn(empty + archives.filter { files[it].isNullOrEmpty() })
            openFound(found.flatMap { item -> files[item.path]?.map { Found(it, item.inSubfolder) } ?: listOf(item) })
        }
    }

    private fun isArchive(path: Path) = ZipSpool.isZip(path) || CompressedSpool.formatOf(path) != null

    private fun tellNothingIn(folders: List<Path>) {
        if (folders.isEmpty()) return
        JOptionPane.showMessageDialog(
            this, "No log files in:\n${folders.joinToString("\n")}", "Nothing to Open", JOptionPane.INFORMATION_MESSAGE,
        )
    }

    /**
     * Opens [found] as the settings say, or as the user answers in the Open Files dialog: how to open
     * them, with which parsers, and whether with the files in subfolders.
     */
    private fun openFound(found: List<Found>) {
        val current = selectedTab()
        // Files the current tab already shows have nothing to add.
        val candidates = found.distinctBy { it.path }.filter { current == null || it.path !in current.paths }
        if (candidates.isEmpty()) return
        val all = candidates.map { it.path }
        val inSubfolders = candidates.filter { it.inSubfolder }.map { it.path }.toSet()
        // With nothing open yet, a single file has only one way to open.
        if (current == null && all.size == 1 && inSubfolders.isEmpty()) return openFile(all.single())
        val settings = AppSettings.current
        val answer = settings.openMode?.let { remembered ->
            val files = if (settings.includeSubfolders) all else all - inSubfolders
            if (files.isEmpty()) return
            val mode = rememberedMode(remembered, files.size, current?.paths?.size) ?: return@let null
            OpenAnswer(mode, files, files.map { null }, settings.includeSubfolders, remember = false)
        } ?: askHowToOpen(all, inSubfolders, current) ?: return
        if (answer.remember) AppSettings.updateOrShowError(this, AppSettings.current.copy(openMode = answer.mode, includeSubfolders = answer.includeSubfolders))
        when (answer.mode) {
            OpenMode.MERGE_INTO_CURRENT -> mergeWithCurrent(current!!, answer.files, answer.formats, keepCurrent = false)
            OpenMode.MERGE_WITH_CURRENT_IN_NEW -> mergeWithCurrent(current!!, answer.files, answer.formats, keepCurrent = true)
            OpenMode.MERGE -> openMerged(answer.files, answer.formats)
            OpenMode.TABS -> answer.files.zip(answer.formats).forEach { (file, format) -> openFile(file, format) }
        }
    }

    /**
     * The closest of the modes [count] files can be opened in to the [remembered] one, with a current tab of
     * [currentCount] files; null if merging is not possible as remembered (too many files), so the user decides.
     */
    private fun rememberedMode(remembered: OpenMode, count: Int, currentCount: Int?): OpenMode? {
        val possible = OpenFilesDialog.modesFor(count, currentCount)
        return when {
            remembered in possible -> remembered
            remembered != OpenMode.TABS && OpenMode.MERGE in possible -> OpenMode.MERGE
            count == 1 -> OpenMode.TABS
            else -> null
        }
    }

    private fun askHowToOpen(files: List<Path>, inSubfolders: Set<Path>, current: LogTab?): OpenAnswer? {
        val names = MergedLog.sourceNames(files, ::originOf)
        val descriptions = files.map { (originOf(it) ?: it).toString() }
        val dialog = OpenFilesDialog(this, files, names, descriptions, inSubfolders, current?.paths)
        dialog.isVisible = true
        return dialog.answer
    }

    /** Arguments for [openArgs] that reopen the open tabs with their filters, e.g. after a restart. */
    fun tabArgs(): List<String> = buildList {
        for (i in 0..<tabs.tabCount) {
            val tab = tabs.getComponentAt(i) as LogTab
            add(TAB_ARG)
            // Extracted files by their zip or compressed file, as the next app extracts them anew.
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
        val args = groups.flatMap { it.paths }
        val zips = args.mapNotNull(ZipSpool::parseOrigin).map { it.zip }
        // Decompressed files are saved as their .gz or .zst, and decompressed again.
        val compressed = args.filter { ZipSpool.parseOrigin(it) == null }.map { Path.of(it).toAbsolutePath().normalize() }
            .filter { CompressedSpool.formatOf(it) != null }
        val archives = (zips + compressed).distinct()
        if (archives.isEmpty()) return openGroups(groups.map { it.paths.map(Path::of) to it.filter() }, selected)
        extractThen(archives) { files ->
            fun path(arg: String) = ZipSpool.parseOrigin(arg)?.let(spool::extractedFile)
                ?: Path.of(arg).let { files[it.toAbsolutePath().normalize()]?.singleOrNull() ?: it }
            openGroups(groups.map { group -> group.paths.map(::path) to group.filter() }, selected)
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
     * Unzips the zips among [archives] and decompresses the .gz and .zst files while showing how far it got,
     * then calls [then] with the files of each; nothing more if the user cancels or one cannot be read.
     */
    private fun extractThen(archives: List<Path>, then: (Map<Path, List<Path>>) -> Unit) {
        lateinit var job: Job
        var finished = false
        val dialog = ExtractDialog(this) { job.cancel() }
        job = scope.launch {
            val files = LinkedHashMap<Path, List<Path>>()
            var failure = ""
            try {
                for ((i, archive) in archives.withIndex()) {
                    val progress = { fraction: Double -> dialog.fraction = (i + fraction) / archives.size }
                    val format = CompressedSpool.formatOf(archive)
                    if (format == null) {
                        failure = "Cannot unzip $archive"
                        dialog.text = "Unzipping ${archive.fileName}…"
                        files[archive] = spool.extract(archive, progress)
                    } else {
                        failure = "Cannot decompress $archive"
                        dialog.text = "Decompressing ${archive.fileName}…"
                        files[archive] = listOf(compressedSpool.decompress(archive, format, progress))
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                dialog.dispose()
                JOptionPane.showMessageDialog(
                    this@MainFrame, "$failure:\n${e.message ?: e.javaClass.simpleName}", "Cannot Open File", JOptionPane.ERROR_MESSAGE,
                )
                return@launch
            } finally {
                finished = true
                dialog.dispose()
            }
            then(files)
        }
        // Quick ones finish before the dialog would only flash.
        // Not job.isActive: the job is still running while [then] asks how to open the files.
        Timer(EXTRACT_DIALOG_DELAY_MILLIS) { if (!finished) dialog.isVisible = true }.apply { isRepeats = false }.start()
    }

    /** Opens each of [paths] in a tab of its own. */
    fun openEach(paths: List<Path>) = paths.forEach { openFile(it) }

    /** Opens [path] in a tab with [format], or the detected one if null; selects its tab if it is open already. */
    fun openFile(path: Path, format: LogFormat? = null) {
        val absolute = path.toAbsolutePath().normalize()
        if (selectTab(listOf(absolute)) || !checkOpenable(absolute)) return
        addTab(LogTab(listOf(absolute), ::openFiles, ::openEach, origin = ::originOf, formats = listOf(format)) { updateStatus() })
    }

    private fun openMerged(paths: List<Path>, formats: List<LogFormat?>) {
        if (selectTab(paths)) return
        addTab(LogTab(paths, ::openFiles, ::openEach, origin = ::originOf, formats = formats) { updateStatus() })
    }

    /**
     * Merges [tab]'s files with [paths] (read with [formats]) under its filter: in a new tab next to it if
     * [keepCurrent], else in its place.
     */
    private fun mergeWithCurrent(tab: LogTab, paths: List<Path>, formats: List<LogFormat?>, keepCurrent: Boolean) {
        val merged = tab.paths + paths
        if (selectTab(merged)) return
        val index = tabs.indexOfComponent(tab)
        val mergedTab = LogTab(merged, ::openFiles, ::openEach, tab.filter, ::originOf, tab.chosenFormats + formats) { updateStatus() }
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
