package io.github.rastislavpapp.logxy.ui

import java.awt.Color
import java.awt.Font
import java.awt.Insets
import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JToggleButton

/**
 * What the views take from the look and feel beyond Swing's own UI defaults: the app's comes from FlatLaf, the
 * plugin's from IntelliJ's. The colours and fonts are read again on every use, as the theme can change at runtime.
 * The host installs it as [current] before it makes any view.
 */
interface Look {

    /** A "new UI" icon of the IntelliJ Platform by its path under `expui/`, without `.svg`; dark in a dark theme. */
    fun icon(path: String, size: Int = 16): Icon

    /** [value] in pixels at the screen's scale factor. */
    fun scale(value: Int): Int

    fun scale(value: Float): Float

    /** Highlights the current choice: a selected toggle, a selection range, progress. */
    val accent: Color

    /** Errors in charts and level colours. */
    val error: Color

    /** Warnings in charts and level colours. */
    val warning: Color

    /** Text that reports a problem, e.g. a filter that does not parse. */
    val errorText: Color

    /** Lines around and between components. */
    val border: Color

    /** The font of record text: table cells, the detail pane. */
    val monospacedFont: Font

    /** Axis labels and other secondary text. */
    val smallFont: Font

    /** Space around a table cell's text; themes differ, and without it timestamps and levels would be cut. */
    val cellMargins: Insets

    /** Draws [button] flat, as in a toolbar, with a background only while hovered or pressed. */
    fun flat(button: AbstractButton)

    /** A flat toggle whose on state is plainly visible: filled with [accent]. */
    fun accentToggle(text: String, icon: Icon?): JToggleButton

    /** Shows [component], such as option toggles, inside [field] at its end. */
    fun trailingComponent(field: JTextField, component: JComponent)

    /** Shows [icon] inside [field] at its start, e.g. a magnifier for a search field. */
    fun leadingIcon(field: JTextField, icon: Icon)

    /** Shows [text] in [field] while it is empty. */
    fun placeholder(field: JTextField, text: String)

    /** Outlines [component] as invalid while [error], or as usual. */
    fun outlineError(component: JComponent, error: Boolean)

    /** Shows [label] as a heading. */
    fun heading(label: JLabel)

    /** A split pane with one component above the other; a look whose own divider does not fit replaces it. */
    fun verticalSplit(): JSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)

    /** A split pane with one component beside the other, as [verticalSplit]. */
    fun horizontalSplit(): JSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

    /** Draws [button] as a link: its text in the link colour, without the button's background or minimum width. */
    fun link(button: AbstractButton)

    /** Offers only the one-touch arrow that hides [split]'s bottom component, and brings it back. */
    fun expandableBottomOnly(split: JSplitPane)

    /** Keeps [item]'s menu open when [item] is clicked, so several checks can be toggled in a row. */
    fun keepMenuOpen(item: JMenuItem)

    companion object {
        lateinit var current: Look
    }
}

/** Short for [Look.current]. */
internal val look: Look get() = Look.current
