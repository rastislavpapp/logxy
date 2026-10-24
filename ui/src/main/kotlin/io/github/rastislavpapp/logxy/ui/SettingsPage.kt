package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.config.Settings
import javax.swing.Icon
import javax.swing.JPanel

/**
 * A section of the settings, shown in the app's Preferences and in the IDE's Settings. It edits a copy of the values
 * it was made with; the host saves what [applyTo] gives.
 */
abstract class SettingsPage(val title: String, val icon: Icon) : JPanel() {

    /** Why the page cannot be saved as it is, or null. */
    open fun problem(): String? = null

    /** [settings] with this page's values. */
    abstract fun applyTo(settings: Settings): Settings

    /**
     * Whether the page changed any of [settings]. Asked often while the page shows, so it must not end an edit in
     * progress.
     */
    open fun isModified(settings: Settings): Boolean = applyTo(settings) != settings
}
