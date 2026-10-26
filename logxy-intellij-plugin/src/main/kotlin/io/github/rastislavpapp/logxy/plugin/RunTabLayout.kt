package io.github.rastislavpapp.logxy.plugin

import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import javax.swing.JComponent

/**
 * The one place that knows how a run or debug tab is built. Each of the tab's contents is a sub-tab once there is more
 * than one: in a run tab, the console and the logxy view; in a debug tab, also the frames and variables before them.
 * Other plugins add their own, as Spring Boot adds Beans and Mappings.
 */
object RunTabLayout {

    private const val LOGXY_CONTENT_ID = "logxy"

    /**
     * Asks the layout for a sub-tab of its own, after the last one. A fixed index would join the sub-tab that already
     * has it, as Spring Boot's Beans has the one after the debugger's console, and the joined sub-tab shows both.
     * Spring Boot adds its sub-tabs as the tab is built, before the view can be added, so the view comes after them.
     */
    private const val NEW_TAB = Int.MAX_VALUE

    /**
     * Adds [view] to [descriptor]'s tab as the last sub-tab, with [actions] as its toolbar, at the action [place].
     * Null if the tab has no layout. Where the user drags the sub-tab, the IDE keeps it for the next run.
     */
    fun addSubTab(descriptor: RunContentDescriptor, view: JComponent, actions: ActionGroup, place: String): Content? {
        val ui = descriptor.runnerLayoutUi ?: return null
        val content = ui.createContent(LOGXY_CONTENT_ID, view, "logxy", LogxyIcons.logxy, view)
        content.isCloseable = false
        content.setActions(actions, place, view)
        ui.addContent(content, NEW_TAB, PlaceInGrid.center, false)
        addStatusToolbar(descriptor, place)
        return content
    }

    /**
     * Selects [view]'s sub-tab if [logxy], else the console's, and then calls [selected] each time the user selects
     * either of them, with true for the view. Not for other sub-tabs: picking Beans keeps the last choice.
     */
    fun followSelection(
        descriptor: RunContentDescriptor,
        view: Content,
        logxy: Boolean,
        selected: (logxy: Boolean) -> Unit,
    ) {
        val ui = descriptor.runnerLayoutUi ?: return
        val console = ui.findContent(ExecutionConsole.CONSOLE_CONTENT_ID)
        (if (logxy) view else console)?.let { ui.selectAndFocus(it, false, false) }
        ui.addListener(
            object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) {
                    if (event.operation != ContentManagerEvent.ContentOperation.add) return
                    when (event.content) {
                        view -> selected(true)
                        console -> selected(false)
                    }
                }
            },
            descriptor,
        )
    }

    /**
     * Puts the parser and the status at the right end of the tab's top row, in the toolbar the IDE leaves empty there.
     * The buttons and sub-tabs before them then stay in place as the parser shows and hides.
     */
    private fun addStatusToolbar(descriptor: RunContentDescriptor, place: String) {
        val toolbar = ActionManager.getInstance().getAction("logxy.StatusToolbar") as? ActionGroup ?: return
        descriptor.runnerLayoutUi?.options?.setTopMiddleToolbar(toolbar, place)
    }
}
