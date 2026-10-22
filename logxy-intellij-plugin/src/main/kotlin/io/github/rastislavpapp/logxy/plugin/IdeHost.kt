package io.github.rastislavpapp.logxy.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.ui.DetailPane
import io.github.rastislavpapp.logxy.ui.FindView
import io.github.rastislavpapp.logxy.ui.LogViewHost
import io.github.rastislavpapp.logxy.ui.QueryText
import io.github.rastislavpapp.logxy.ui.SettingsDialogs
import io.github.rastislavpapp.logxy.ui.UiState
import io.github.rastislavpapp.logxy.ui.WritableSettings
import kotlinx.coroutines.CoroutineDispatcher
import java.awt.Window
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

/**
 * The IDE's side of the views in [project]: settings in the IDE's storage and pages of its Settings, UI state in its
 * properties, its search bar, the visual filter editor only and no detail pane.
 */
class IdeHost(private val project: Project) : LogViewHost, SettingsDialogs {

    override val settings: WritableSettings get() = IdeSettings.instance

    override val state: UiState get() = IdeUiState

    override val uiDispatcher: CoroutineDispatcher get() = EdtDispatcher

    // Expanded rows show records whole.
    override fun detailPane(): DetailPane? = null

    override fun queryText(onApply: () -> Unit): QueryText? = null

    override fun findView(tab: JComponent, onSearch: (TextSearch?) -> Unit, onFind: (Boolean) -> Unit): FindView =
        IdeFindView(project, tab, onSearch, onFind)

    override val settingsDialogs: SettingsDialogs get() = this

    // The page cannot pick the parser for the file as the app's can: the IDE's dialog has only OK and Cancel.
    override fun editParsers(owner: Window?, file: Path, select: Parser, offerUse: Boolean, adopt: Parser?): Parser? {
        ShowSettingsUtil.getInstance().editConfigurable(project, ParsersConfigurable(file, select, adopt))
        return null
    }

    override fun editLevelMapping(owner: Window?) {
        ShowSettingsUtil.getInstance().editConfigurable(project, LevelsConfigurable())
    }

    // A run's view shows one file, which is never a merge.
    override fun chooseParsersPerFile(
        owner: Window?,
        paths: List<Path>,
        names: List<String>,
        descriptions: List<String>,
        current: List<Parser?>,
    ): List<Parser?>? = null

    override val moreMemoryHint get() = "Give the IDE more memory in Help › Change Memory Settings to copy more."
}

/** The views' UI state, among the IDE's application properties. */
object IdeUiState : UiState {

    private fun key(key: String) = "logxy.$key"

    override fun get(key: String): String? = PropertiesComponent.getInstance().getValue(key(key))

    override fun set(key: String, value: String?) = PropertiesComponent.getInstance().setValue(key(key), value)
}

/**
 * Runs on the event dispatch thread through Swing's queue, as kotlinx-coroutines-swing would; the views only touch
 * Swing, so they need none of the IDE's locks or modality.
 */
private object EdtDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
}
