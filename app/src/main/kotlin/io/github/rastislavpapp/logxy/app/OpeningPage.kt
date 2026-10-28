package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.ui.Icons
import io.github.rastislavpapp.logxy.ui.Note
import io.github.rastislavpapp.logxy.ui.SettingsPage
import net.miginfocom.swing.MigLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.UIManager

/** Whether opening files asks how to open them, or always does the same; and whether closed sessions are remembered. */
class OpeningPage : SettingsPage("Opening Files", Icons.open) {

    private val ask = JCheckBox("Ask questions when opening files")
    private val modeLabel = JLabel("Open files:")
    private val mode = JComboBox(OpenMode.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component =
                super.getListCellRendererComponent(list, label(value as OpenMode), index, isSelected, cellHasFocus)
        }
    }
    private val subfolders = JCheckBox("When opening a folder, also open the files in its subfolders")
    private val rememberRecent = JCheckBox("Remember recent sessions")
    private val reopenOnStart = JCheckBox("Reopen the sessions open at exit on start")

    init {
        val settings = AppSettings.current
        ask.isSelected = settings.openMode == null
        mode.selectedItem = settings.openMode ?: OpenMode.MERGE
        subfolders.isSelected = settings.includeSubfolders
        rememberRecent.isSelected = settings.rememberRecent
        reopenOnStart.isSelected = settings.reopenOnStart
        ask.addActionListener { enableDefaults() }
        rememberRecent.addActionListener { enableReopen() }
        enableDefaults()
        enableReopen()

        // The label starts where the checkboxes' boxes do, past their margin.
        modeLabel.border = BorderFactory.createEmptyBorder(0, ask.insets.left, 0, 0)
        layout = MigLayout("insets 0, wrap 2", "[][grow]", "[]para[][]para[][][]")
        add(ask, "span 2")
        add(modeLabel)
        add(mode)
        add(subfolders, "span 2")
        add(rememberRecent, "span 2")
        val note = Note(
            "Lists closed sessions under File › Open Recent, with their parsers, filter, position and trace id " +
                "column, for Reopen Closed Tab too. Turning it off deletes the list.",
        )
        // Under the checkbox's text, past its box.
        val box = UIManager.getIcon("CheckBox.icon")?.iconWidth ?: 0
        note.border = BorderFactory.createEmptyBorder(0, ask.insets.left + box + ask.iconTextGap, 0, 0)
        add(note, "span 2, growx, wmin 0")
        add(reopenOnStart, "span 2")
    }

    /** The defaults apply only without asking; the Open Files dialog asks for them otherwise. */
    private fun enableDefaults() {
        listOf(modeLabel, mode, subfolders).forEach { it.isEnabled = !ask.isSelected }
    }

    /** Only remembered sessions can be reopened on start. */
    private fun enableReopen() {
        reopenOnStart.isEnabled = rememberRecent.isSelected
        if (!rememberRecent.isSelected) reopenOnStart.isSelected = false
    }

    override fun applyTo(settings: Settings) = settings.copy(
        openMode = if (ask.isSelected) null else mode.selectedItem as OpenMode,
        includeSubfolders = ask.isSelected || subfolders.isSelected,
        rememberRecent = rememberRecent.isSelected,
        // Cleared by enableReopen while recent sessions are not remembered.
        reopenOnStart = reopenOnStart.isSelected,
    )

    private fun label(mode: OpenMode) = when (mode) {
        OpenMode.MERGE_INTO_CURRENT -> "Merge into the current tab"
        OpenMode.MERGE_WITH_CURRENT_IN_NEW -> "Merge with the current tab into a new tab"
        OpenMode.MERGE -> "Merge into a new tab"
        OpenMode.TABS -> "Open each file in its own tab"
    }
}
