package io.github.rastislavpapp.logxy.app

import java.awt.Taskbar
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
        // The macOS dock shows a generic Java icon unless told otherwise; elsewhere the window icons suffice.
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().iconImage = Icons.logo(256).image
        }
        MainFrame().apply {
            isVisible = true
            openFiles(args.map { Path.of(it) })
        }
    }
}
