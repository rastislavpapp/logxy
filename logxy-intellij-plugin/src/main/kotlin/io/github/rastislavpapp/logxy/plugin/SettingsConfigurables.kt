package io.github.rastislavpapp.logxy.plugin

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.Messages
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.ui.LevelsPage
import io.github.rastislavpapp.logxy.ui.LinkButton
import io.github.rastislavpapp.logxy.ui.Look
import io.github.rastislavpapp.logxy.ui.ParsersPage
import io.github.rastislavpapp.logxy.ui.SettingsPage
import io.github.rastislavpapp.logxy.ui.addNote
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

private const val IMPORT_TITLE = "Import from logxy App"

/** Tools › logxy in the IDE's Settings: what its pages are for, and the import of the app's settings. */
class LogxyConfigurable : SearchableConfigurable {

    override fun getId() = "logxy"

    override fun getDisplayName() = "logxy"

    override fun createComponent(): JComponent {
        Look.current = IdeLook
        return JPanel(MigLayout("insets 0, wrap 1", "[grow, fill]")).apply {
            addNote(
                "logxy shows a run's output in parsed columns: switch a run between Console and logxy in the run " +
                    "tab's toolbar. The parsers and log levels it uses are set on the pages below.",
            )
            val import = LinkButton("Import Parsers, Saved Filters and Log Levels from the logxy App…") {
                importAppSettings(this)
            }
            add(import, "growx 0")
        }
    }

    override fun isModified() = false

    override fun apply() {}
}

/**
 * A page of the settings, editing a copy of them; it starts afresh when they change otherwise, e.g. by the import.
 */
abstract class PageConfigurable(private val id: String, private val name: String) : SearchableConfigurable {

    private val holder = JPanel(BorderLayout())
    private var page: SettingsPage? = null
    private var applying = false
    private val onChanged: () -> Unit = { if (!applying) reset() }

    protected abstract fun makePage(settings: Settings): SettingsPage

    override fun getId() = id

    override fun getDisplayName() = name

    override fun createComponent(): JComponent {
        Look.current = IdeLook
        IdeSettings.instance.addListener(onChanged)
        return holder
    }

    override fun isModified(): Boolean {
        val current = IdeSettings.instance.current
        return page?.isModified(current) == true
    }

    override fun apply() {
        val page = page ?: return
        page.problem()?.let { throw ConfigurationException(it) }
        val settings = IdeSettings.instance
        applying = true
        try {
            settings.update(page.applyTo(settings.current))
        } finally {
            applying = false
        }
    }

    override fun reset() {
        holder.removeAll()
        val made = makePage(IdeSettings.instance.current)
        holder.add(made, BorderLayout.CENTER)
        holder.revalidate()
        holder.repaint()
        page = made
    }

    override fun disposeUIResources() {
        IdeSettings.instance.removeListener(onChanged)
        page = null
    }
}

/**
 * Tools › logxy › Parsers; from a run's view, opened with a preview of its output in [previewFile], [select] selected
 * and [adopt] added as a new parser.
 */
class ParsersConfigurable(
    private val previewFile: Path? = null,
    private val select: Parser? = null,
    private var adopt: Parser? = null,
) : PageConfigurable("logxy.parsers", "Parsers") {

    // Adopted once: Reset starts from the saved parsers.
    override fun makePage(settings: Settings): SettingsPage =
        ParsersPage(settings, previewFile, select, adopt).also { adopt = null }
}

/** Tools › logxy › Log Levels. */
class LevelsConfigurable : PageConfigurable("logxy.levels", "Log Levels") {
    override fun makePage(settings: Settings): SettingsPage = LevelsPage(settings)
}

/**
 * Copies the standalone app's parsers, saved filters and level mappings over the plugin's, once: the two keep apart,
 * as both may run at once. How to open files does not apply to the plugin, so it stays.
 */
private fun importAppSettings(parent: Component) {
    val path = Settings.defaultPath()
    if (!Files.exists(path)) {
        Messages.showInfoMessage(parent, "The logxy app has no settings at $path.", IMPORT_TITLE)
        return
    }
    val app = try {
        Settings.load(path)
    } catch (e: Exception) {
        Messages.showErrorDialog(parent, "Cannot read $path:\n${e.message}", IMPORT_TITLE)
        return
    }
    val answer = Messages.showOkCancelDialog(
        parent,
        "Replace the plugin's parsers, saved filters and level mappings with the logxy app's?",
        IMPORT_TITLE,
        "Import",
        Messages.getCancelButton(),
        null,
    )
    if (answer != Messages.OK) return
    val settings = IdeSettings.instance
    settings.update(
        settings.current.copy(
            parsers = app.parsers,
            levels = app.levels,
            filters = app.filters,
            deletedBuiltIns = app.deletedBuiltIns,
        ),
    )
}
