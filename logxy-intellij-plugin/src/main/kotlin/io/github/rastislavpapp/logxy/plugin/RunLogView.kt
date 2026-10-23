package io.github.rastislavpapp.logxy.plugin

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.session.Redetection
import io.github.rastislavpapp.logxy.core.session.SessionStatus
import io.github.rastislavpapp.logxy.ui.LogPart
import io.github.rastislavpapp.logxy.ui.LogTab
import io.github.rastislavpapp.logxy.ui.Look
import io.github.rastislavpapp.logxy.ui.count
import java.awt.BorderLayout
import java.awt.CardLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Lines of output to wait for before the view is made, so the parser is detected from more than a line or two. */
private const val DETECT_LINES = 20

/** Or this long after the first output, for a program that prints little. */
private const val DETECT_WAIT_MILLIS = 1500

/** Where [RunLogView.shownParts] is kept: one choice for all runs. */
private const val PARTS_KEY = "run.parts"

private val log = logger<RunLogView>()

/**
 * A run's console or a logxy view of its [spool], as the run tab's toolbar switches them; [memory] keeps the
 * choice and the columns for the next run, which starts unfiltered. The view is made once logxy shows and there is
 * enough output to detect its parser. While it shows, the view's actions take the place of the console's [toolbar].
 * The IDE's Find, Find Next and Find Previous search its records.
 */
