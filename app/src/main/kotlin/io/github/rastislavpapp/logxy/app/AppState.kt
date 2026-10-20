package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.PropertiesFiles
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.ui.UiState
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Small UI state remembered between runs (theme, last folder, …), in `ui.properties`; unlike [AppSettings], not
 * configuration. The views' own keys are in [UiState].
 */
object AppState : UiState {

    private val log = LoggerFactory.getLogger(AppState::class.java)
    private val path = Settings.directory().resolve("ui.properties")
    private val properties = try {
        PropertiesFiles.load(path)
    } catch (e: Exception) {
        log.warn("Cannot read {}", path, e)
        Properties()
    }

    override operator fun get(key: String): String? = properties.getProperty(key)

    override operator fun set(key: String, value: String?) {
        if (value == null) properties.remove(key) else properties[key] = value
        try {
            PropertiesFiles.store(path, properties, "logxy UI state")
        } catch (e: Exception) {
            log.warn("Cannot write {}", path, e)
        }
    }

    const val THEME = "theme"
    const val TITLE_BAR = "titleBar.integrated"
    const val LAST_DIRECTORY = "lastDirectory"
    const val PREFERENCES_SECTION = "preferences.section"
}
