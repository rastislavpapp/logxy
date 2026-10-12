package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Window
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.UIManager

/** A section of the preferences; all sections are saved together. */
abstract class PreferencesPage(val title: String, val icon: Icon) : JPanel() {
    /** Why the page cannot be saved as it is, or null. */
    open fun problem(): String? = null

    /** [settings] with this page's values. */
    abstract fun applyTo(settings: Settings): Settings
}

/**
 * The app's settings, one section per page, opening on [section] (or the last one shown).
 * The parser being edited previews [previewFile]; [offerUse] adds a button that uses the selected parser for it.
 * [adopt] is added to the parsers as a new one.
 */
class PreferencesDialog(
    owner: Window?,
    section: Section? = null,
    previewFile: Path? = null,
    select: LogFormat? = null,
    offerUse: Boolean = false,
    adopt: LogFormat? = null,
) : JDialog(owner, "Preferences", ModalityType.APPLICATION_MODAL) {

    enum class Section { PARSERS, LEVELS, MEMORY, OPENING }

    /** Set when the dialog closed with "Save and Use for This File". */
    var formatToUse: LogFormat? = null
        private set

    private val parsers = ParsersPage(previewFile, select, adopt)
    private val pages = listOf(parsers, LevelsPage(), MemoryPage(), OpeningPage())
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val heading = JLabel().apply { putClientProperty("FlatLaf.styleClass", "h3") }
    private val navigation = JList(pages.toTypedArray())
    private var restartAfterClose = false

    init {
        pages.forEach { content.add(it, it.title) }
        navigation.selectionMode = ListSelectionModel.SINGLE_SELECTION
        navigation.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val page = value as PreferencesPage
                super.getListCellRendererComponent(list, page.title, index, isSelected, cellHasFocus)
                icon = page.icon
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                return this
            }
        }
        navigation.addListSelectionListener {
            val page = navigation.selectedValue ?: return@addListSelectionListener
            heading.text = page.title
            cards.show(content, page.title)
            AppState[AppState.PREFERENCES_SECTION] = Section.entries[navigation.selectedIndex].name
        }
        val shown = section ?: Section.entries.firstOrNull { it.name == AppState[AppState.PREFERENCES_SECTION] } ?: Section.PARSERS
        navigation.selectedIndex = shown.ordinal

        contentPane = JPanel(MigLayout("fill, insets dialog", "[][grow]", "[][grow][]")).apply {
            add(JScrollPane(navigation), "spany 2, growy, w 170!")
            add(heading, "wrap")
            add(content, "grow, wrap")
            add(buttons(offerUse), "span, growx")
        }
        fitToScreen(0.75)
        WindowState.install(this, "preferences")
    }

    private fun buttons(offerUse: Boolean) = JPanel(MigLayout("insets 0", "[grow][][][]")).apply {
        add(JLabel("Saved in ${AppSettings.location}").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            toolTipText = AppSettings.location.toString()
        }, "wmin 0")
        if (offerUse) {
            add(JButton("Save and Use for This File").apply {
                addActionListener { if (save()) { formatToUse = parsers.selectedFormat; close() } }
            })
        }
        add(JButton("Save").apply {
            addActionListener { if (save()) close() }
            this@PreferencesDialog.rootPane.defaultButton = this
        })
        add(JButton("Cancel").apply { addActionListener { dispose() } })
    }

    /** Validates and saves every page; returns false (after telling the user) if something is wrong. */
    private fun save(): Boolean {
        for (page in pages) {
            val problem = page.problem() ?: continue
            navigation.setSelectedValue(page, true)
            JOptionPane.showMessageDialog(this, problem, "Cannot Save", JOptionPane.ERROR_MESSAGE)
            return false
        }
        val before = AppSettings.current
        val settings = pages.fold(before) { settings, page -> page.applyTo(settings) }
        if (!AppSettings.updateOrShowError(this, settings)) return false
        if (settings.maxMemory != before.maxMemory) {
            val options = arrayOf("Restart Now", "Later")
            restartAfterClose = JOptionPane.showOptionDialog(
                this, "The new memory limit applies when logxy starts again. Restart now? Open tabs are reopened with their filters.", "Memory Limit",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0],
            ) == 0
        }
        return true
    }

    private fun close() {
        dispose()
        if (restartAfterClose) Restart.now(owner)
    }
}