class RunLogView(
    console: JComponent,
    private val toolbar: ConsoleToolbar,
    private val spool: Spool,
    private val memory: RunMemory,
    private val host: IdeHost,
) : JPanel(CardLayout()), Disposable {

    private val cards = layout as CardLayout
    private val logPanel = JPanel(BorderLayout()).apply {
        add(JLabel("Waiting for output…", SwingConstants.CENTER))
    }

    /** The view once made; set on the UI thread, read on the process's threads, which push new output to it. */
    @Volatile var tab: LogTab? = null
        private set
    private val checkScheduled = AtomicBoolean()
    private var waitTimer: Timer? = null
    private val actions = viewActions(this)

    /** Whether Clear All emptied the output: no view is made of it while it stays empty, also once the run ends. */
    private var cleared = false

    /** What the view had before Clear All, for the one made after it. */
    private var kept: Kept? = null

    private class Kept(val parsers: List<Parser?>, val filter: SavedFilter)

    var showsLogxy = memory.showsLogxy
        private set

    /** The parts shown besides the rows, the same for all runs: none at first, so a run shows its rows alone. */
    var shownParts: Set<LogPart>
        get() = tab?.shownParts ?: rememberedParts()
        set(value) {
            val tab = tab
            if (tab != null) tab.shownParts = value else rememberParts(value)
        }

    init {
        add(console, CONSOLE)
        add(logPanel, LOGXY)
        bind(IdeActions.ACTION_FIND) { it.showFind() }
        bind(IdeActions.ACTION_FIND_NEXT) { it.findNext(forward = true) }
        bind(IdeActions.ACTION_FIND_PREVIOUS) { it.findNext(forward = false) }

        spool.onGrew = {
            val tab = tab
            if (tab != null) {
                tab.fileGrew()
            } else if (checkScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater {
                    checkScheduled.set(false)
                    makeTabIfReady()
                }
            }
        }
        spool.onExit = {
            SwingUtilities.invokeLater {
                statusChanged()
                makeTabIfReady()
                tab?.writerEnded()
                remember()
            }
        }
        showView(showsLogxy)
    }

    /** Runs [action] on the view by the shortcuts of the IDE action [id], while the focus is in the view. */
    private fun bind(id: String, action: (LogTab) -> Unit) {
        val shortcuts = ActionManager.getInstance().getAction(id)?.shortcutSet ?: return
        DumbAwareAction.create { tab?.let(action) }.registerCustomShortcutSet(shortcuts, logPanel, this)
    }

    /** Shows logxy, or the console. */
    fun showView(logxy: Boolean) {
        showsLogxy = logxy
        memory.showsLogxy = logxy
        cards.show(this, if (logxy) LOGXY else CONSOLE)
        toolbar.show(if (logxy) actions else null, this)
        makeTabIfReady()
        statusChanged()
    }

    /**
     * Empties the view, as Clear All empties the console; the console keeps its text. The view is made anew from the
     * output that follows, with the parser and the filter it had: reading the emptied file again would detect no
     * parser in it.
     */
    fun clear() {
        log.info("logxy: Clear All emptied ${spool.path}")
        val old = tab
        // Before the file is emptied, so the output that follows waits for the new view.
        tab = null
        spool.clear()
        cleared = true
        if (old != null) {
            remember(old)
            kept = Kept(old.chosenParsers, old.filter)
            old.dispose()
        }
        logPanel.removeAll()
        logPanel.add(JLabel("No output since Clear All", SwingConstants.CENTER))
        logPanel.revalidate()
        logPanel.repaint()
        makeTabIfReady()
        statusChanged()
    }

    /** Makes the view if logxy shows and the output is enough to detect its parser, or will be after a wait. */
    private fun makeTabIfReady() {
        if (tab != null || !showsLogxy) return
        if (spool.lines == 0 && cleared) return
        val ended = spool.exitCode != null
        if (ended) return makeTab("the process ended")
        if (spool.lines >= DETECT_LINES) return makeTab("enough output")
        if (spool.lines == 0) return
        if (waitTimer != null) return
        waitTimer = Timer(DETECT_WAIT_MILLIS) { makeTab("the wait for more output ended") }.apply {
            isRepeats = false
            start()
        }
    }

    /** Makes the view; [why] it is made now goes to the IDE log. */
    private fun makeTab(why: String) {
        if (tab != null) return
        waitTimer?.stop()
        waitTimer = null
        log.info("logxy: view of ${spool.path} made at ${spool.lines} lines of output, as $why")
        Look.current = IdeLook
        val made = LogTab(
            listOf(spool.path),
            host,
            openFiles = {},
            openEach = {},
            filter = kept?.filter,
            parsers = kept?.parsers.orEmpty(),
            togglesParts = true,
            // A run's output starts with the build tool's or framework's lines before its log records.
            redetection = Redetection(),
            onStatusChanged = ::statusChanged,
        )
        if (spool.exitCode != null) made.writerEnded()
        made.hiddenColumns = memory.hiddenColumns
        made.columnWidths = memory.columnWidths
        made.shownParts = rememberedParts()
        made.onPartsChanged = { rememberParts(made.shownParts) }
        logPanel.removeAll()
        logPanel.add(made)
        logPanel.revalidate()
        tab = made
        statusChanged()
    }

    /** The records shown while logxy shows, and the exit code once the process ended. */
    val statusText: String
        get() {
            val parts = ArrayList<String>()
            val (_, records, shown) = tab?.status ?: SessionStatus()
            if (showsLogxy && records != null) {
                val all = count(records, "record")
                parts += if (shown == null) all else "${"%,d".format(shown)} of $all"
            }
            spool.exitCode?.let { parts += "exited with code $it" }
            return parts.joinToString(" · ")
        }

    // The toolbar shows the status and the state of the view: it updates soon after.
    private fun statusChanged() = ActivityTracker.getInstance().inc()

    private fun rememberedParts(): Set<LogPart> {
        val names = IdeUiState[PARTS_KEY].orEmpty().split(',')
        return LogPart.entries.filter { it.name in names }.toSet()
    }

    private fun rememberParts(parts: Set<LogPart>) {
        IdeUiState[PARTS_KEY] = parts.joinToString(",") { it.name }
    }

    // Also on exit, not only on dispose: a pinned run tab outlives the next run, which reads the memory.
    private fun remember(tab: LogTab? = this.tab) {
        tab ?: return
        memory.hiddenColumns = tab.hiddenColumns
        memory.columnWidths = tab.columnWidths
    }

    override fun dispose() {
        spool.onGrew = {}
        spool.onExit = {}
        waitTimer?.stop()
        remember()
        tab?.dispose()
    }

    companion object {
        private const val CONSOLE = "console"
        private const val LOGXY = "logxy"

        /** The view of a run, on its process handler, where the run tab's top toolbar finds it. */
        val KEY = Key.create<RunLogView>("logxy.view")
    }
}
