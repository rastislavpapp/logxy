package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import java.nio.file.Files
import java.util.Properties

/** Small UI state remembered between runs (theme, last folder, …); unlike [AppSettings], not configuration. */
object AppState {

    private val path = Settings.directory().resolve("ui.properties")
    private val properties = Properties().apply {
        try {
            if (Files.exists(path)) Files.newBufferedReader(path).use { load(it) }
        } catch (e: Exception) {
            System.err.println("Cannot read $path: $e")
        }
    }

    operator fun get(key: String): String? = properties.getProperty(key)

    operator fun set(key: String, value: String?) {
        if (value == null) properties.remove(key) else properties[key] = value
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { properties.store(it, "logxy UI state") }
        } catch (e: Exception) {
            System.err.println("Cannot write $path: $e")
        }
    }

    const val THEME = "theme"
    const val TITLE_BAR = "titleBar.integrated"
    const val LAST_DIRECTORY = "lastDirectory"
    const val DETAIL_DIVIDER = "detail.divider"
    const val FILTER_MODE = "filter.mode"
}
