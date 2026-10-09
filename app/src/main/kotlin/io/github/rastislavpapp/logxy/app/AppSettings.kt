package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings

/** The settings in use, loaded at startup; listeners hear about saved changes. Used on the EDT only. */
object AppSettings {

    private val path = Settings.defaultPath()
    private val listeners = ArrayList<() -> Unit>()

    var current: Settings = try {
        Settings.load(path)
    } catch (e: Exception) {
        System.err.println("Cannot read settings from $path: $e")
        Settings()
    }
        private set

    /** Saves [settings] and tells the listeners; throws if the file cannot be written. */
    fun update(settings: Settings) {
        Settings.save(path, settings)
        current = settings
        listeners.toList().forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    val location get() = path
}
