package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.parse.LogFormat
import net.miginfocom.swing.MigLayout
import java.awt.Component
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.UIManager

/** Asks for a custom pattern layout or regex; returns null if cancelled. */
fun askForCustomFormat(parent: Component, initial: LogFormat?): LogFormat? {
    val patternChoice = JRadioButton("log4j / logback pattern", initial !is LogFormat.Regex)
    val regexChoice = JRadioButton("Regex with named groups", initial is LogFormat.Regex)
    ButtonGroup().apply {
        add(patternChoice)
        add(regexChoice)
    }
    val layout = JTextField(
        when (initial) {
            is LogFormat.Pattern -> initial.pattern
            is LogFormat.Regex -> initial.regex
            else -> "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n"
        },
        60,
    )
    val timestamp = JTextField((initial as? LogFormat.Regex)?.timestampPattern ?: "yyyy-MM-dd HH:mm:ss.SSS", 30)
    val hint = JLabel().apply { foreground = UIManager.getColor("Label.disabledForeground") }
    fun update() {
        timestamp.isEnabled = regexChoice.isSelected
        hint.text = if (regexChoice.isSelected) {
            "Groups: timestamp, level, thread, logger, message; other names become extra columns."
        } else {
            "As in log4j2/logback configuration, e.g. %d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n"
        }
    }
    patternChoice.addActionListener { update() }
    regexChoice.addActionListener { update() }
    update()

    val panel = JPanel(MigLayout("wrap 2, insets 0", "[][grow, fill]")).apply {
        add(patternChoice, "span 2, split 2")
        add(regexChoice)
        add(JLabel("Layout:"))
        add(layout)
        add(JLabel("Timestamp:"))
        add(timestamp)
        add(hint, "span 2")
    }
    while (true) {
        val answer = JOptionPane.showConfirmDialog(
            parent, panel, "Custom Format", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
        )
        if (answer != JOptionPane.OK_OPTION) return null
        val format = if (regexChoice.isSelected) {
            LogFormat.Regex("Custom regex", layout.text, timestamp.text)
        } else {
            LogFormat.Pattern("Custom pattern", layout.text)
        }
        try {
            format.compile()
            return format
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(parent, e.message ?: e.toString(), "Invalid Format", JOptionPane.ERROR_MESSAGE)
        }
    }
}
