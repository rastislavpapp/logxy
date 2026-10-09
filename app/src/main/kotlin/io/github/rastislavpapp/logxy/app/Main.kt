package io.github.rastislavpapp.logxy.app

import java.nio.file.Path
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        Themes.installFonts()
        if (AppState[AppState.TITLE_BAR] != "false") {
            // FlatLaf draws the title bars itself, with the menu bar inside, IntelliJ-style.
            JFrame.setDefaultLookAndFeelDecorated(true)
            JDialog.setDefaultLookAndFeelDecorated(true)
        }
        Themes.apply(Themes.saved())
        MainFrame().apply {
            isVisible = true
            openFiles(args.map { Path.of(it) })
        }
    }
}
