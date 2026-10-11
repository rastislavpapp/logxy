package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.OpenMode
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

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

    private val parsers: List<LogFormat?> = listOf(null, LogFormat.Raw) + AppSettings.current.formats + LogFormat.BUILT_IN
    private val chosen = HashMap<Path, LogFormat?>()
    private val heading = JLabel().apply { putClientProperty("FlatLaf.styleClass", "h3") }
    private val model = FilesModel()
    private val table = JTable(model)
    /** Painted in the parser column's header; a click there sets the parser of all files. */
    private val headerCombo = JComboBox<String>()
    private val modes = JPanel(MigLayout("insets 0, wrap 1, gapy 2"))
    private val subfolders = JCheckBox("Include subfolders", true)
    private val remember = JCheckBox("Always open files this way")
    private val openButton = JButton("Open")
    private var choices = emptyList<OpenMode>()
    private var radios = emptyList<JRadioButton>()

    // Only what the user clicked; a choice that was the only one left was not picked.
    private var picked: OpenMode? = null

    /** The files to open, as far as the subfolders checkbox lets them. */
    private var shown = files

    init {
        table.apply {
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
            fillsViewportHeight = true
            columnModel.getColumn(1).cellEditor = DefaultCellEditor(JComboBox(parsers.toTypedArray()).apply { renderer = ParserRenderer() })
                .apply { clickCountToStart = 1 }
            columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) {
                    text = parserName(value as LogFormat?) + "  ⌄"
                }
            }
            columnModel.getColumn(1).preferredWidth = 240
            columnModel.getColumn(0).preferredWidth = 360
            columnModel.getColumn(1).headerRenderer = ParserHeader()
            columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
                ): Component {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    toolTipText = descriptions[files.indexOf(shown[row])]
                    return this
                }
            }
        }
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (table.tableHeader.columnAtPoint(e.point) == 1 && files.size > 1) chooseForAll()
            }
        })
        subfolders.addActionListener { update() }

        val hint = JLabel("To ask again, see File › Preferences › Opening Files.").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        contentPane = JPanel(MigLayout("fill, insets dialog, wrap 1, hidemode 3", "[grow, fill]", "[][grow][][][][][][]")).apply {
            add(heading)
            add(JScrollPane(table).apply { preferredSize = Dimension(620, tableHeight()) }, "grow")
            add(modes, "gaptop para")
            if (inSubfolders.isNotEmpty()) add(subfolders)
            add(remember, "gaptop para")
            add(hint, "gapleft indent")
            // Buttons never narrower than their text, whatever the font.
            add(JPanel(MigLayout("insets 0, fillx", "push[][]")).apply {
                add(openButton, "sg button, wmin pref")
                add(JButton("Cancel").apply { addActionListener { dispose() } }, "sg button, wmin pref")
            }, "gaptop para")
        }
        update()
        openButton.addActionListener { open() }
        rootPane.defaultButton = openButton
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        pack()
        setLocationRelativeTo(owner)
    }

    /** Sets the parser of all files listed. */
    private fun chooseForAll() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val listed = listedParsers()
        val items = parsers.map { format ->
            PopupItem(parserName(format), selected = listed.singleOrNull() == format && listed.size == 1) {
                shown.forEach { chosen[it] = format }
                parsersChanged()
            }
        }
        val header = table.tableHeader
        showPopupList(header, items, header.getHeaderRect(1).x)
    }

    /** The parsers of the listed files, each once; null is Auto-detect. */
    private fun listedParsers(): List<LogFormat?> = shown.map { chosen[it] }.distinct()

    /**
     * After a parser changed: repaints, and offers to remember the answer only while every parser is detected,
     * as chosen parsers are not remembered and the user might expect them to be.
     */
    private fun parsersChanged() {
        model.fireTableDataChanged()
        table.tableHeader.repaint()
        val detected = shown.all { chosen[it] == null }
        remember.isEnabled = detected
        if (!detected) remember.isSelected = false
        remember.toolTipText = if (detected) null else "Only when every parser is Auto-detect: chosen parsers are not remembered"
    }

    private fun tableHeight() = table.tableHeader.preferredSize.height + table.rowHeight * files.size.coerceIn(3, 12) + 4

    /** Follows the subfolders checkbox: the files listed, the heading, and the choices, which depend on how many files there are. */
    private fun update() {
        shown = if (subfolders.isSelected) files else files.filter { it !in inSubfolders }
        model.fireTableDataChanged()
        heading.text = when (shown.size) {
            0 -> "No files outside subfolders"
            1 -> "Open ${names[files.indexOf(shown.single())]}"
            else -> "Open ${shown.size} files"
        }
        choices = modesFor(shown.size, current?.size)
        val group = ButtonGroup()
        radios = choices.map { mode ->
            JRadioButton(label(mode, shown.size, current != null), mode == picked).also { radio ->
                group.add(radio)
                radio.addActionListener { picked = mode }
            }
        }
        if (radios.none { it.isSelected }) radios.firstOrNull()?.isSelected = true
        modes.removeAll()
        radios.forEach(modes::add)
        openButton.isEnabled = shown.isNotEmpty()
        parsersChanged()
        if (isVisible) pack()
    }

    private fun open() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val mode = choices.getOrNull(radios.indexOfFirst { it.isSelected }) ?: return
        answer = OpenAnswer(mode, shown, shown.map { chosen[it] }, subfolders.isSelected, remember.isSelected)
        dispose()
    }

    private inner class FilesModel : AbstractTableModel() {
        override fun getRowCount() = shown.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "File" else "Parser"
        override fun isCellEditable(row: Int, column: Int) = column == 1

        override fun getValueAt(row: Int, column: Int): Any? {
            val file = shown[row]
            return if (column == 0) names[files.indexOf(file)] else chosen[file]
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            chosen[shown[row]] = value as LogFormat?
            // Later: setValueAt runs while the editor is still being removed.
            SwingUtilities.invokeLater { parsersChanged() }
        }
    }

    /** "Parser" and a combo showing the parser of all files (or "Mixed"), which a click on the header opens. */
    private inner class ParserHeader : TableCellRenderer {
        private val label = JLabel("Parser")
        private val panel = JPanel(MigLayout("insets 2 6 2 4, gap 8", "[][grow, fill]")).apply {
            add(label)
            add(headerCombo)
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val listed = listedParsers()
            val text = if (listed.size == 1) parserName(listed.single()) else "Mixed"
            headerCombo.model = DefaultComboBoxModel(arrayOf(text))
            headerCombo.isVisible = files.size > 1
            panel.background = table.tableHeader.background
            return panel
        }
    }

    private class ParserRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
            super.getListCellRendererComponent(list, parserName(value as LogFormat?), index, isSelected, cellHasFocus)
    }

    companion object {
        private fun parserName(format: LogFormat?) = format?.name ?: "Auto-detect"

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
