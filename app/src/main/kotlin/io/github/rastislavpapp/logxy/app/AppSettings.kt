package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.session.SettingsStore
import org.slf4j.LoggerFactory

/** The settings in use, loaded at startup; listeners hear about saved changes. Used on the EDT only. */
object AppSettings : SettingsStore {

    private val log = LoggerFactory.getLogger(AppSettings::class.java)
    private val path = Settings.defaultPath()
    private val listeners = ArrayList<() -> Unit>()

    override var current: Settings = try {
        Settings.load(path)
    } catch (e: Exception) {
        log.warn("Cannot read settings from {}", path, e)
        Settings()
    }
        private set

    /** Saves [settings] and tells the listeners; throws if the file cannot be written. */
    fun update(settings: Settings) {
        Settings.save(path, settings)
        current = settings
        listeners.toList().forEach { it() }
    }

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    val location get() = path
}
