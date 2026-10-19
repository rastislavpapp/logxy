package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import net.miginfocom.swing.MigLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JToolBar
import javax.swing.UIManager

/**
 * [view] (a list or table) with a row of icon buttons attached to its top edge, in one shared border, like IntelliJ's
 * toolbar decorator. The border is the panel's, not the scroll pane's, so focusing the view draws no ring around it.
 */
class ListToolbar(view: JComponent) : JPanel(MigLayout("insets 0, gap 0, fill", "[grow]", "[][][grow]")) {

    private val toolbar = JToolBar().apply {
        isFloatable = false
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
    }

    init {
        border = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"))
        add(toolbar, "growx, wrap")
        add(JSeparator(), "growx, wrap")
        add(JScrollPane(view).apply { border = null }, "grow")
    }

    /** Returns the added button, so that the caller can enable and disable it with the selection. */
    fun addButton(icon: Icon, tooltip: String, action: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            addActionListener { action() }
            toolbar.add(this)
        }
}
