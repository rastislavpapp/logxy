package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import io.github.rastislavpapp.logxy.core.query.TextSearch
import net.miginfocom.swing.MigLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.text.NumberFormat
import javax.swing.AbstractAction
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Typing waits this long for the next key before searching. */
private const val TYPING_DELAY_MILLIS = 150

private const val ARC = 10

/**
 * Finds text in the records without filtering them, like a text editor's find box; floats over the table.
 * [onSearch] is called when what to find changes (null once the box is closed), [onFind] to go to the next
 * match, or the previous. How many records match shows under the text once there is any.
 */
class FindBar(
    private val onSearch: (TextSearch?) -> Unit,
    private val onFind: (forward: Boolean) -> Unit,
) : JPanel(MigLayout("insets 6 8 6 6, gap 2 2, hidemode 3, wrap 4", "[300!, fill]4[][][]")) {

    private val matchCase = fieldToggle("inline/matchCase", "Match case")
    private val wholeWords = fieldToggle("inline/exactWords", "Words")
    private val regex = fieldToggle("inline/regex", "Regex")

    private val input = JTextField().apply {
        putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Find in records")
        putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("general/search"))
        withToggles(matchCase, wholeWords, regex)
        limitPaste()
    }
    private val count = JLabel()
    private val typing = Timer(TYPING_DELAY_MILLIS) { onSearch(search) }.apply { isRepeats = false }

    /** What to find; null while the bar is hidden or empty. */
    val search: TextSearch?
        get() {
            if (!isVisible || input.text.isEmpty()) return null
            return TextSearch(input.text, matchCase.isSelected, wholeWords.isSelected, regex.isSelected)
        }

    init {
        isOpaque = false
        isVisible = false
        count.isVisible = false
        add(input)
        add(toolBarButton(Icons.get("general/chevronUp"), "Previous match (Shift+Enter, Shift+F3)") {
            find(forward = false)
        })
        add(toolBarButton(Icons.get("general/chevronDown"), "Next match (Enter, F3)") { find(forward = true) })
        add(toolBarButton(Icons.closeSmall, "Close (Esc)") { close() })
        add(count, "span, gapleft 4")
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = typing.restart()
            override fun removeUpdate(e: DocumentEvent) = typing.restart()
            override fun changedUpdate(e: DocumentEvent) = typing.restart()
        })
        for (toggle in listOf(matchCase, wholeWords, regex)) toggle.addActionListener { searchNow() }
        bind("ENTER", "logxyFindNext") { find(forward = true) }
        bind("shift ENTER", "logxyFindPrevious") { find(forward = false) }
        bind("ESCAPE", "logxyCloseFind") { close() }
    }

    // Rounded, so it reads as a box floating over the rows.
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIManager.getColor("Panel.background")
            g2.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
            g2.color = UIManager.getColor("Component.borderColor")
            g2.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
        } finally {
            g2.dispose()
        }
    }

    private fun bind(key: String, name: String, action: () -> Unit) {
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
        input.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    private fun searchNow() {
        typing.stop()
        onSearch(search)
    }

    // A search still waiting for the typing to pause starts at once, so the jump finds the text typed.
    private fun find(forward: Boolean) {
        if (typing.isRunning) searchNow()
        onFind(forward)
    }

    /** Shows the bar and selects its text for typing over; with [text], looks for that instead. */
    fun open(text: String? = null) {
        val wasVisible = isVisible
        isVisible = true
        if (text != null && text != input.text) {
            input.text = text
            searchNow()
        } else if (!wasVisible) {
            searchNow()
        }
        input.selectAll()
        input.requestFocusInWindow()
    }

    fun close() {
        if (!isVisible) return
        isVisible = false
        searchNow()
    }

    /**
     * Shows how many records match: [total], null while searching (then [progress], from 0 to 1, if known);
     * [current] is the position among them of the selected record, from 1, or 0 if it is none.
     */
    fun showCount(current: Int, total: Int?, progress: Double? = null) {
        showError(null)
        val numbers = NumberFormat.getIntegerInstance()
        count.text = when {
            input.text.isEmpty() -> ""
            total == null && progress != null && progress > 0 -> "Searching ${(progress * 100).toInt()} %"
            total == null -> "Searching…"
            total == 0 -> "No matches"
            current > 0 -> "${numbers.format(current)} of ${numbers.format(total)}"
            total == 1 -> "1 match"
            else -> "${numbers.format(total)} matches"
        }
        count.foreground = UIManager.getColor("Label.foreground")
        showCountLine()
    }

    private fun showCountLine() {
        if (count.isVisible == count.text.isNotEmpty()) return
        count.isVisible = count.text.isNotEmpty()
        revalidate()
        repaint()
    }

    /** Marks the text as wrong, e.g. an invalid regex, or shows it is fine again if [message] is null. */
    fun showError(message: String?) {
        input.putClientProperty(FlatClientProperties.OUTLINE, message?.let { FlatClientProperties.OUTLINE_ERROR })
        input.toolTipText = message
        if (message != null) {
            count.text = "Invalid regex"
            count.foreground = UIManager.getColor("Component.error.focusedBorderColor")
            showCountLine()
        }
    }
}
