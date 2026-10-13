package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import net.miginfocom.swing.MigLayout
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.KeyStroke
import javax.swing.UIManager

/** How the user chose to open files: [formats] has the parser for each of [files], null to detect it. */
class OpenAnswer(
    val mode: OpenMode,
    val files: List<Path>,
    val formats: List<LogFormat?>,
    val includeSubfolders: Boolean,
    /** Open files this way from now on, without asking. */
    val remember: Boolean,
)

/**
 * Asks how to open [files] ([names] as the merge's source column would show them, [descriptions] in
 * tooltips): merged, with the [current] tab's files if there is one, or in tabs, and with which parser
 * each. Files [inSubfolders] of an opened folder can be left out. [answer] is null if cancelled.
 */
class OpenFilesDialog(
    owner: Window,
    private val files: List<Path>,
    private val names: List<String>,
    private val descriptions: List<String>,
    private val inSubfolders: Set<Path>,
    private val current: List<Path>?,
) : JDialog(owner, "Open Files", ModalityType.APPLICATION_MODAL) {

    var answer: OpenAnswer? = null
        private set

    private val heading = JLabel().apply { putClientProperty("FlatLaf.styleClass", "h3") }
    private val parsers: FileParsersTable = FileParsersTable(files, names, descriptions, excludable = true) { update() }
    private val subfolders = JCheckBox("Include subfolders", true)
    private val remember = JCheckBox("Always open files this way")
    private val openButton = JButton("Open")

    // All choices this context can offer, always shown: those that do not fit the files are disabled, so the dialog keeps its size.
    private val choices = OpenMode.entries.filter { current != null || (it != OpenMode.MERGE_INTO_CURRENT && it != OpenMode.MERGE_WITH_CURRENT_IN_NEW) }
    private val radios = choices.map { JRadioButton() }

    // Only what the user clicked; a choice that was the only one left was not picked.
    private var picked: OpenMode? = null

    init {
        val group = ButtonGroup()
        radios.forEachIndexed { i, radio ->
            group.add(radio)
            radio.addActionListener { picked = choices[i] }
        }
        subfolders.addActionListener { parsers.shown = if (subfolders.isSelected) files else files.filter { it !in inSubfolders } }
        val hint = JLabel("To ask again, see File › Preferences › Opening Files.").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        contentPane = JPanel(MigLayout("fill, insets dialog, wrap 1, hidemode 3", "[grow, fill]", "[][grow][][][][][][]")).apply {
            add(heading)
            add(parsers.component, "grow")
            add(JPanel(MigLayout("insets 0, wrap 1, gapy 2")).apply { radios.forEach(::add) }, "gaptop para")
            if (inSubfolders.isNotEmpty()) add(subfolders)
            add(remember, "gaptop para")
            add(hint, "gapleft indent")
            add(dialogButtons(openButton, JButton("Cancel").apply { addActionListener { dispose() } }), "gaptop para")
        }
        update()
        openButton.addActionListener { open() }
        rootPane.defaultButton = openButton
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        pack()
        // So restored bounds from a smaller saved size cannot cut off what is under the list.
        minimumSize = minimumSize
        setLocationRelativeTo(owner)
        WindowState.install(this, "open-files")
    }

    override fun dispose() {
        parsers.dispose()
        super.dispose()
    }

    /**
     * Follows the files to open (subfolders, exclusions, parsers): the heading, the choices that fit how many there
     * are, opening only files that fit their parsers, and remembering the answer only while every parser is detected
     * (chosen parsers are not remembered, though the user might expect it).
     */
    private fun update() {
        val files = parsers.included
        heading.text = when (files.size) {
            0 -> "No files to open"
            1 -> "Open ${names[this.files.indexOf(files.single())]}"
            else -> "Open ${files.size} files"
        }
        val possible = modesFor(files.size, current?.size)
        radios.forEachIndexed { i, radio ->
            radio.text = label(choices[i], files.size, current != null)
            radio.isEnabled = choices[i] in possible
        }
        val selected = picked?.takeIf { it in possible } ?: choices.firstOrNull { it in possible }
        radios.forEachIndexed { i, radio -> radio.isSelected = choices[i] == selected }
        openButton.isEnabled = parsers.allFit
        openButton.toolTipText = parsers.notReady
        val detected = parsers.formats.all { it == null }
        remember.isEnabled = detected
        if (!detected) remember.isSelected = false
        remember.toolTipText = if (detected) null else "Only when every parser is Auto-detect: chosen parsers are not remembered"
    }

    private fun open() {
        parsers.stopEditing()
        val mode = choices.getOrNull(radios.indexOfFirst { it.isSelected && it.isEnabled }) ?: return
        answer = OpenAnswer(mode, parsers.included, parsers.formats, subfolders.isSelected, remember.isSelected)
        dispose()
    }

    companion object {
        /** What [count] files can do: merge with a current tab of [currentCount] files, merge on their own, or open in tabs. */
        fun modesFor(count: Int, currentCount: Int?): List<OpenMode> = buildList {
            if (count == 0) return@buildList
            if (currentCount != null && currentCount + count <= MergedLog.MAX_PARTS) {
                add(OpenMode.MERGE_INTO_CURRENT)
                add(OpenMode.MERGE_WITH_CURRENT_IN_NEW)
            }
            if (count in 2..MergedLog.MAX_PARTS) add(OpenMode.MERGE)
            add(OpenMode.TABS)
        }

        fun label(mode: OpenMode, count: Int, hasCurrent: Boolean): String = when (mode) {
            OpenMode.MERGE_INTO_CURRENT -> "Merge into the current tab"
            OpenMode.MERGE_WITH_CURRENT_IN_NEW -> "Merge with the current tab into a new tab"
            OpenMode.MERGE -> if (hasCurrent) "Merge only these files into a new tab" else "Merge into a new tab"
            OpenMode.TABS -> if (count > 1) "Open each in its own tab" else "Open in a new tab"
        }
    }
}

/** OK and Cancel style buttons at the right, the same width, never narrower than their text whatever the font. */
fun dialogButtons(vararg buttons: JButton) = JPanel(MigLayout("insets 0, fillx", "push" + "[]".repeat(buttons.size))).apply {
    buttons.forEach { add(it, "sg button, wmin pref") }
}
