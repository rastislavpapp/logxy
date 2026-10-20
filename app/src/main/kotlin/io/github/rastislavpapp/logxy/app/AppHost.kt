package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.ui.DetailPane
import io.github.rastislavpapp.logxy.ui.LogViewHost
import io.github.rastislavpapp.logxy.ui.QueryText
import io.github.rastislavpapp.logxy.ui.SettingsDialogs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import java.awt.Window
import java.nio.file.Path

/** The app's side of the views: its settings files, the Preferences dialog and the RSyntaxTextArea editors. */
object AppHost : LogViewHost, SettingsDialogs {

    override val settings get() = AppSettings

    override val state get() = AppState

    override val uiDispatcher: CoroutineDispatcher get() = Dispatchers.Swing

    override fun detailPane(): DetailPane = LogDetailArea()

    override fun queryText(onApply: () -> Unit): QueryText = QueryTextArea(onApply)

    override val settingsDialogs: SettingsDialogs get() = this

    override fun editParsers(owner: Window?, file: Path, select: Parser, offerUse: Boolean, adopt: Parser?): Parser? {
        val section = PreferencesDialog.Section.PARSERS
        val dialog = PreferencesDialog(owner, section, file, select, offerUse = offerUse, adopt = adopt)
        dialog.isVisible = true
        return dialog.parserToUse
    }

    override fun editLevelMapping(owner: Window?) {
        PreferencesDialog(owner, PreferencesDialog.Section.LEVELS).isVisible = true
    }

    override fun chooseParsersPerFile(
        owner: Window?,
        paths: List<Path>,
        names: List<String>,
        descriptions: List<String>,
        current: List<Parser?>,
    ): List<Parser?>? {
        val dialog = FileParsersDialog(owner, paths, names, descriptions, current)
        dialog.isVisible = true
        return dialog.chosenParsers
    }

    override val moreMemoryHint get() = "Give logxy more memory in Preferences › Memory to copy more."
}
