package io.github.rastislavpapp.logxy.plugin

import com.intellij.find.FindModel
import com.intellij.find.SearchReplaceComponent
import com.intellij.find.SearchSession
import com.intellij.find.editorHeaderActions.NextOccurrenceAction
import com.intellij.find.editorHeaderActions.PrevOccurrenceAction
import com.intellij.find.editorHeaderActions.StatusTextAction
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.find.editorHeaderActions.ToggleRegex
import com.intellij.find.editorHeaderActions.ToggleWholeWordsOnlyAction
import com.intellij.openapi.project.Project
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.ui.FindView
import io.github.rastislavpapp.logxy.ui.findCountText
import javax.swing.JComponent
import javax.swing.Timer

/** Typing waits this long for the next key before searching, as in the app's find bar. */
private const val TYPING_DELAY_MILLIS = 150

/**
 * The IDE's search bar, as an editor's Ctrl+F shows it, above the rows of [tab]; it searches with logxy's search,
 * which finds records rather than places in a text. The options are the editor's: case, words and regex.
 */
class IdeFindView(
    private val project: Project,
    tab: JComponent,
    private val onSearch: (TextSearch?) -> Unit,
    private val onFind: (forward: Boolean) -> Unit,
) : FindView {

    private val model = FindModel()

    /** What the bar's own actions (previous, next, the options, close) act on. */
    private val session = object : SearchSession {
        override fun getFindModel() = model

        override fun getComponent() = bar

        override fun hasMatches() = mayMatch

        override fun isSearchInProgress() = searching

        override fun searchForward() = find(forward = true)

        override fun searchBackward() = find(forward = false)

        override fun close() = this@IdeFindView.close()
    }
    private val bar: SearchReplaceComponent = SearchReplaceComponent.buildFor(project, tab, session)
        .addPrimarySearchActions(StatusTextAction(), PrevOccurrenceAction(), NextOccurrenceAction())
        .addExtraSearchActions(ToggleMatchCase(), ToggleWholeWordsOnlyAction(), ToggleRegex())
        .withCloseAction { close() }
        // Records are found by one line of text.
        .withMultilineEnabled(false)
        .withNewLineButton(false)
        .build()
    private val typing = Timer(TYPING_DELAY_MILLIS) { searchNow() }.apply { isRepeats = false }

    /** Whether the last count was still searching, or found some; the previous and next buttons go by it. */
    private var mayMatch = false
    private var searching = false

    init {
        bar.isVisible = false
        bar.addListener(object : SearchReplaceComponent.Listener {
            override fun searchFieldDocumentChanged() = typing.restart()
        })
        // The options' toggles change the model.
        model.addObserver { searchNow() }
    }

    private val text: String get() = bar.searchTextComponent.text

    private val search: TextSearch?
        get() {
            if (!isOpen || text.isEmpty()) return null
            return TextSearch(text, model.isCaseSensitive, model.isWholeWordsOnly, model.isRegularExpressions)
        }

    private fun searchNow() {
        typing.stop()
        onSearch(search)
    }

    override val component: JComponent get() = bar

    override val floats get() = false

    override val isOpen get() = bar.isVisible

    override fun open(text: String?) {
        val wasOpen = isOpen
        showBar(true)
        if (text != null && text != this.text) {
            bar.searchTextComponent.text = text
            searchNow()
        } else if (!wasOpen) {
            searchNow()
        }
        bar.requestFocusInTheSearchFieldAndSelectContent(project)
    }

    override fun showCount(current: Int, total: Int?, progress: Double?) {
        searching = total == null || progress != null
        mayMatch = searching || total != 0
        bar.searchTextComponent.toolTipText = null
        bar.statusText = findCountText(text, current, total, progress)
        if (total == 0 && !searching && text.isNotEmpty()) bar.setNotFoundBackground() else bar.setRegularBackground()
        bar.updateActions()
    }

    override fun showError(message: String?) {
        if (message == null) return
        mayMatch = false
        searching = false
        bar.searchTextComponent.toolTipText = message
        bar.statusText = "Invalid regex"
        bar.setNotFoundBackground()
        bar.updateActions()
    }

    private fun showBar(shown: Boolean) {
        bar.isVisible = shown
        bar.parent?.revalidate()
        bar.parent?.repaint()
    }

    // A search still waiting for the typing to pause starts at once, so the jump finds the text typed.
    private fun find(forward: Boolean) {
        if (typing.isRunning) searchNow()
        onFind(forward)
    }

    // Closed, the search ends, and the tab puts the focus back on the rows.
    private fun close() {
        if (!isOpen) return
        showBar(false)
        searchNow()
    }
}
