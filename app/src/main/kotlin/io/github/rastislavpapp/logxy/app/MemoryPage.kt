package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.config.VmOptions
import io.github.rastislavpapp.logxy.core.formatByteSize
import net.miginfocom.swing.MigLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager
import kotlin.math.roundToInt

/**
 * The most memory logxy may use, kept in the launcher's [VmOptions] rather than the [Settings]; it applies only
 * to a newly started JVM. Without the packaged launcher there is nothing to keep it in, so the page only tells.
 */
class MemoryPage : PreferencesPage("Memory", Icons.memory) {

    private val vmOptions = Restart.vmOptions

    // The packaged launcher's defaults raise the JVM's; see the jpackage task.
    private val default = JRadioButton(
        if (vmOptions != null) {
            "Default (half of this computer's memory)"
        } else {
            "Java default (a quarter of this computer's memory)"
        },
    )
    private val custom = JRadioButton("Up to")
    private val gigabytes = JSpinner(SpinnerNumberModel(8, 1, MAX_GIGABYTES, 1))

    init {
        val limit = vmOptions?.let { VmOptions.memoryLimit(it.current()) }
        if (limit == null) {
            default.isSelected = true
        } else {
            custom.isSelected = true
            val rounded = (limit.toDouble() / (1L shl 30)).roundToInt()
            gigabytes.value = rounded.coerceIn(1, MAX_GIGABYTES)
        }
        ButtonGroup().apply {
            add(default)
            add(custom)
        }
        default.isEnabled = vmOptions != null
        custom.isEnabled = vmOptions != null
        gigabytes.isEnabled = custom.isSelected && vmOptions != null
        custom.addChangeListener { gigabytes.isEnabled = custom.isSelected }

        val now = "logxy can use up to ${formatByteSize(Runtime.getRuntime().maxMemory())} now."
        val hint = when {
            vmOptions == null -> "$now Started without its launcher, logxy takes the limit from Java's -Xmx option."
            !vmOptions.isInstalled() ->
                "$now It cannot write ${vmOptions.installed}, so a limit set here does not apply. " +
                    "Move logxy to a folder you can write to."
            else -> "$now Large logs, and many open at once, need more. A new limit applies after a restart."
        }
        layout = MigLayout("insets 0", "[]", "[]para[][]")
        add(JLabel(hint).apply { foreground = UIManager.getColor("Label.disabledForeground") }, "wrap")
        add(default, "wrap")
        add(custom, "split 3")
        add(gigabytes, "w 70!")
        add(JLabel("GB"))
    }

    override fun applyTo(settings: Settings) = settings

    /** The launcher's options with the chosen limit; null without the packaged launcher. */
    fun options(): List<String>? {
        val options = vmOptions ?: return null
        val limit = if (custom.isSelected) gigabytes.value as Int else null
        return VmOptions.withMemoryLimit(options.current(), limit, options.defaults)
    }

    private companion object {
        const val MAX_GIGABYTES = 1024
    }
}
