package io.github.rastislavpapp.logxy.app

import javax.swing.SwingUtilities

fun main() {
    SwingUtilities.invokeLater {
        Themes.apply(Theme.LIGHT)
        MainFrame().isVisible = true
    }
}
