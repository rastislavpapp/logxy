package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.config.Settings
import net.miginfocom.swing.MigLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList

/** Whether opening files asks how to open them, or always does the same. */
class OpeningPage : PreferencesPage("Opening Files", Icons.open) {

    private val ask = JCheckBox("Ask questions when opening files")
    private val modeLabel = JLabel("Open files:")
    private val mode = JComboBox(OpenMode.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
                super.getListCellRendererComponent(list, label(value as OpenMode), index, isSelected, cellHasFocus)
        }
    }
    private val subfolders = JCheckBox("When opening a folder, also open the files in its subfolders")

    init {
        val settings = AppSettings.current
        ask.isSelected = settings.openMode == null
        mode.selectedItem = settings.openMode ?: OpenMode.MERGE
        subfolders.isSelected = settings.includeSubfolders
        ask.addActionListener { enableDefaults() }
        enableDefaults()

        // The label starts where the checkboxes' boxes do, past their margin.
        modeLabel.border = BorderFactory.createEmptyBorder(0, ask.insets.left, 0, 0)
        layout = MigLayout("insets 0, wrap 2", "[][]", "[]para[][]")
        add(ask, "span 2")
        add(modeLabel)
        add(mode)
        add(subfolders, "span 2")
    }

    /** The defaults apply only without asking; the Open Files dialog asks for them otherwise. */
    private fun enableDefaults() {
        listOf(modeLabel, mode, subfolders).forEach { it.isEnabled = !ask.isSelected }
    }

    override fun applyTo(settings: Settings) = settings.copy(
        openMode = if (ask.isSelected) null else mode.selectedItem as OpenMode,
        includeSubfolders = ask.isSelected || subfolders.isSelected,
    )

    private fun label(mode: OpenMode) = when (mode) {
        OpenMode.MERGE_INTO_CURRENT -> "Merge into the current tab"
        OpenMode.MERGE_WITH_CURRENT_IN_NEW -> "Merge with the current tab into a new tab"
        OpenMode.MERGE -> "Merge into a new tab"
        OpenMode.TABS -> "Open each file in its own tab"
    }
}
