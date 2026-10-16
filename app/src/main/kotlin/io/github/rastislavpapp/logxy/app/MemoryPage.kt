package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.config.VmOptions
import io.github.rastislavpapp.logxy.core.formatByteSize
import net.miginfocom.swing.MigLayout
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.UIManager

/**
 * The most memory logxy may use, kept in the launcher's [VmOptions] rather than the [Settings]; it applies only
 * to a newly started JVM. Without the packaged launcher there is nothing to keep it in, so the page only tells.
 */
class MemoryPage : PreferencesPage("Memory", Icons.memory) {

    private val vmOptions = Restart.vmOptions

    /** The `-Xmx` value as the file has it, so an untouched page saves nothing back. */
    private val initial = vmOptions?.let { VmOptions.memoryLimit(it.current()) }.orEmpty()
    private val xmx = JTextField(initial).apply { isEnabled = vmOptions != null }

    init {
        val now = "logxy can use up to ${formatByteSize(Runtime.getRuntime().maxMemory())} now."
        val hint = when {
            vmOptions == null -> "$now Started without its launcher, logxy takes the limit from Java's -Xmx option."
            vmOptions.installFailed ->
                "$now It cannot write ${vmOptions.installed}, so a limit set here does not apply. " +
                    "Move logxy to a folder you can write to."
            else -> "$now Large logs, and many open at once, need more. A new limit applies after a restart."
        }
        // The packaged launcher's defaults raise the JVM's; see the jpackage task.
        val example = "For example 8g or 8192m; empty for the default, " +
            if (vmOptions != null) "half of this computer's memory." else "a quarter of this computer's memory."
        layout = MigLayout("insets 0", "[]", "[]para[][]para[]")
        add(note(hint), "wrap")
        add(JLabel("-Xmx"), "split 2")
        add(xmx, "w 100!, wrap")
        add(note(example), "wrap")
        if (vmOptions != null) {
            // Restart.atStartup installs the user's file, so an edit there applies from the next start.
            val file = "The limit is kept in ${vmOptions.user} with the other Java options. " +
                "Edit that file to set any other option; it applies from the next start."
            add(note(file))
        }
    }

    private fun note(text: String) = JLabel(text).apply { foreground = UIManager.getColor("Label.disabledForeground") }

    override fun applyTo(settings: Settings) = settings

    /** The launcher's options with the typed limit; null without the packaged launcher or if it is unchanged. */
    fun options(): List<String>? {
        val options = vmOptions ?: return null
        val limit = xmx.text.trim()
        if (limit == initial) return null
        return VmOptions.withMemoryLimit(options.current(), limit.ifEmpty { null }, options.defaults)
    }
}
