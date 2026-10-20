package io.github.rastislavpapp.logxy.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.util.ui.JBUI
import io.github.rastislavpapp.logxy.core.session.SessionStatus
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

/**
 * A run's console and a logxy view of its [spool], with a Console | logxy switch above them; [memory] keeps the
 * choice, the filter and the hidden columns for the next run. The view is made once logxy shows and there is enough
 * output to detect its parser.
 */
class RunLogView(
    console: JComponent,
    private val spool: Spool,
    private val memory: RunMemory,
) : JPanel(BorderLayout()), Disposable {

    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val logPanel = JPanel(BorderLayout()).apply {
        add(JLabel("Waiting for output…", SwingConstants.CENTER))
    }
    private val controls = JPanel(BorderLayout()).apply { isOpaque = false }
    private val status = JLabel().apply { border = JBUI.Borders.empty(0, 8) }

    /** Set on the UI thread; read on the process's threads, which push new output to it. */
    @Volatile private var tab: LogTab? = null
    private val checkScheduled = AtomicBoolean()
    private var waitTimer: Timer? = null
    private var showsLogxy = memory.showsLogxy

    init {
        center.add(console, CONSOLE)
        center.add(logPanel, LOGXY)
        val bar = JPanel(BorderLayout()).apply {
            add(viewSwitch(), BorderLayout.WEST)
            add(controls, BorderLayout.CENTER)
            add(status, BorderLayout.EAST)
            border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor())
        }
        add(bar, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)

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
                showStatus()
                makeTabIfReady()
                remember()
            }
        }
        showView(showsLogxy)
    }

    /** The Console | logxy switch, as toggles in an IDE toolbar. */
    private fun viewSwitch(): JComponent {
        val group = DefaultActionGroup(ViewToggle("Console", logxy = false), ViewToggle("logxy", logxy = true))
        val toolbar = ActionManager.getInstance().createActionToolbar("LogxyRunView", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun showView(logxy: Boolean) {
        showsLogxy = logxy
        memory.showsLogxy = logxy
        cards.show(center, if (logxy) LOGXY else CONSOLE)
        controls.isVisible = logxy
        makeTabIfReady()
        showStatus()
    }

    /** Makes the view if logxy shows and the output is enough to detect its parser, or will be after a wait. */
    private fun makeTabIfReady() {
        if (tab != null || !showsLogxy) return
        val ended = spool.exitCode != null
        if (spool.lines >= DETECT_LINES || ended) return makeTab()
        if (spool.lines == 0) return
        if (waitTimer != null) return
        waitTimer = Timer(DETECT_WAIT_MILLIS) { makeTab() }.apply {
            isRepeats = false
            start()
        }
    }

    private fun makeTab() {
        if (tab != null) return
        waitTimer?.stop()
        Look.current = IdeLook
        val made = LogTab(
            listOf(spool.path),
            IdeHost,
            openFiles = {},
            openEach = {},
            filter = memory.filter,
            onStatusChanged = ::showStatus,
        )
        made.hiddenColumns = memory.hiddenColumns
        logPanel.removeAll()
        logPanel.add(made)
        logPanel.revalidate()
        controls.add(made.fileControls, BorderLayout.WEST)
        controls.revalidate()
        tab = made
    }

    /** The records shown while logxy shows, and the exit code once the process ended. */
    private fun showStatus() {
        val parts = ArrayList<String>()
        val (_, records, shown) = tab?.status ?: SessionStatus()
        if (showsLogxy && records != null) {
            val all = count(records, "record")
            parts += if (shown == null) all else "${"%,d".format(shown)} of $all"
        }
        spool.exitCode?.let { parts += "exited with code $it" }
        status.text = parts.joinToString(" · ")
    }

    // Also on exit, not only on dispose: a pinned run tab outlives the next run, which reads the memory.
    private fun remember() {
        val tab = tab ?: return
        memory.filter = tab.filter
        memory.hiddenColumns = tab.hiddenColumns
    }

    override fun dispose() {
        spool.onGrew = {}
        spool.onExit = {}
        waitTimer?.stop()
        remember()
        tab?.dispose()
    }

    /** One side of the switch: selected while it shows. */
    private inner class ViewToggle(text: String, private val logxy: Boolean) : ToggleAction(text) {
        init {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun isSelected(e: AnActionEvent) = showsLogxy == logxy

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) showView(logxy)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private companion object {
        const val CONSOLE = "console"
        const val LOGXY = "logxy"
    }
}
