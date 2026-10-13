package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.config.Settings

/** Where the settings in use come from; the standalone app reads a file, an IDE would use its own storage. */
interface SettingsStore {
    val current: Settings

    /** [listener] runs after the settings changed, on the thread that changed them. */
    fun addListener(listener: () -> Unit)

    /** Stops calling [listener], e.g. when a session is disposed. */
    fun removeListener(listener: () -> Unit)
}
