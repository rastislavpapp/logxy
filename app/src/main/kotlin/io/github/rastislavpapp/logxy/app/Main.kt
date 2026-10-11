package io.github.rastislavpapp.logxy.app

import java.awt.Taskbar
import java.lang.invoke.MethodHandles
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.SwingUtilities

internal val MAIN_CLASS: String = MethodHandles.lookup().lookupClass().name

fun main(args: Array<String>) {
    Logging.init()
    if (Restart.atStartup(args.toList())) return
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
            openArgs(args.toList())
        }
    }
}
