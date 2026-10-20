package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.query.Highlighter
import io.github.rastislavpapp.logxy.core.query.TextSearch
import io.github.rastislavpapp.logxy.core.session.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import java.awt.Component
import java.awt.Window
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/**
 * What a [LogTab] takes from the program it runs in, the app or the IDE plugin: where settings and UI state are kept,
 * the UI thread's dispatcher, and the parts only the app has.
 */
interface LogViewHost {

    val settings: WritableSettings

    val state: UiState

    /** Runs coroutines on the UI thread; the views' work starts there. */
    val uiDispatcher: CoroutineDispatcher

    /** A fresh pane for the selected record's whole text. */
    fun detailPane(): DetailPane

    /**
     * A fresh editor for the filter's text form (Advanced), applying with [onApply]; null offers only the visual
     * editor.
     */
    fun queryText(onApply: () -> Unit): QueryText?

    /**
     * A fresh view for finding text in the records of [tab], which reports what to find with [onSearch] (null once
     * closed) and asks to go to the next match, or the previous, with [onFind].
     */
    fun findView(tab: JComponent, onSearch: (TextSearch?) -> Unit, onFind: (forward: Boolean) -> Unit): FindView =
        FindBar(onSearch, onFind)

    /** Opens the parser and level settings from the views' menus; null if the host has no such dialogs. */
    val settingsDialogs: SettingsDialogs?

    /**
     * Asks for the parser of each of a merge's [paths], shown by their [names] and [descriptions] and starting from
     * [current]; null if cancelled.
     */
    fun chooseParsersPerFile(
        owner: Window?,
        paths: List<Path>,
        names: List<String>,
        descriptions: List<String>,
        current: List<Parser?>,
    ): List<Parser?>?

    /** How to raise the limit on copying, appended to a refused copy's message; null if the user cannot. */
    val moreMemoryHint: String?
}

/** The settings in use, saved where the host keeps them. Used on the UI thread only. */
interface WritableSettings : SettingsStore {

    /** Saves [settings] and tells the listeners; on failure tells the user (over [parent]) and returns false. */
    fun updateOrShowError(parent: Component, settings: Settings): Boolean
}

/** Small UI state remembered between runs, such as where a divider was dragged; null values remove a key. */
interface UiState {

    operator fun get(key: String): String?

    operator fun set(key: String, value: String?)

    companion object {
        const val DETAIL_DIVIDER = "detail.divider"
        const val FILTER_MODE = "filter.mode"
    }
}

/** The host's dialogs for the settings the views offer to edit. */
interface SettingsDialogs {

    /**
     * Opens the parser settings for [file] with [select] selected, or [adopt] added as a new parser to save; with
     * [offerUse] the dialog can pick the parser for the file. Returns the parser picked, or null.
     */
    fun editParsers(owner: Window?, file: Path, select: Parser, offerUse: Boolean, adopt: Parser?): Parser?

    fun editLevelMapping(owner: Window?)
}

/** Shows the selected record's whole text, read-only, with what the filter and the find bar matched marked. */
interface DetailPane {

    val component: JTextComponent

    /** Shows [text], with the marks of [matches] and [found]. */
    fun show(text: String)

    /** What the filter matched. */
    var matches: Highlighter

    /** What the find bar found. */
    var found: Highlighter

    /** Where [found] first occurs in the shown text; 0 if it does not. */
    val firstFound: Int
}

/** The text form of a filter, as typed; [component] goes into a scroll pane. */
interface QueryText {

    val component: JTextComponent

    /** Field names to offer while typing, e.g. for completion. */
    fun setFields(fields: List<String>)

    /** Lets go of what would outlive the editor, e.g. listeners on app-wide changes. */
    fun release()
}
