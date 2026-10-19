package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.util.ColorFunctions
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.config.VmOptions
import io.github.rastislavpapp.logxy.core.parse.Parser
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Window
import java.io.IOException
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
import javax.swing.JSeparator
import javax.swing.ListSelectionModel
import javax.swing.UIManager

private const val MIN_SIDEBAR_WIDTH = 180
private const val SIDEBAR_PADDING = 8
private val MIN_SIZE = Dimension(760, 520)

/** The panel background nudged away from itself, darker in a light theme and lighter in a dark one. */
private fun sidebarBackground(): Color {
    val panel = UIManager.getColor("Panel.background")
    return if (FlatLaf.isLafDark()) ColorFunctions.lighten(panel, 0.03f) else ColorFunctions.darken(panel, 0.03f)
}

/** A section of the preferences; all sections are saved together. */
abstract class PreferencesPage(val title: String, val icon: Icon) : JPanel() {
    /** Why the page cannot be saved as it is, or null. */
    open fun problem(): String? = null

    /** [settings] with this page's values. */
    abstract fun applyTo(settings: Settings): Settings

    /** Where this page's values are saved, for the dialog's footer. */
    open val savedIn: String get() = "Saved in ${AppSettings.location}"
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
    select: Parser? = null,
    offerUse: Boolean = false,
    adopt: Parser? = null,
) : JDialog(owner, "Preferences", ModalityType.APPLICATION_MODAL) {

    enum class Section { PARSERS, LEVELS, JVM, OPENING }

    /** Set when the dialog closed with "Save and Use for This File". */
    var parserToUse: Parser? = null
        private set

    private val parsers = ParsersPage(previewFile, select, adopt)
    private val jvm = JvmPage()
    private val pages = listOf(parsers, LevelsPage(), jvm, OpeningPage())
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val heading = JLabel().apply { putClientProperty("FlatLaf.styleClass", "h3") }
    private val navigation = JList(pages.toTypedArray())
    private val savedIn = JLabel().apply { foreground = UIManager.getColor("Label.disabledForeground") }
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
                return this
            }
        }
        // The list's own cell border paints the selection, so the pill's shape and the padding are FlatLaf styles.
        navigation.putClientProperty(
            "FlatLaf.style",
            "cellMargins: 5,10,5,10; selectionArc: 10; selectionInsets: 1,8,1,8",
        )
        navigation.background = sidebarBackground()
        navigation.addListSelectionListener {
            val page = navigation.selectedValue ?: return@addListSelectionListener
            heading.text = page.title
            cards.show(content, page.title)
            savedIn.text = page.savedIn
            savedIn.toolTipText = page.savedIn
            AppState[AppState.PREFERENCES_SECTION] = Section.entries[navigation.selectedIndex].name
        }
        val remembered = Section.entries.firstOrNull { it.name == AppState[AppState.PREFERENCES_SECTION] }
        val shown = section ?: remembered ?: Section.PARSERS
        navigation.selectedIndex = shown.ordinal

        val body = JPanel(MigLayout("fill, insets 16 20 16 16", "[grow]", "[]para[grow]")).apply {
            add(heading, "wrap")
            add(content, "grow")
        }
        val main = JPanel(MigLayout("fill, insets 0, gap 0", "[grow]", "[grow][][]")).apply {
            add(body, "grow, wrap")
            add(JSeparator(), "growx, wrap")
            add(buttons(offerUse), "growx")
        }
        contentPane = JPanel(MigLayout("fill, insets 0, gap 0", "[][grow]", "[grow]")).apply {
            add(sidebar(), "growy")
            add(main, "grow")
        }
        fitToScreen(0.75)
        minimumSize = MIN_SIZE
        WindowState.install(this, "preferences")
        // A size remembered from an older, looser layout may be below the minimum.
        size = Dimension(maxOf(width, MIN_SIZE.width), maxOf(height, MIN_SIZE.height))
    }

    /** The page list, flush with the dialog's edges; a plain panel, so that focusing the list draws no border. */
    private fun sidebar(): JPanel {
        // The list is as wide as its longest title needs; the scroll pane only adds a scroll bar if the height fails.
        val width = maxOf(MIN_SIDEBAR_WIDTH, navigation.preferredSize.width + 2 * SIDEBAR_PADDING)
        return JPanel(MigLayout("fill, insets $SIDEBAR_PADDING 0 $SIDEBAR_PADDING 0", "[grow]", "[grow]")).apply {
            background = sidebarBackground()
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Component.borderColor"))
            val scroll = JScrollPane(navigation).apply {
                border = null
                viewport.isOpaque = false
            }
            add(scroll, "grow, w $width!")
        }
    }

    private fun buttons(offerUse: Boolean) = JPanel(MigLayout("insets 10 16 10 16", "[grow][][][]")).apply {
        add(savedIn, "wmin 0")
        if (offerUse) {
            add(JButton("Save and Use for This File").apply {
                addActionListener {
                    if (save()) {
                        parserToUse = parsers.selectedParser
                        close()
                    }
                }
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
        val settings = pages.fold(AppSettings.current) { settings, page -> page.applyTo(settings) }
        if (!AppSettings.updateOrShowError(this, settings)) return false
        return saveJvmOptions()
    }

    /** Saves the JVM page's options if they changed, offering a restart to apply them; false if they cannot be. */
    private fun saveJvmOptions(): Boolean {
        val vmOptions = Restart.vmOptions ?: return true
        val options = jvm.options()
        if (options == null || options == vmOptions.current()) return true
        val installed = try {
            vmOptions.save(options)
        } catch (e: IOException) {
            val message = "Cannot write ${vmOptions.user}:\n${e.message}"
            JOptionPane.showMessageDialog(this, message, "Cannot Save", JOptionPane.ERROR_MESSAGE)
            return false
        }
        if (installed == VmOptions.Install.FAILED) {
            val message = "logxy cannot write ${vmOptions.installed}, so the changed JVM options do not apply. " +
                "Move logxy to a folder you can write to."
            JOptionPane.showMessageDialog(this, message, "JVM Options", JOptionPane.WARNING_MESSAGE)
            return true
        }
        val choices = arrayOf("Restart Now", "Later")
        restartAfterClose = JOptionPane.showOptionDialog(
            this,
            "The changed JVM options apply when logxy starts again. Restart now? " +
                "Open tabs are reopened with their parsers and filters.",
            "JVM Options",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0],
        ) == 0
        return true
    }

    private fun close() {
        dispose()
        if (restartAfterClose) Restart.now(owner)
    }
}
