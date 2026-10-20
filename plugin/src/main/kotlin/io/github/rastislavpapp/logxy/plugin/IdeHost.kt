package io.github.rastislavpapp.logxy.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.ui.DetailPane
import io.github.rastislavpapp.logxy.ui.LogViewHost
import io.github.rastislavpapp.logxy.ui.PlainDetailArea
import io.github.rastislavpapp.logxy.ui.QueryText
import io.github.rastislavpapp.logxy.ui.SettingsDialogs
import io.github.rastislavpapp.logxy.ui.UiState
import io.github.rastislavpapp.logxy.ui.WritableSettings
import kotlinx.coroutines.CoroutineDispatcher
import java.awt.Component
import java.awt.Window
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

/**
 * The IDE's side of the views: settings in the IDE's config directory, UI state in its properties, the visual filter
 * editor only and a plain detail pane. There are no settings dialogs yet, so the views offer no editing of parsers;
 * [ImportAppSettings] brings in the app's.
 */
object IdeHost : LogViewHost {

    override val settings: WritableSettings get() = IdeSettings

    override val state: UiState get() = IdeUiState

    override val uiDispatcher: CoroutineDispatcher get() = EdtDispatcher

    override fun detailPane(): DetailPane = PlainDetailArea()

    override fun queryText(onApply: () -> Unit): QueryText? = null

    override val settingsDialogs: SettingsDialogs? get() = null

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

/**
 * The plugin's settings, in `logxy/logxy.properties` of the IDE's config directory: apart from the app's, as both
 * may run at once. Used on the UI thread only.
 */
object IdeSettings : WritableSettings {

    private val log = logger<IdeSettings>()
    private val path: Path by lazy { PathManager.getConfigDir().resolve("logxy/logxy.properties") }
    private val listeners = ArrayList<() -> Unit>()

    override var current: Settings = load()
        private set

    private fun load(): Settings = try {
        Settings.load(path)
    } catch (e: Exception) {
        log.warn("logxy: cannot read settings from $path", e)
        Settings()
    }

    override fun updateOrShowError(parent: Component, settings: Settings): Boolean {
        try {
            Settings.save(path, settings)
        } catch (e: Exception) {
            Messages.showErrorDialog(parent, "Cannot write $path:\n${e.message}", "Cannot Save")
            return false
        }
        current = settings
        listeners.toList().forEach { it() }
        return true
    }

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
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
