package io.github.rastislavpapp.logxy.plugin

import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.content.Content
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * The one place that knows how a run or debug tab is built: the console is the content
 * [ExecutionConsole.CONSOLE_CONTENT_ID] of the tab's layout, shown in a grid cell's wrapper panel. The cell does not
 * follow a change of the content's component, so the wrapper takes the console's place in that panel too. The
 * content's actions are the console's toolbar beside it. A debug tab shows its contents as sub-tabs, the console in
 * the second.
 */
object ConsoleSwap {

    private const val LOGXY_CONTENT_ID = "logxy"

    /** The debugger's sub-tab of the console; the layout's tab indexes order the sub-tabs. */
    private const val DEBUG_CONSOLE_TAB = 1

    enum class Outcome {
        INSTALLED,

        /** The tab has no console content. */
        NO_CONSOLE,

        /** The console takes typed input at a prompt, as a REPL does, which the wrapper would hide. */
        PROMPT,
    }

    /**
     * Wraps the console of [descriptor] in what [wrap] makes of it, which must hold the console; [wrap] also gets
     * the console's toolbar.
     */
    fun install(
        descriptor: RunContentDescriptor,
        wrap: (console: JComponent, toolbar: ConsoleToolbar) -> JComponent,
    ): Outcome {
        val content = consoleContent(descriptor) ?: return Outcome.NO_CONSOLE
        val console = content.component ?: return Outcome.NO_CONSOLE
        if (UIUtil.uiTraverser(console).any { it is LanguageConsoleView }) return Outcome.PROMPT
        val parent = console.parent ?: return Outcome.NO_CONSOLE
        val index = parent.components.indexOf(console)
        val constraints = (parent.layout as? BorderLayout)?.getConstraints(console)
        // Taking the console in removes it from the cell.
        val wrapper = wrap(console, ConsoleToolbar(content, descriptor.component))
        parent.add(wrapper, constraints, index)
        parent.revalidate()
        content.component = wrapper
        return Outcome.INSTALLED
    }

    /**
     * Adds [view] to [descriptor]'s debug tab as a sub-tab after the console's, with [actions] beside it. False if the
     * tab has no layout. Where the user drags the sub-tab, the IDE keeps it for the next session.
     */
    fun addSubTab(descriptor: RunContentDescriptor, view: JComponent, actions: ActionGroup): Boolean {
        val ui = descriptor.runnerLayoutUi ?: return false
        val content = ui.createContent(LOGXY_CONTENT_ID, view, "logxy", null, view)
        content.isCloseable = false
        content.setActions(actions, ActionPlaces.DEBUGGER_TOOLBAR, view)
        ui.addContent(content, DEBUG_CONSOLE_TAB + 1, PlaceInGrid.center, false)
        return true
    }

    private fun consoleContent(descriptor: RunContentDescriptor): Content? =
        descriptor.runnerLayoutUi?.findContent(ExecutionConsole.CONSOLE_CONTENT_ID)
}

/**
 * The toolbar beside the console, found in the run tab's [root]. Its group's children are swapped in place rather
 * than the content's actions set anew: a run tab whose content's actions change shows the content's tab above it.
 * The IDE performs no action whose toolbar's target is hidden, as the console is while the view shows, so the
 * actions shown in its place get a target of their own.
 */
class ConsoleToolbar internal constructor(content: Content, private val root: JComponent) {

    private val group = content.actions as? DefaultActionGroup
    private val consoleActions: Array<AnAction> = group?.childActionsOrStubs.orEmpty().toList().toTypedArray()
    private val console = content.component

    /** Read as the toolbar updates, off the UI thread. */
    @Volatile private var shown: ActionGroup? = null

    init {
        group?.removeAll()
        group?.add(Shown())
    }

    /** Shows [actions], which act on [target], instead of the console's; null shows the console's again. */
    fun show(actions: ActionGroup?, target: JComponent) {
        shown = actions
        val toolbar = UIUtil.uiTraverser(root).filter(ActionToolbar::class.java).find { it.actionGroup === group }
        toolbar?.targetComponent = if (actions != null) target else console
    }

    // The shown group as the one child, not its children: a toolbar inlines a non-popup group, and
    // `getChildren` is the IDE's to call.
    private inner class Shown : ActionGroup(), DumbAware {
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val actions = shown
            return if (actions != null) arrayOf(actions) else consoleActions
        }
    }
}
