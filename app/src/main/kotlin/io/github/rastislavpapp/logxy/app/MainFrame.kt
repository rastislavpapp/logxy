package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGUtils
import io.github.rastislavpapp.logxy.core.archive.ArchiveOrigin
import io.github.rastislavpapp.logxy.core.archive.CompressedSpool
import io.github.rastislavpapp.logxy.core.archive.ZipSpool
import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.formatTimeSpan
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.open.Decision
import io.github.rastislavpapp.logxy.core.open.Found
import io.github.rastislavpapp.logxy.core.open.FoundFile
import io.github.rastislavpapp.logxy.core.open.OpenPlan
import io.github.rastislavpapp.logxy.core.open.OpenSession
import io.github.rastislavpapp.logxy.core.open.RecentSessions
import io.github.rastislavpapp.logxy.core.open.Reopen
import io.github.rastislavpapp.logxy.core.open.SessionArgs
import io.github.rastislavpapp.logxy.core.open.SessionFiles
import io.github.rastislavpapp.logxy.core.open.TabAction
import io.github.rastislavpapp.logxy.core.open.TabPlan
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.ui.FileDropHandler
import io.github.rastislavpapp.logxy.ui.Icons
import io.github.rastislavpapp.logxy.ui.LogTab
import io.github.rastislavpapp.logxy.ui.ProgressDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import net.miginfocom.swing.MigLayout
import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
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
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.event.MenuEvent
import javax.swing.event.MenuListener

private const val CARD_PLACEHOLDER = "placeholder"
private const val CARD_TABS = "tabs"
private const val LIVE_COLOR = "Actions.Green"
private const val FOLLOW_COLOR = "Component.accentColor"
private const val OPENING_TITLE = "Opening Files"

class MainFrame : JFrame("logxy") {

    private val log = LoggerFactory.getLogger(MainFrame::class.java)

    private val shortcut = toolkit.menuShortcutKeyMaskEx
    private val tabs = JTabbedPane()
    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val statusBar = JPanel(MigLayout("insets 3 8 3 8, fill, gap 0, hidemode 3", "[grow][]"))
    private val fileSlot = Slot()
    private var lastDirectory: File? = AppState[AppState.LAST_DIRECTORY]?.let(::File)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    /** The latest of [openFiles], which the next one waits for. */
    private var opening: Job? = null

    /** Where opened zip files are extracted to; made when the first one is opened. */
    private val spoolCreation = lazy { ZipSpool.forThisProcess(Settings.cacheDirectory()) }
    private val spool by spoolCreation

    /** Where opened .gz and .zst files are decompressed to; made when the first one is opened. */
    private val compressedSpoolCreation = lazy { CompressedSpool.forThisProcess(Settings.cacheDirectory()) }
    private val compressedSpool by compressedSpoolCreation

    private fun originOf(path: Path): ArchiveOrigin? =
        (if (spoolCreation.isInitialized()) spool.originOf(path) else null)
            ?: if (compressedSpoolCreation.isInitialized()) compressedSpool.originOf(path) else null

    /** The recent sessions, newest first; filled each time it opens. Shown while they are remembered. */
    private val openRecent = JMenu("Open Recent").apply {
        addMenuListener(object : MenuListener {
            override fun menuSelected(e: MenuEvent) = fillOpenRecent(this@apply)
            override fun menuDeselected(e: MenuEvent) {}
            override fun menuCanceled(e: MenuEvent) {}
        })
    }

