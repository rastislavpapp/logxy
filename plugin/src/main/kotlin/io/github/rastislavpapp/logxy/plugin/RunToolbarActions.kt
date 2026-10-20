package io.github.rastislavpapp.logxy.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.rastislavpapp.logxy.ui.LogPart
import io.github.rastislavpapp.logxy.ui.LogTab
import javax.swing.Icon
import javax.swing.JComponent

// The actions of the logxy view. The switch, the parser and the status are in the run tab's top toolbar, beside
// Rerun and Stop: in every run tab's toolbar, acting on that tab's view; a tab without one, e.g. of a run started
// before the plugin, hides them. The others take the place of the console's toolbar beside the view: made for one
// view each, as that toolbar acts on the hidden console, whose context does not reach the view.

/** The logxy view of the run tab whose toolbar the action is in; null if it has none. */
private val AnActionEvent.view: RunLogView?
    get() = getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.processHandler?.getUserData(RunLogView.KEY)

/** The run's view while it shows logxy, and the records are there. */
private val AnActionEvent.tab: LogTab?
    get() = view?.takeIf { it.showsLogxy }?.tab

/** Shows text in the toolbar, not only an icon. */
private fun Presentation.showText() = putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)

/** One side of the Console | logxy switch: selected while it shows. */
sealed class ViewSwitch(private val logxy: Boolean) : DumbAwareToggleAction() {
    init {
        templatePresentation.showText()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.view != null
    }

    override fun isSelected(e: AnActionEvent) = e.view?.showsLogxy == logxy

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) e.view?.showView(logxy)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ShowConsole : ViewSwitch(logxy = false)

class ShowLogxy : ViewSwitch(logxy = true)

/**
 * A button with a drop-down arrow that opens one of the view's own menus under itself: a group to the toolbar, so it
 * gets the arrow, which runs [showMenu] instead of listing children.
 */
abstract class MenuButton : ActionGroup(), DumbAware {
    init {
        templatePresentation.isPopupGroup = true
        templatePresentation.isPerformGroup = true
    }

    /** The view's tab the menu is of, null while there is none. */
    protected abstract fun tab(e: AnActionEvent): LogTab?

    protected abstract fun showMenu(tab: LogTab, anchor: JComponent)

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = EMPTY_ARRAY

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = tab(e) != null
        e.presentation.isPerformGroup = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tab = tab(e) ?: return
        val anchor = e.inputEvent?.component as? JComponent ?: return
        showMenu(tab, anchor)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/** The parser in use, as the button's text; its menu picks another. */
class ChooseParser : MenuButton() {
    init {
        templatePresentation.showText()
    }

    override fun tab(e: AnActionEvent) = e.tab

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isVisible = e.view?.showsLogxy == true
        e.tab?.let { e.presentation.text = it.parserText }
    }

    override fun showMenu(tab: LogTab, anchor: JComponent) = tab.showParserMenu(anchor)
}

/** The view's records and the exit code, as a label at the toolbar's end. */
class RunStatus : DumbAwareAction(), CustomComponentAction {
    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        JBLabel().apply {
            border = JBUI.Borders.empty(0, 6)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as JBLabel).text = presentation.text
    }

    override fun update(e: AnActionEvent) {
        val text = e.view?.statusText.orEmpty()
        e.presentation.isVisible = text.isNotEmpty()
        e.presentation.text = text
    }

    override fun actionPerformed(e: AnActionEvent) {}

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/** The actions beside [view]: follow, the columns and Clear All, then the parts to show. */
fun viewActions(view: RunLogView): ActionGroup = DefaultActionGroup(
    FollowRun(view),
    ChooseColumns(view),
    ClearRun(view),
    Separator.getInstance(),
    PartToggle(view, LogPart.FILTER, "Filter", "Show the filter", AllIcons.General.Filter),
    PartToggle(
        view,
        LogPart.HISTOGRAM,
        "Time Histogram",
        "Show the records over time, and charts of their values",
        LogxyIcons.histogram,
    ),
    PartToggle(
        view,
        LogPart.DETAIL,
        "Detail",
        "Show the selected record's whole text",
        AllIcons.Actions.PreviewDetails,
    ),
    PartToggle(
        view,
        LogPart.TEMPLATES,
        "Templates",
        "Show the message templates of the shown records",
        AllIcons.Nodes.Template,
    ),
)

private class FollowRun(private val view: RunLogView) :
    DumbAwareToggleAction("Follow", "Scroll to new records as they come", AllIcons.RunConfigurations.Scroll_down) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = view.tab != null
    }

    override fun isSelected(e: AnActionEvent) = view.tab?.follow == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        view.tab?.follow = state
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private class ChooseColumns(private val view: RunLogView) : MenuButton() {
    init {
        templatePresentation.text = "Columns"
        templatePresentation.description = "Columns to show"
        templatePresentation.icon = AllIcons.Nodes.DataColumn
    }

    override fun tab(e: AnActionEvent) = view.tab?.takeIf { it.canChooseColumns }

    override fun showMenu(tab: LogTab, anchor: JComponent) = tab.showColumnsMenu(anchor)
}

/** Empties the view, as the console's Clear All; the records the run prints next show. */
private class ClearRun(private val view: RunLogView) :
    DumbAwareAction("Clear All", "Remove the records shown so far", AllIcons.Actions.GC) {

    override fun actionPerformed(e: AnActionEvent) = view.clear()
}

/** Shows or hides [part] of the view; the choice holds for all runs. */
private class PartToggle(
    private val view: RunLogView,
    private val part: LogPart,
    text: String,
    description: String,
    icon: Icon,
) : DumbAwareToggleAction(text, description, icon) {

    override fun isSelected(e: AnActionEvent) = part in view.shownParts

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        view.shownParts = if (state) view.shownParts + part else view.shownParts - part
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/** Icons the IDE has none like. */
object LogxyIcons {
    /** Bars of counts over time. */
    val histogram: Icon = IconLoader.getIcon("/icons/histogram.svg", LogxyIcons::class.java)
}
