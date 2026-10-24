package io.github.rastislavpapp.logxy.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.ui.WritableSettings
import java.awt.Component
import java.util.Properties

/**
 * The plugin's settings, as the same properties the app keeps in its file, but in the IDE's own storage
 * (`logxy.xml` of its options), apart from the app's, as both may run at once. Used on the UI thread, except that
 * the IDE may load the state on any thread.
 */
@Service(Service.Level.APP)
@State(name = "logxy", storages = [Storage("logxy.xml")])
class IdeSettings : PersistentStateComponent<IdeSettings.Stored>, WritableSettings {

    /** The settings' properties, as the IDE writes them. */
    class Stored {
        var properties: MutableMap<String, String> = LinkedHashMap()
    }

    private val listeners = ArrayList<() -> Unit>()

    @Volatile override var current: Settings = Settings()
        private set

    /** Saves [settings] and tells the listeners; storing in the IDE cannot fail. */
    fun update(settings: Settings) {
        current = settings
        listeners.toList().forEach { it() }
    }

    override fun updateOrShowError(parent: Component, settings: Settings): Boolean {
        update(settings)
        return true
    }

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    override fun getState() = Stored().apply {
        Settings.toProperties(current).forEach { (key, value) -> properties["$key"] = "$value" }
    }

    // Also when the settings change outside this IDE, e.g. by settings sync; the listeners, which update views, run
    // on the UI thread.
    override fun loadState(state: Stored) {
        val settings = Settings.fromProperties(Properties().apply { putAll(state.properties) })
        if (settings == current) return
        current = settings
        ApplicationManager.getApplication().invokeLater { listeners.toList().forEach { it() } }
    }

    companion object {
        val instance: IdeSettings get() = service()
    }
}