    private val reopenClosedTabItem =
        menuItem("Reopen Closed Tab", null, KeyEvent.VK_T, shortcut or InputEvent.SHIFT_DOWN_MASK) {
            reopenClosedTab()
        }

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        iconImages = FlatSVGUtils.createWindowIconImages(Logo.PATH)
        jMenuBar = createMenuBar()
        // The title bar is full of controls, and the tab names the file; the title still names the window elsewhere.
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false)

        tabs.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        // Like IntelliJ's editor tabs: a darker strip, the selected tab in the content's color (joined to it), accent
        // underline.
        tabs.putClientProperty(
            "FlatLaf.style",
            "tabHeight: 36; tabInsets: 4,14,4,10; showContentSeparator: true; " +
                "background: darken(\$Panel.background,4%); selectedBackground: \$Panel.background; " +
                "hoverColor: darken(\$Panel.background,8%); showTabSeparators: true; " +
                "underlineColor: \$Component.accentColor; inactiveUnderlineColor: \$Component.borderColor",
        )
        tabs.isOpaque = true
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true)
        val closeTooltip = "Close (${keyText(KeyEvent.VK_W, shortcut)})"
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_TOOLTIPTEXT, closeTooltip)
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, IntConsumer { closeTab(it) })
        tabs.addChangeListener {
            fileSlot.show(selectedTab()?.fileControls)
            updateStatus()
        }
        tabs.addMouseListener(object : MouseAdapter() {
            // Pressed on Linux and macOS, released on Windows.
            override fun mousePressed(e: MouseEvent) = maybeShowTabMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowTabMenu(e)
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
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = recordExit()
        })
        var remembered = AppSettings.current.rememberRecent
        AppSettings.addListener {
            // Only when turned off, not on every save while it is off.
            if (remembered && !AppSettings.current.rememberRecent) RecentStore.forget()
            remembered = AppSettings.current.rememberRecent
            showRecentItems()
        }
        showRecentItems()
        updateStatus()
    }

    /** Whether the open sessions were recorded at exit; closing may be asked for more than once. */
    private var exitRecorded = false

    private fun recordExit() {
        if (exitRecorded) return
        exitRecorded = true
        RecentStore.recordExit(openSessionsToStore(), tabs.selectedIndex)
    }

    private fun tabAt(index: Int) = tabs.getComponentAt(index) as LogTab

    /** Open Recent and Reopen Closed Tab are there only while the recent sessions are remembered. */
    private fun showRecentItems() {
        val remembered = AppSettings.current.rememberRecent
        openRecent.isVisible = remembered
        reopenClosedTabItem.isVisible = remembered
        // A hidden item's shortcut would still work.
        reopenClosedTabItem.isEnabled = remembered
    }

    /** Fills [menu] with the recent sessions, each opening it again, then Clear Recent. */
    private fun fillOpenRecent(menu: JMenu) {
        menu.removeAll()
        val recent = RecentStore.sessions.recent
        for (session in recent) {
            menu.add(JMenuItem(recentLabel(session)).apply {
                toolTipText = session.paths.joinToString("<br>", "<html>", "</html>") { escapeHtml(it) }
                // Its files are all gone; it is dropped when the recent sessions are written next.
                isEnabled = !RecentSessions.gone(session)
                addActionListener { reopen(Reopen(listOf(session), 0)) }
            })
        }
        if (recent.isEmpty()) menu.add(JMenuItem("No Recent Sessions").apply { isEnabled = false })
        menu.addSeparator()
        menu.add(JMenuItem("Clear Recent").apply {
            isEnabled = recent.isNotEmpty()
            addActionListener { RecentStore.clear() }
        })
    }

    private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A recent session's name in Open Recent: its first file's, and how many more a merge has. */
    private fun recentLabel(session: OpenSession): String {
        val first = recentFileLabel(session.paths.first())
        return if (session.paths.size == 1) first else "$first + ${session.paths.size - 1} more"
    }

    /** A stored file's name: `server.log`, or `app.zip › server.log` for a file from a zip. */
    private fun recentFileLabel(stored: String): String {
        val origin = ZipSpool.parseOrigin(stored)
        if (origin != null) return "${origin.zip.fileName} › ${origin.entry.substringAfterLast('/')}"
        return stored.substringAfterLast('/').substringAfterLast(File.separatorChar)
    }

    /** Opens the newest recent session that is not open; pressed again, the next one, as the last one is open now. */
    private fun reopenClosedTab() {
        if (!AppSettings.current.rememberRecent) return
        val open = (0..<tabs.tabCount).map { i -> tabAt(i).paths.map(::storedPath).toSet() }
        val session = RecentStore.sessions.recent.firstOrNull { recent ->
            !RecentSessions.gone(recent) && recent.paths.toSet() !in open
        } ?: return
        reopen(Reopen(listOf(session), 0))
    }

    /** [path] as a session is stored: an extracted file by its zip or compressed file, as opening it extracts anew. */
    private fun storedPath(path: Path) = (originOf(path) ?: path).toString()

    /** [tab]'s session as it can be opened again, e.g. after a restart or from Open Recent. */
    private fun sessionOf(tab: LogTab): OpenSession {
        val filter = tab.filter.takeUnless { it.isEmpty }
        val position = tab.position?.mapFiles(::storedPath)
        return OpenSession(tab.paths.map(::storedPath), tab.chosenParsers, filter, position)
    }

    /** The sessions of the open tabs as they can be opened again, in tab order. */
    private fun openSessionsToStore() = (0..<tabs.tabCount).map { sessionOf(tabAt(it)) }

    private fun selectedTab() = tabs.selectedComponent as LogTab?

    private fun keyText(key: Int, modifiers: Int = 0) = KeyStroke.getKeyStroke(key, modifiers).toString()
        .replace("pressed ", "")
        .replace("ctrl", "Ctrl")
        .replace("shift", "Shift")
        .replace("meta", "⌘")
        .replace(" ", "+")

    private fun menuItem(text: String, icon: Icon?, key: Int?, modifiers: Int = 0, action: () -> Unit) =
        JMenuItem(text, icon).apply {
            if (key != null) accelerator = KeyStroke.getKeyStroke(key, modifiers)
            addActionListener { action() }
        }

    private fun createMenuBar() = JMenuBar().apply {
        add(JMenu("File").apply {
            mnemonic = KeyEvent.VK_F
            add(menuItem("Open", Icons.open, KeyEvent.VK_O, shortcut) { chooseAndOpenFiles() })
            add(openRecent)
            add(menuItem("Reload", Icons.refresh, KeyEvent.VK_F5) { selectedTab()?.reloadFile() })
            add(menuItem("Close Tab", Icons.close, KeyEvent.VK_W, shortcut) {
                if (tabs.selectedIndex >= 0) closeTab(tabs.selectedIndex)
            })
            add(reopenClosedTabItem)
            addSeparator()
            add(menuItem("Preferences", Icons.settings, KeyEvent.VK_S, shortcut or InputEvent.ALT_DOWN_MASK) {
                PreferencesDialog(this@MainFrame, previewFile = selectedTab()?.path).isVisible = true
            })
            addSeparator()
            // Closing rather than disposing, so the open sessions are recorded as at any exit.
            add(menuItem("Exit", Icons.exit, null) {
                this@MainFrame.dispatchEvent(WindowEvent(this@MainFrame, WindowEvent.WINDOW_CLOSING))
            })
        })
        add(JMenu("Edit").apply {
            mnemonic = KeyEvent.VK_E
            add(menuItem("Find", Icons.get("general/search"), KeyEvent.VK_F, shortcut) { selectedTab()?.showFind() })
            add(menuItem("Find Next", null, KeyEvent.VK_F3) { selectedTab()?.findNext(forward = true) })
            add(menuItem("Find Previous", null, KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK) {
                selectedTab()?.findNext(forward = false)
            })
            addSeparator()
            add(menuItem("Edit Filter", Icons.filter, KeyEvent.VK_F, shortcut or InputEvent.SHIFT_DOWN_MASK) {
                selectedTab()?.focusFilter()
            })
        })
        add(JMenu("View").apply {
            mnemonic = KeyEvent.VK_V
            add(themeMenu())
            add(JCheckBoxMenuItem("Integrated Title Bar", AppState[AppState.TITLE_BAR] != "false").apply {
                toolTipText = "Menu and toolbar inside the window title, IntelliJ-style (applies after a restart)"
                addActionListener {
                    AppState[AppState.TITLE_BAR] = isSelected.toString()
                    val message = "The title bar changes when logxy is started again."
                    JOptionPane.showMessageDialog(this@MainFrame, message, "Title Bar", JOptionPane.INFORMATION_MESSAGE)
                }
            })
        })
        add(JMenu("Help").apply {
            mnemonic = KeyEvent.VK_H
            add(menuItem("About logxy", Logo.icon(16), null) { showAbout(this@MainFrame) })
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
        fun item(theme: ThemeInfo) =
            JRadioButtonMenuItem(theme.name, theme.className == Themes.current.className).apply {
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

    /**
     * Opens [paths]: folders as the log files in them, zip files as the files in them, and compressed
     * files (.gz, .zst) as their decompressed content. A single file opens in a tab if no tab is open;
     * otherwise the user chooses between merging them, with the current tab's files or in a new tab,
     * and opening them in tabs of their own.
     */
    fun openFiles(paths: List<Path>) {
        val previous = opening
        // Undispatched, so plain files open at once. An open that comes while an earlier one walks folders or
        // extracts archives waits for it, so tabs open in the order asked.
        opening = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            val found = find(paths) ?: return@launch
            found.problems.forEach { (path, problem) -> tellCannotOpen(path, problem) }
            val archives = found.files.map { it.path }.filter(::isArchive)
            if (archives.isEmpty()) {
                tellNothingIn(found.empty)
                return@launch openFound(found.files)
            }
            extractThen(archives) { files ->
                tellLeftOut(archives)
                tellNothingIn(found.empty + archives.filter { files[it].isNullOrEmpty() })
                openFound(found.files.flatMap { item ->
                    files[item.path]?.map { FoundFile(it, item.inSubfolder) } ?: listOf(item)
                })
            }.join()
        }
    }

    /**
     * The files of [paths], with folders walked off the UI thread and a dialog telling how many files
     * were found if it takes a while; null if the user cancels.
     */
    private suspend fun CoroutineScope.find(paths: List<Path>): Found? {
        if (paths.none { Files.isDirectory(it) }) return OpenPlan.find(paths)
        lateinit var walk: Deferred<Found>
        var finished = false
        val dialog = ProgressDialog(this@MainFrame, OPENING_TITLE, indeterminate = true) { walk.cancel() }
        fun tell(folder: Path, count: Int) {
            dialog.text = "Looking for logs in $folder… ($count found)"
        }
        tell(paths.first { Files.isDirectory(it) }, 0)
        walk = async(Dispatchers.IO) { OpenPlan.find(paths, ::tell) }
        dialog.showLater { finished }
        return try {
            walk.await()
        } catch (e: CancellationException) {
            // Only the walk was cancelled, by the user; not the open.
            currentCoroutineContext().ensureActive()
            null
        } finally {
            finished = true
            dialog.dispose()
        }
    }

    private fun isArchive(path: Path) = ZipSpool.isZip(path) || CompressedSpool.formatOf(path) != null

    /** Tells the user which files of the zips among [archives] are not opened, and why. */
    private fun tellLeftOut(archives: List<Path>) {
        if (!spoolCreation.isInitialized()) return
        val rows = archives.flatMap { archive ->
            spool.leftOutOf(archive).map { listOf("$archive", it.entry, it.reason) }
        }
        if (rows.isEmpty()) return
        TableNoticeDialog(
            this, "Files Left Out", UIManager.getIcon("OptionPane.warningIcon"), "These files are not opened:",
            listOf("Archive", "File", "Reason"), rows, "files-left-out",
        ).isVisible = true
    }

    private fun tellNothingIn(folders: List<Path>) {
        if (folders.isEmpty()) return
        TableNoticeDialog(
            this, "Nothing to Open", UIManager.getIcon("OptionPane.informationIcon"), "No log files in:",
            listOf("Folder"), folders.map { listOf("$it") }, "nothing-to-open",
        ).isVisible = true
    }

    /**
     * Opens [found] as the settings say, or as the user answers in the Open Files dialog: how to open
     * them, with which parsers, and whether with the files in subfolders.
     */
    private fun openFound(found: List<FoundFile>) {
        val current = selectedTab()
        val settings = AppSettings.current
        val decision = OpenPlan.decide(found, current?.paths, settings.openMode, settings.includeSubfolders)
        val answer = when (decision) {
            Decision.Nothing -> return
            is Decision.OpenOne -> return open(OpenMode.TABS, listOf(decision.path), listOf(null), current = null)
            is Decision.Open -> {
                val parsers = decision.files.map { null }
                OpenAnswer(decision.mode, decision.files, parsers, settings.includeSubfolders, remember = false)
            }
            is Decision.Ask -> askHowToOpen(decision.files, decision.inSubfolders, current) ?: return
        }
        if (answer.remember) {
            val remembered =
                AppSettings.current.copy(openMode = answer.mode, includeSubfolders = answer.includeSubfolders)
            AppSettings.updateOrShowError(this, remembered)
        }
        open(answer.mode, answer.files, answer.parsers, current)
    }

    /**
     * Opens [files] (read with [parsers]) in [mode], merged with [current] if the mode says so, selecting the tab
     * that shows them already if there is one.
     */
    private fun open(mode: OpenMode, files: List<Path>, parsers: List<Parser?>, current: LogTab?) {
        // By the tab, not the selected index: tabs may have opened or closed while the user was asked.
        val index = current?.let(tabs::indexOfComponent)?.takeIf { it >= 0 }
        if (index == null && (mode == OpenMode.MERGE_INTO_CURRENT || mode == OpenMode.MERGE_WITH_CURRENT_IN_NEW)) return
        // Files can go between finding them and the user's answer.
        val filesWithParsers = files.zip(parsers).filter { (file, _) -> checkOpenable(file) }
        val openableFiles = filesWithParsers.map { it.first }
        log.info("Opening files $openableFiles with mode=$mode")
        perform(TabPlan.open(mode, openableFiles, filesWithParsers.map { it.second }, openSessions(), index))
    }

    private fun askHowToOpen(files: List<Path>, inSubfolders: Set<Path>, current: LogTab?): OpenAnswer? {
        val names = MergedLog.sourceNames(files, ::originOf)
        val descriptions = files.map { (originOf(it) ?: it).toString() }
        val dialog = OpenFilesDialog(this, files, names, descriptions, inSubfolders, current?.paths)
        dialog.isVisible = true
        return dialog.answer
    }

    /**
     * Arguments for [openArgs] that reopen the open tabs with their parsers, filters and positions, e.g. after a
     * restart.
     */
    fun tabArgs(): List<String> = SessionArgs.write(openSessionsToStore(), tabs.selectedIndex)

    /**
     * Opens the tabs from [tabArgs]; or else files given on the command line, each file group in a tab, merged if
     * several, after the sessions open at the last exit if the settings say to reopen them.
     */
    fun openArgs(args: List<String>) {
        SessionArgs.read(args)?.let { return reopen(it) }
        // Settings only have reopenOnStart while they remember recent sessions.
        val atExit = if (AppSettings.current.reopenOnStart) RecentStore.sessions.atExit else null
        if (atExit != null) reopen(atExit)
        if (args.isNotEmpty()) openFiles(args.map { Path.of(it) })
    }

    /**
     * Opens [reopen]'s sessions, after any files being opened before are, each in a tab with its parsers, filter and
     * position. Files from zips and compressed files are extracted again first.
     */
    private fun reopen(reopen: Reopen) {
        val previous = opening
        opening = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            val stored = reopen.sessions.flatMap { it.paths }
            val zips = stored.mapNotNull(ZipSpool::parseOrigin).map { it.zip }
            // Decompressed files are stored as their .gz or .zst, and decompressed again.
            val compressed = stored
                .filter { ZipSpool.parseOrigin(it) == null }
                .map { Path.of(it).toAbsolutePath().normalize() }
                .filter { CompressedSpool.formatOf(it) != null }
            // A zip that is gone leaves out its files, as any other file that is gone.
            val archives = (zips + compressed).distinct().filter { Files.exists(it) }
            if (archives.isEmpty()) return@launch openRestored(reopen) { Path.of(it) }
            extractThen(archives) { files ->
                openRestored(reopen) { text ->
                    ZipSpool.parseOrigin(text)?.let(spool::extractedFile)?.let { return@openRestored it }
                    val path = Path.of(text)
                    files[path.toAbsolutePath().normalize()]?.singleOrNull() ?: path
                }
            }.join()
        }
    }

    /**
     * Opens a tab for each of [restored]'s sessions with its parsers, filter and position, then selects the tab
     * [restored] names. [path] turns each path as stored into the file to open. Files that cannot be opened are
     * left out, and listed for the user.
     */
    private fun openRestored(restored: Reopen, path: (String) -> Path) {
        val leftOut = ArrayList<List<String>>()
        val sessions = restored.sessions.map { session ->
            val files = LinkedHashMap<String, Path>()
            val parsers = ArrayList<Parser?>()
            session.paths.forEachIndexed { i, stored ->
                val file = path(stored)
                val problem = OpenPlan.problem(file)
                if (problem != null) {
                    leftOut += listOf(stored, problem)
                } else {
                    files[stored] = file
                    parsers += session.parsers.getOrNull(i)
                }
            }
            SessionFiles(files.values.toList(), parsers, session.filter, session.position?.mapFiles(files::get))
        }
        if (leftOut.isNotEmpty()) {
            TableNoticeDialog(
                this, "Files Left Out", UIManager.getIcon("OptionPane.warningIcon"), "These files are not opened:",
                listOf("File", "Reason"), leftOut, "files-left-out",
            ).isVisible = true
        }
        perform(TabPlan.reopen(sessions, openSessions(), restored.selected))
    }

    /**
     * Unzips the zips among [archives] and decompresses the .gz and .zst files while showing how far it got,
     * then calls [then] with the files of each; nothing more if the user cancels or one cannot be read.
     * Returns the job that does it all.
     */
    private fun extractThen(archives: List<Path>, then: (Map<Path, List<Path>>) -> Unit): Job {
        lateinit var job: Job
        var finished = false
        val dialog = ProgressDialog(this, OPENING_TITLE) { job.cancel() }
        job = scope.launch {
            val files = LinkedHashMap<Path, List<Path>>()
            var failure = ""
            var error: Exception? = null
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
                error = e
            } finally {
                finished = true
                dialog.dispose()
            }
            // Only once finished: the timer fires while the message is open and would show the progress dialog over it.
            error?.let { e ->
                JOptionPane.showMessageDialog(
                    this@MainFrame,
                    "$failure:\n${e.message ?: e.javaClass.simpleName}",
                    "Cannot Open File",
                    JOptionPane.ERROR_MESSAGE,
                )
                return@launch
            }
            then(files)
        }
        // Not job.isActive: the job is still running while [then] asks how to open the files.
        dialog.showLater { finished }
        return job
    }

    /** Opens each of [paths] in a tab of its own. */
    fun openEach(paths: List<Path>) = open(OpenMode.TABS, paths, paths.map { null }, current = null)

    private fun openSessions() = (0..<tabs.tabCount).map { i ->
        (tabs.getComponentAt(i) as LogTab).let { SessionFiles(it.paths, it.chosenParsers, it.filter) }
    }

    private fun perform(actions: List<TabAction>) = actions.forEach { action ->
        when (action) {
            is TabAction.Select -> tabs.selectedIndex = action.index
            is TabAction.Add -> insertTab(tabs.tabCount, action.session)
            is TabAction.InsertAfter -> insertTab(action.index + 1, action.session)
            is TabAction.Replace -> {
                insertTab(action.index, action.session)
                // Merged into the new tab, not closed.
                closeTab(action.index + 1, recorded = false)
            }
        }
    }

    private fun insertTab(index: Int, session: SessionFiles) {
        val tab = LogTab(
            session.paths, AppHost, ::openFiles, ::openEach, session.filter, ::originOf, session.parsers,
            restore = session.position,
        ) {
            updateStatus()
        }
        tabs.insertTab(tab.title, if (tab.isMerged) Icons.merged else Icons.logFile, tab, tab.description, index)
        tabs.selectedIndex = index
        cards.show(center, CARD_TABS)
    }

    /** Tells the user why [path] cannot be opened, if it cannot. */
    private fun checkOpenable(path: Path): Boolean {
        val problem = OpenPlan.problem(path) ?: return true
        tellCannotOpen(path, problem)
        return false
    }

    private fun tellCannotOpen(path: Path, problem: String) =
        JOptionPane.showMessageDialog(this, "$problem:\n$path", "Cannot Open File", JOptionPane.ERROR_MESSAGE)

    /** Closes the tab at [index]; if [recorded], its session becomes the newest recent session. */
    private fun closeTab(index: Int, recorded: Boolean = true) {
        val tab = tabAt(index)
        if (recorded) RecentStore.recordClosed(sessionOf(tab))
        tabs.removeTabAt(index)
        tab.dispose()
        if (tabs.tabCount == 0) cards.show(center, CARD_PLACEHOLDER)
        updateStatus()
    }

    /** Closes the tabs at [indices]; from the last, so the others keep their indices. */
    private fun closeTabs(indices: IntRange) = indices.reversed().forEach(::closeTab)

    private fun maybeShowTabMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = tabs.indexAtLocation(e.x, e.y)
        if (index < 0) return
        val tab = tabs.getComponentAt(index) as LogTab
        val last = tabs.tabCount - 1
        val popup = JPopupMenu()
        popup.add(JMenuItem("Close", Icons.close).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcut)
            addActionListener { closeTab(index) }
        })
        popup.add(JMenuItem("Close Other Tabs").apply {
            isEnabled = last > 0
            addActionListener {
                closeTabs(index + 1..last)
                closeTabs(0..<index)
            }
        })
        popup.add(JMenuItem("Close Tabs to the Right").apply {
            isEnabled = index < last
            addActionListener { closeTabs(index + 1..last) }
        })
        popup.add(JMenuItem("Close All Tabs").apply {
            addActionListener { closeTabs(0..last) }
        })
        popup.addSeparator()
        popup.add(JMenuItem(if (tab.isMerged) "Copy Paths" else "Copy Path", Icons.copy).apply {
            // Extracted files by their zip or compressed file, as the tab's tooltip names them.
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(tab.description), null)
            }
        })
        popup.show(tabs, e.x, e.y)
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
            val location = if (tab.isMerged) {
                tab.sourceNames.joinToString(", ")
            } else {
                (originOf(tab.path) ?: tab.path).toString()
            }
            val locationTooltip = "<html>" + tab.description.replace("\n", "<br>") + "</html>"
            statusBar.add(JLabel(location).apply { toolTipText = locationTooltip }, "growx, wmin 0")
            fun segment(component: JComponent) {
                statusBar.add(JSeparator(SwingConstants.VERTICAL), "growy, gap 10 10")
                statusBar.add(component)
            }
            fun muted(text: String) = JLabel(text).apply { foreground = UIManager.getColor("Label.disabledForeground") }
            status.message?.let { segment(muted(it)) }
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
            if (tab.selectedCount > 1) {
                val span = tab.selectionSpan
                val rows = "${numbers.format(tab.selectedCount)} rows"
                val text = if (span == null) rows else "$rows · ${formatTimeSpan(span)}"
                segment(JLabel("Selection: $text").apply {
                    toolTipText = if (span == null) "Rows selected" else "Rows selected; time from first to last"
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
            status.indexMillis?.let { segment(muted("indexed in ${numbers.format(it)} ms")) }
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
