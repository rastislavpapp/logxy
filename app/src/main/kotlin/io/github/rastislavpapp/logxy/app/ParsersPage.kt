package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.ParserPreview
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.parse.PreviewLine
import io.github.rastislavpapp.logxy.core.parse.RoleKeys
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
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
 * Edits the saved parsers, with a preview of [previewFile] read by the one being edited.
 * [adopt], e.g. a detected parser, is added as a new one.
 */
class ParsersPage(
    private val previewFile: Path?,
    select: Parser?,
    adopt: Parser? = null,
) : PreferencesPage("Parsers", Icons.settings) {

    private class Entry(val parser: Parser, val builtIn: Boolean)

    /** A setting of a parser kind: the property it is stored as, and its value for a parser that has none. */
    private class Setting(val label: String, val key: String, val default: String, val list: Boolean = false) {
        val labelComponent = JLabel(label)
        val field = JTextField(if (key == "layout") 40 else 30)

        /** Shows the value of [properties], or the default. */
        fun show(properties: Map<String, String>) {
            val value = properties[key] ?: default
            field.text = if (list) value.split(',').filter { it.isNotBlank() }.joinToString(", ") else value
        }
    }

    /** A parser kind as the kind selector offers it, with the settings shown for it. */
    private class KindChoice(val label: String, val kind: Parser.Kind, val settings: List<Setting>) {
        fun parser(name: String): Parser? = settings.associate { it.key to it.field.text }.let { values -> kind.read(name) { values[it] } }

        override fun toString() = label
    }

    private val entries = DefaultListModel<Entry>()
    private val list = JList(entries)
    private val nameField = JTextField(30)
    private val choices = kindChoices()
    private val kindBox = JComboBox(choices.toTypedArray())
    private val status = JLabel(" ")
    private val deleteButton = JButton("Delete").apply { addActionListener { deleteSelected() } }
    private val restoreButton = JButton("Restore Built-ins").apply {
        toolTipText = "Brings back the deleted built-in parsers"
        addActionListener { restoreBuiltIns() }
    }
    private val previewModel = PreviewModel()
    private val previewTable = JTable(previewModel)
    private val sample: List<ByteArray> = previewFile?.let {
        try {
            ParserPreview.sample(it, PREVIEW_LINES)
        } catch (_: Exception) {
            null
        }
    } ?: emptyList()
    private val previewTimer = Timer(250) { updatePreview() }.apply { isRepeats = false }
    private var loadingEditor = false

    init {
        AppSettings.current.parsers.forEach { entries.addElement(Entry(it, builtIn = false)) }
        AppSettings.current.builtInParsers.forEach { entries.addElement(Entry(it, builtIn = true)) }
        updateRestoreButton()

        layout = MigLayout("fill, insets 0", "[grow]", "[grow]")
        add(editor(), "grow")

        val index = (0..<entries.size).firstOrNull { entries[it].parser == select } ?: 0
        if (entries.size > 0) list.selectedIndex = index
        adopt?.let { addParser(named(it, uniqueName(it.name))) }
    }

    /** The selected parser, if it is one the user may use. */
    val selectedParser: Parser? get() = selected()?.parser

    private fun editor(): JSplitPane {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val entry = value as Entry
                super.getListCellRendererComponent(list, entry.parser.name, index, isSelected, cellHasFocus)
                if (entry.builtIn) {
                    text = "${entry.parser.name} (built-in)"
                    if (!isSelected) foreground = UIManager.getColor("Label.disabledForeground")
                }
                return this
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }

        val onChange = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = edited()
            override fun removeUpdate(e: DocumentEvent) = edited()
            override fun changedUpdate(e: DocumentEvent) = edited()
        }
        (listOf(nameField) + settings().map { it.field }).forEach { it.document.addDocumentListener(onChange) }
        kindBox.addActionListener { kindChanged() }

        val left = JPanel(MigLayout("fill, insets 0", "[grow]", "[grow][]")).apply {
            add(JScrollPane(list), "grow, wrap")
            add(JButton("New").apply { addActionListener { addParser(newParser()) } }, "split 3")
            add(JButton("Duplicate").apply { addActionListener { selected()?.let { addParser(copyOf(it.parser)) } } })
            add(deleteButton, "wrap")
            add(restoreButton)
        }
        previewTable.apply {
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            setShowGrid(false)
            setDefaultRenderer(Any::class.java, PreviewRenderer())
        }
        val right = JPanel(MigLayout("fill, insets 0 8 0 0, wrap 2, hidemode 3", "[][grow, fill]")).apply {
            add(JLabel("Name:"))
            add(nameField)
            add(JLabel("Kind:"))
            add(kindBox, "growx 0")
            for (setting in settings()) {
                add(setting.labelComponent)
                add(setting.field)
            }
            add(status, "span 2")
            add(JScrollPane(previewTable), "span 2, grow, push")
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            resizeWeight = 0.25
            border = null
        }
    }

    private fun selected(): Entry? = list.selectedValue

    private fun showSelected() {
        val entry = selected()
        loadingEditor = true
        try {
            val parser = entry?.parser
            nameField.text = parser?.name.orEmpty()
            kindBox.selectedItem = choices.firstOrNull { it.kind == parser?.kind } ?: choices.first()
            val properties = parser?.properties().orEmpty()
            for (choice in choices) choice.settings.forEach { it.show(if (choice.kind == parser?.kind) properties else emptyMap()) }
            val editable = entry != null && !entry.builtIn
            deleteButton.isEnabled = entry != null
            (listOf(nameField, kindBox) + settings().map { it.field }).forEach { it.isEnabled = editable }
            showKindFields()
        } finally {
            loadingEditor = false
        }
        updatePreview()
    }

    private fun kindChanged() {
        if (loadingEditor) return
        // Carry over what the new kind shares with the one edited so far (the layout); role keys have defaults per kind.
        val properties = selected()?.parser?.properties().orEmpty()
        loadingEditor = true
        try {
            choice().settings.filter { it.key in properties && !it.list }.forEach { it.show(properties) }
        } finally {
            loadingEditor = false
        }
        edited()
    }

    private fun edited() {
        if (loadingEditor) return
        showKindFields()
        val index = list.selectedIndex
        val entry = selected() ?: return
        if (entry.builtIn) return
        val parser = choice().parser(nameField.text.trim()) ?: return
        entries.setElementAt(Entry(parser, builtIn = false), index)
        previewTimer.restart()
    }

    private fun choice() = kindBox.selectedItem as KindChoice

    private fun settings() = choices.flatMap { it.settings }

    /** Shows the settings of the chosen kind only. */
    private fun showKindFields() {
        for (choice in choices) {
            val shown = choice === choice()
            choice.settings.forEach {
                it.labelComponent.isVisible = shown
                it.field.isVisible = shown
            }
        }
    }

    private fun updatePreview() {
        val parser = selected()?.parser
        val compiled = try {
            parser?.compile()
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
        val lines = ParserPreview.preview(compiled, sample)
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

    private fun addParser(parser: Parser) {
        val userCount = (0..<entries.size).count { !entries[it].builtIn }
        entries.add(userCount, Entry(parser, builtIn = false))
        list.selectedIndex = userCount
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun deleteSelected() {
        selected() ?: return
        val index = list.selectedIndex
        entries.remove(index)
        if (entries.size > 0) list.selectedIndex = minOf(index, entries.size - 1) else showSelected()
        updateRestoreButton()
    }

    private fun builtIns() = (0..<entries.size).map { entries[it] }.filter { it.builtIn }.map { it.parser }

    private fun restoreBuiltIns() {
        val kept = builtIns()
        val selectedParser = selected()?.parser
        val firstBuiltIn = (0..<entries.size).count { !entries[it].builtIn }
        // Re-added in their original order, after the user's parsers.
        Parser.BUILT_IN.forEachIndexed { i, parser ->
            if (parser !in kept) entries.add(firstBuiltIn + i, Entry(parser, builtIn = true))
        }
        list.setSelectedValue((0..<entries.size).map { entries[it] }.firstOrNull { it.parser == selectedParser }, true)
        updateRestoreButton()
    }

    private fun updateRestoreButton() {
        restoreButton.isEnabled = builtIns().size < Parser.BUILT_IN.size
    }

    private fun uniqueName(base: String): String {
        val names = (0..<entries.size).map { entries[it].parser.name }.toSet()
        return (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base $it" }).first { it !in names }
    }

    private fun newParser() = Parser.Pattern(uniqueName("My parser"), "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n")

    private fun copyOf(parser: Parser): Parser = named(parser, uniqueName("${parser.name} copy"))

    private fun named(parser: Parser, name: String): Parser = if (parser == Parser.Raw) newParser() else parser.withName(name)

    private fun userParsers() = (0..<entries.size).map { entries[it] }.filterNot { it.builtIn }.map { it.parser }

    override fun problem(): String? {
        val parsers = userParsers()
        return parsers.firstNotNullOfOrNull { parser ->
            when {
                parser.name.isBlank() -> "A parser has no name."
                parsers.count { it.name == parser.name } > 1 || builtIns().any { it.name == parser.name } ->
                    "The name “${parser.name}” is used more than once."
                else -> try {
                    parser.compile()
                    null
                } catch (e: Exception) {
                    "“${parser.name}”: ${e.message}"
                }
            }
        }
    }

    override fun applyTo(settings: Settings): Settings {
        val kept = builtIns()
        return settings.copy(parsers = userParsers(), deletedBuiltIns = Parser.BUILT_IN.filter { it !in kept }.map { it.name }.toSet())
    }

    private companion object {
        /** The kinds a user can choose; each new kind of parser adds its line here. */
        fun kindChoices() = listOf(
            KindChoice("log4j / logback pattern", Parser.Pattern, listOf(Setting("Layout:", "layout", ""))),
            KindChoice(
                "Regex with named groups", Parser.Regex,
                listOf(Setting("Layout:", "layout", ""), Setting("Timestamp:", "timestamp", Parser.Regex.DEFAULT_TIMESTAMP)),
            ),
            KindChoice("JSON lines", Parser.Json, roleKeys(RoleKeys())),
            KindChoice("logfmt (key=value)", Parser.Logfmt, roleKeys(RoleKeys.LOGFMT)),
            KindChoice("Web server access log", Parser.AccessLog, emptyList()),
        )

        /** One setting per role, listing the keys that fill it. */
        fun roleKeys(defaults: RoleKeys) = defaults.properties("keys.").map { (key, value) ->
            Setting("${key.removePrefix("keys.").replaceFirstChar { it.uppercase() }} keys:", key, value, list = true)
        }
    }

    /** Sample lines as the edited parser reads them: one row per line, one column per field. */
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
