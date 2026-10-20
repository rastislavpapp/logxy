package io.github.rastislavpapp.logxy.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.ui.Messages
import io.github.rastislavpapp.logxy.core.config.Settings
import java.nio.file.Files
import javax.swing.JOptionPane

private const val TITLE = "Import from logxy App"

/**
 * Copies the standalone app's parsers, saved filters and level mappings over the plugin's, once: the two keep apart
 * files, as both may run at once. How to open files does not apply to the plugin, so it stays.
 */
class ImportAppSettings : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val path = Settings.defaultPath()
        if (!Files.exists(path)) {
            Messages.showInfoMessage(e.project, "The logxy app has no settings at $path.", TITLE)
            return
        }
        val app = try {
            Settings.load(path)
        } catch (ex: Exception) {
            Messages.showErrorDialog(e.project, "Cannot read $path:\n${ex.message}", TITLE)
            return
        }
        val answer = Messages.showOkCancelDialog(
            e.project,
            "Replace the plugin's parsers, saved filters and level mappings with the logxy app's?",
            TITLE,
            "Import",
            Messages.getCancelButton(),
            null,
        )
        if (answer != Messages.OK) return
        val merged = IdeSettings.current.copy(
            parsers = app.parsers,
            levels = app.levels,
            filters = app.filters,
            deletedBuiltIns = app.deletedBuiltIns,
        )
        val parent = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) ?: JOptionPane.getRootFrame()
        IdeSettings.updateOrShowError(parent, merged)
    }
}
