package io.github.rastislavpapp.logxy.app

import java.nio.file.Path
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        Themes.apply(Theme.DARK)
        MainFrame().apply {
            isVisible = true
            openFiles(args.map { Path.of(it) })
        }
    }
}
