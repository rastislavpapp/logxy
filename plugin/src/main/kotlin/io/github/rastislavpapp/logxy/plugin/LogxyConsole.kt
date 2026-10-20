package io.github.rastislavpapp.logxy.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import io.github.rastislavpapp.logxy.app.AppHost
import io.github.rastislavpapp.logxy.app.FlatLook
import io.github.rastislavpapp.logxy.ui.LogTab
import io.github.rastislavpapp.logxy.ui.Look
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * The run's console and a logxy view of [spool], with a Console | logxy switch above them. The choice is remembered
 * per run configuration. The LogTab is made on the first switch to logxy once there is output: on an empty file the
 * parser could only guess Raw.
 */
class LogxyConsole(
    private val project: Project,
    private val configuration: String,
    console: JComponent,
    private val spool: Spool,
) : JPanel(BorderLayout()), Disposable {

    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val logPanel = JPanel(BorderLayout()).apply {
        add(JLabel("Waiting for output…", SwingConstants.CENTER))
    }
    private val status = JLabel()

    @Volatile private var tab: LogTab? = null
    private val tabScheduled = AtomicBoolean()
    private var showsLogxy = false

    init {
        center.add(console, CONSOLE)
        center.add(logPanel, LOGXY)
        val consoleButton = JToggleButton("Console")
        val logxyButton = JToggleButton("logxy")
        ButtonGroup().apply {
            add(consoleButton)
            add(logxyButton)
        }
        consoleButton.addActionListener { switchTo(logxy = false) }
        logxyButton.addActionListener { switchTo(logxy = true) }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(consoleButton)
            add(logxyButton)
            add(status)
        }
        add(bar, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)

        spool.onGrew = {
            val tab = tab
            if (tab != null) {
                tab.fileGrew()
            } else if (tabScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater { if (showsLogxy) makeTab() else tabScheduled.set(false) }
            }
        }
        spool.onExit = { code -> SwingUtilities.invokeLater { status.text = "Process finished with exit code $code" } }
        spool.exitCode?.let { status.text = "Process finished with exit code $it" }

        val logxy = PropertiesComponent.getInstance(project).getValue(key()) == LOGXY
        (if (logxy) logxyButton else consoleButton).isSelected = true
        switchTo(logxy)
    }

    private fun switchTo(logxy: Boolean) {
        showsLogxy = logxy
        PropertiesComponent.getInstance(project).setValue(key(), if (logxy) LOGXY else CONSOLE)
        if (logxy && tab == null && Files.size(spool.path) > 0) makeTab()
        cards.show(center, if (logxy) LOGXY else CONSOLE)
    }

    private fun makeTab() {
        if (tab != null) return
        LafShim.apply()
        Look.current = FlatLook
        val made = LogTab(listOf(spool.path), AppHost, openFiles = {}, openEach = {}, onStatusChanged = {})
        logPanel.removeAll()
        logPanel.add(made)
        logPanel.revalidate()
        tab = made
    }

    private fun key() = "logxy.view.$configuration"

    override fun dispose() {
        spool.onGrew = {}
        spool.onExit = {}
        tab?.dispose()
    }

    private companion object {
        const val CONSOLE = "console"
        const val LOGXY = "logxy"
    }
}
