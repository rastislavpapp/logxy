package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.ui.WritableSettings
import org.slf4j.LoggerFactory
import java.awt.Component
import javax.swing.JOptionPane

/** The settings in use, loaded at startup; listeners hear about saved changes. Used on the EDT only. */
object AppSettings : WritableSettings {

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

    override fun updateOrShowError(parent: Component, settings: Settings): Boolean = try {
        update(settings)
        true
    } catch (e: Exception) {
        val message = "Cannot write $path:\n${e.message}"
        JOptionPane.showMessageDialog(parent, message, "Cannot Save", JOptionPane.ERROR_MESSAGE)
        false
    }

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    val location get() = path
}
