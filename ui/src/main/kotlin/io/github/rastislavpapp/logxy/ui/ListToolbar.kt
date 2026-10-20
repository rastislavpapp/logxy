package io.github.rastislavpapp.logxy.ui

import net.miginfocom.swing.MigLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JToolBar

/**
 * [view] (a list or table) with a row of icon buttons attached to its top edge, in one shared border, like IntelliJ's
 * toolbar decorator. The border is the panel's, not the scroll pane's, so focusing the view draws no ring around it.
 */
class ListToolbar(view: JComponent) : JPanel(MigLayout("insets 0, gap 0, fill", "[grow]", "[][][grow]")) {

    // Not opaque: IntelliJ's look paints a tool bar's own background, lighter than the panel's.
    private val toolbar = JToolBar().apply {
        isFloatable = false
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
    }

    init {
        border = BorderFactory.createLineBorder(look.border)
        add(toolbar, "growx, wrap")
        add(JSeparator(), "growx, wrap")
        add(JScrollPane(view).apply { border = null }, "grow")
    }

    /** Returns the added button, so that the caller can enable and disable it with the selection. */
    fun addButton(icon: Icon, tooltip: String, action: () -> Unit): JButton =
        toolBarButton(icon, tooltip, focusable = true) { action() }.also { toolbar.add(it) }
}
