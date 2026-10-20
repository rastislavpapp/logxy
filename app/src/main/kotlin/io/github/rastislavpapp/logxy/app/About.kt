package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.net.URI
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.UIManager

const val PROJECT_URL = "https://github.com/rastislavpapp/logxy"

/** The jar's version; unset when running from classes, e.g. through exec:java. */
val appVersion: String? = MainFrame::class.java.`package`?.implementationVersion

fun showAbout(owner: Component) {
    val muted = UIManager.getColor("Label.disabledForeground")
    val panel = JPanel(MigLayout("insets 8 16 0 16, wrap 1, gapy 4", "[center]")).apply {
        add(JLabel(Logo.icon(96)), "gapbottom 8")
        add(JLabel("logxy").apply { putClientProperty(FlatClientProperties.STYLE_CLASS, "h1") })
        add(JLabel(appVersion?.let { "Version $it" } ?: "Development build").apply { foreground = muted })
        add(JLabel("Fast desktop viewer for large log files."), "gaptop 8")
        val java = "Java ${System.getProperty("java.version")}, ${System.getProperty("java.vendor")}"
        add(JLabel(java).apply { foreground = muted })
        add(JButton(PROJECT_URL.removePrefix("https://")).apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
            foreground = UIManager.getColor("Component.linkColor")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isEnabled = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            addActionListener { Desktop.getDesktop().browse(URI(PROJECT_URL)) }
        }, "gaptop 4")
    }
    JOptionPane.showMessageDialog(owner, panel, "About logxy", JOptionPane.PLAIN_MESSAGE)
}
