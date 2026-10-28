package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.ui.DetailPane
import io.github.rastislavpapp.logxy.ui.ExportFile
import io.github.rastislavpapp.logxy.ui.ExportFiles
import io.github.rastislavpapp.logxy.ui.LogViewHost
import io.github.rastislavpapp.logxy.ui.QueryText
import io.github.rastislavpapp.logxy.ui.SettingsDialogs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Window
import java.io.File
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton

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

    override val exportFiles: ExportFiles get() = ExportDialog
}

/** The app's save dialog for exports: a file chooser with the choice of records below the files, if there is one. */
private object ExportDialog : ExportFiles {

    override fun choose(owner: Window?, name: String, choices: List<String>): ExportFile? {
        val buttons = choices.map { JRadioButton(it) }
        buttons.first().isSelected = true
        val group = ButtonGroup()
        buttons.forEach(group::add)
        val directory = AppState[AppState.EXPORT_DIRECTORY]?.let(::File)
        val chooser = object : JFileChooser(directory) {
            override fun createDialog(parent: Component?): JDialog =
                super.createDialog(parent).also { WindowState.install(it, "export-chooser") }

            override fun approveSelection() {
                val file = selectedFile
                if (file.exists() && !confirmReplace(this, file)) return
                super.approveSelection()
            }
        }.apply {
            dialogTitle = "Export"
            selectedFile = File(currentDirectory, name)
            if (buttons.size > 1) {
                accessory = JPanel(MigLayout("insets 0 12 0 0, wrap 1")).apply {
                    add(JLabel("Export:"))
                    buttons.forEach(::add)
                }
            }
        }
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        AppState[AppState.EXPORT_DIRECTORY] = chooser.currentDirectory.path
        return ExportFile(chooser.selectedFile.toPath(), buttons.indexOfFirst { it.isSelected })
    }

    private fun confirmReplace(parent: Component, file: File): Boolean {
        val answer = JOptionPane.showConfirmDialog(
            parent, "${file.name} already exists. Replace it?", "Export", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        return answer == JOptionPane.YES_OPTION
    }
}
