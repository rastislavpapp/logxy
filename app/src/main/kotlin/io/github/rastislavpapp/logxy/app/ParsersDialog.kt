package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.FormatPreview
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import io.github.rastislavpapp.logxy.core.parse.PreviewLine
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Window
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

private const val PREVIEW_LINES = 200

/**
 * Edits the saved parsers (formats), with a preview of [previewFile] read by the one being edited.
 * Returns the parser to use for the file if the user asked for that.
 */
class ParsersDialog(owner: Window?, private val previewFile: Path?, private val offerUse: Boolean, select: LogFormat?) :
    JDialog(owner, "Parsers", ModalityType.APPLICATION_MODAL) {

    /** Set when the dialog closed with "Save and Use for This File". */
    var formatToUse: LogFormat? = null
        private set

    private class Entry(val format: LogFormat, val builtIn: Boolean)

    private val entries = DefaultListModel<Entry>()
    private val list = JList(entries)
    private val nameField = JTextField(30)
    private val patternChoice = JRadioButton("log4j / logback pattern")
    private val regexChoice = JRadioButton("Regex with named groups")
    private val layoutField = JTextField(40)
    private val timestampField = JTextField(30)
    private val status = JLabel(" ")
    private val deleteButton = JButton("Delete").apply {
        toolTipText = "Built-in parsers cannot be deleted"
        addActionListener { deleteSelected() }
    }
    private val previewModel = PreviewModel()
    private val previewTable = JTable(previewModel)
    private val sample: List<ByteArray> = previewFile?.let {
        try {
            FormatPreview.sample(it, PREVIEW_LINES)
        } catch (_: Exception) {
            null
        }
    } ?: emptyList()
    private val previewTimer = Timer(250) { updatePreview() }.apply { isRepeats = false }
    private var loadingEditor = false


    init {
        AppSettings.current.formats.forEach { entries.addElement(Entry(it, builtIn = false)) }
        LogFormat.BUILT_IN.forEach { entries.addElement(Entry(it, builtIn = true)) }

        contentPane = JPanel(MigLayout("fill, insets dialog", "[grow]", "[grow][]")).apply {
            add(parsersPanel(), "grow, wrap")
            add(buttons(), "growx")
        }
        fitToScreen(0.75)
        WindowState.install(this, "parsers")

        val index = (0..<entries.size).firstOrNull { entries[it].format == select } ?: 0
        if (entries.size > 0) list.selectedIndex = index
    }

    private fun parsersPanel(): JPanel {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val entry = value as Entry
                super.getListCellRendererComponent(list, entry.format.name, index, isSelected, cellHasFocus)
                if (entry.builtIn) {
                    text = "${entry.format.name} (built-in)"
                    if (!isSelected) foreground = UIManager.getColor("Label.disabledForeground")
                }
                return this
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }

        ButtonGroup().apply {
            add(patternChoice)
            add(regexChoice)
        }
        val onChange = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = edited()
            override fun removeUpdate(e: DocumentEvent) = edited()
            override fun changedUpdate(e: DocumentEvent) = edited()
        }
        listOf(nameField, layoutField, timestampField).forEach { it.document.addDocumentListener(onChange) }
        patternChoice.addActionListener { edited() }
        regexChoice.addActionListener { edited() }

        val left = JPanel(MigLayout("fill, insets 0", "[grow]", "[grow][]")).apply {
            add(JScrollPane(list), "grow, wrap")
            add(JButton("New").apply { addActionListener { addFormat(newFormat()) } }, "split 3")
            add(JButton("Duplicate").apply { addActionListener { selected()?.let { addFormat(copyOf(it.format)) } } })
            add(deleteButton)
        }
        previewTable.apply {
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            setShowGrid(false)
            setDefaultRenderer(Any::class.java, PreviewRenderer())
        }
        val right = JPanel(MigLayout("fill, insets 0 8 0 0, wrap 2", "[][grow, fill]", "[][][][][][grow]")).apply {
            add(JLabel("Name:"))
            add(nameField)
            add(patternChoice, "skip, split 2")
            add(regexChoice)
            add(JLabel("Layout:"))
            add(layoutField)
            add(JLabel("Timestamp:"))
            add(timestampField)
            add(status, "span 2")
            add(JScrollPane(previewTable), "span 2, grow")
        }
        return JPanel(MigLayout("fill, insets 8", "[grow]", "[grow]")).apply {
            add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
                resizeWeight = 0.25
                border = null
            }, "grow")
        }
    }

    private fun buttons() = JPanel(MigLayout("insets 0", "[grow][][][]")).apply {
        add(JLabel("Saved in ${AppSettings.location}").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            toolTipText = AppSettings.location.toString()
        }, "wmin 0")
        if (offerUse) {
            add(JButton("Save and Use for This File").apply {
                addActionListener { if (save()) { formatToUse = selected()?.format; dispose() } }
            })
        }
        add(JButton("Save").apply {
            addActionListener { if (save()) dispose() }
            this@ParsersDialog.rootPane.defaultButton = this
        })
        add(JButton("Cancel").apply { addActionListener { dispose() } })
    }

    private fun selected(): Entry? = list.selectedValue

    private fun showSelected() {
        val entry = selected()
        loadingEditor = true
        try {
            val format = entry?.format
            nameField.text = format?.name.orEmpty()
            patternChoice.isSelected = format !is LogFormat.Regex
            regexChoice.isSelected = format is LogFormat.Regex
            layoutField.text = when (format) {
                is LogFormat.Pattern -> format.pattern
                is LogFormat.Regex -> format.regex
                else -> ""
            }
            timestampField.text = (format as? LogFormat.Regex)?.timestampPattern ?: Settings.DEFAULT_TIMESTAMP
            val editable = entry != null && !entry.builtIn
            deleteButton.isEnabled = editable
            deleteButton.toolTipText = if (entry?.builtIn == true) "Built-in parsers cannot be deleted" else null
            listOf(nameField, patternChoice, regexChoice, layoutField).forEach { it.isEnabled = editable }
            timestampField.isEnabled = editable && regexChoice.isSelected
        } finally {
            loadingEditor = false
        }
        updatePreview()
    }

    private fun edited() {
        if (loadingEditor) return
        timestampField.isEnabled = regexChoice.isSelected
        val index = list.selectedIndex
        val entry = selected() ?: return
        if (entry.builtIn) return
        entries.setElementAt(Entry(editedFormat(), builtIn = false), index)
        previewTimer.restart()
    }

    private fun editedFormat(): LogFormat = if (regexChoice.isSelected) {
        LogFormat.Regex(nameField.text.trim(), layoutField.text, timestampField.text)
    } else {
        LogFormat.Pattern(nameField.text.trim(), layoutField.text)
    }

    private fun updatePreview() {
        val format = selected()?.format
        val compiled = try {
            format?.compile()
        } catch (e: Exception) {
            status.text = e.message ?: e.toString()
            status.foreground = UIManager.getColor("Component.error.focusedBorderColor")
            previewModel.show(null, emptyList())
            return
        }
        status.foreground = UIManager.getColor("Label.foreground")
        if (compiled == null) {
            status.text = " "
            previewModel.show(null, emptyList())
            return
        }
        val lines = FormatPreview.preview(compiled, sample)
        previewModel.show(compiled.schema.fields.map { it.name }, lines)
        status.text = when {
            previewFile == null -> "Open a file to preview the parser on it."
            lines.isEmpty() -> "The file is empty."
            else -> {
                val matched = lines.count { it.values != null }
                val continued = lines.count { it.values == null && it.continuation }
                "$matched of ${lines.size} lines start an entry, $continued continue one, " +
                    "${lines.size - matched - continued} do not match."
            }
        }
    }

    private fun addFormat(format: LogFormat) {
        val userCount = (0..<entries.size).count { !entries[it].builtIn }
        entries.add(userCount, Entry(format, builtIn = false))
        list.selectedIndex = userCount
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun deleteSelected() {
        val entry = selected() ?: return
        if (entry.builtIn) return
        val index = list.selectedIndex
        entries.remove(index)
        if (entries.size > 0) list.selectedIndex = minOf(index, entries.size - 1)
    }

    private fun uniqueName(base: String): String {
        val names = (0..<entries.size).map { entries[it].format.name }.toSet()
        return (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base $it" }).first { it !in names }
    }

    private fun newFormat() = LogFormat.Pattern(uniqueName("My parser"), "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n")

    private fun copyOf(format: LogFormat): LogFormat = when (format) {
        is LogFormat.Pattern -> format.copy(name = uniqueName("${format.name} copy"))
        is LogFormat.Regex -> format.copy(name = uniqueName("${format.name} copy"))
        LogFormat.Raw -> newFormat()
    }

    /** Validates and saves; returns false (after telling the user) if something is wrong. */
    private fun save(): Boolean {
        val formats = (0..<entries.size).map { entries[it] }.filterNot { it.builtIn }.map { it.format }
        val problem = formats.firstNotNullOfOrNull { format ->
            when {
                format.name.isBlank() -> "A parser has no name."
                formats.count { it.name == format.name } > 1 || LogFormat.BUILT_IN.any { it.name == format.name } ->
                    "The name “${format.name}” is used more than once."
                else -> try {
                    format.compile()
                    null
                } catch (e: Exception) {
                    "“${format.name}”: ${e.message}"
                }
            }
        }
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Cannot Save", JOptionPane.ERROR_MESSAGE)
            return false
        }
        return try {
            AppSettings.update(AppSettings.current.copy(formats = formats))
            true
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Cannot write ${AppSettings.location}:\n${e.message}", "Cannot Save", JOptionPane.ERROR_MESSAGE)
            false
        }
    }

    /** Sample lines as the edited format reads them: one row per line, one column per field. */
    private inner class PreviewModel : AbstractTableModel() {
        private var columns: List<String> = emptyList()
        var lines: List<PreviewLine> = emptyList()
            private set

        fun show(fields: List<String>?, lines: List<PreviewLine>) {
            columns = fields ?: emptyList()
            this.lines = lines
            fireTableStructureChanged()
            // The table has recreated its columns; keep the ✓/↳/✗ one narrow.
            val width = previewTable.getFontMetrics(previewTable.font).stringWidth("✓") * 3
            previewTable.columnModel.getColumn(0).apply {
                minWidth = width
                maxWidth = width
            }
        }

        override fun getRowCount() = lines.size
        override fun getColumnCount() = 1 + maxOf(columns.size, 1)
        override fun getColumnName(column: Int) = if (column == 0) "" else columns.getOrElse(column - 1) { "Line" }

        override fun getValueAt(row: Int, column: Int): Any {
            val line = lines[row]
            if (column == 0) return if (line.values != null) "✓" else if (line.continuation) "↳" else "✗"
            val values = line.values ?: return if (column == 1) line.text else ""
            return values.getOrElse(column - 1) { "" }
        }
    }

    private inner class PreviewRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val line = previewModel.lines[row]
            if (!isSelected) {
                foreground = when {
                    line.values != null -> table.foreground
                    line.continuation -> UIManager.getColor("Label.disabledForeground")
                    else -> UIManager.getColor("Actions.Red")
                }
            }
            return this
        }
    }
}
