package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.formatByteSize
import net.miginfocom.swing.MigLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager

/** The most memory logxy may use; it applies only to a newly started JVM. */
class MemoryPage : PreferencesPage("Memory", Icons.memory) {

    // The packaged launcher raises the JVM default; see the jpackage task.
    private val default = JRadioButton(
        if (System.getProperty("jpackage.app-path") != null) "Default (half of this computer's memory)"
        else "Java default (a quarter of this computer's memory)"
    )
    private val custom = JRadioButton("Up to")
    private val gigabytes = JSpinner(SpinnerNumberModel(8, 1, 1024, 1))

    init {
        val saved = AppSettings.current.maxMemory
        if (saved == null) default.isSelected = true else {
            custom.isSelected = true
            gigabytes.value = gigabytesOf(saved)
        }
        ButtonGroup().apply { add(default); add(custom) }
        gigabytes.isEnabled = custom.isSelected
        custom.addChangeListener { gigabytes.isEnabled = custom.isSelected }

        val hint = "logxy can use up to ${formatByteSize(Runtime.getRuntime().maxMemory())} now. " +
            "Large logs, and many open at once, need more. A new limit applies after a restart."
        layout = MigLayout("insets 0", "[]", "[]para[][]")
        add(JLabel(hint).apply { foreground = UIManager.getColor("Label.disabledForeground") }, "wrap")
        add(default, "wrap")
        add(custom, "split 3")
        add(gigabytes, "w 70!")
        add(JLabel("GB"))
    }

    override fun applyTo(settings: Settings) = settings.copy(maxMemory = if (custom.isSelected) "${gigabytes.value}g" else null)

    /** Whole gigabytes for a saved limit such as `8g` or `6144m`, at least 1. */
    private fun gigabytesOf(limit: String): Int {
        val amount = limit.dropLast(1).toInt()
        return if (limit.endsWith("g")) amount else maxOf(1, (amount + 512) / 1024)
    }
}
