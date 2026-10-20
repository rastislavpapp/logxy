package io.github.rastislavpapp.logxy.plugin

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import io.github.rastislavpapp.logxy.ui.Look
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

private const val ICON_SIZE = 16

/** The [Look] from IntelliJ's theme; the colours are theme-aware, so they follow a theme switch. */
object IdeLook : Look {

    // IconLoader picks the `_dark` variant in dark themes by itself.
    override fun icon(path: String, size: Int): Icon {
        val icon = IconLoader.getIcon("expui/$path.svg", IdeLook::class.java.classLoader)
        return if (size == ICON_SIZE) icon else IconUtil.scale(icon, null, size.toFloat() / ICON_SIZE)
    }

    override fun scale(value: Int): Int = JBUIScale.scale(value)

    override fun scale(value: Float): Float = JBUIScale.scale(value)

    override val accent: Color get() = JBUI.CurrentTheme.Focus.focusColor()

    override val error: Color get() = JBColor.namedColor("Actions.Red", JBColor(0xDB5860, 0xC75450))

    override val warning: Color get() = JBColor.namedColor("Actions.Yellow", JBColor(0xEDA200, 0xF0A732))

    override val errorText: Color get() = NamedColorUtil.getErrorForeground()

    override val border: Color get() = JBColor.border()

    /** The editor's font, as the console's text is in it. */
    override val monospacedFont: Font
        get() = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

    override val smallFont: Font get() = JBFont.small()

    override val cellMargins: Insets get() = JBUI.insets(1, 3)

    // IntelliJ's button UI makes buttons at least 72 pixels wide, and draws no text on its square, toolbar-like ones.
    override fun flat(button: AbstractButton) {
        button.setUI(FlatButtonUI)
        // A theme switch installs the look's button UI again.
        button.addPropertyChangeListener("UI") { if (button.ui !== FlatButtonUI) button.setUI(FlatButtonUI) }
        button.isRolloverEnabled = true
        button.isOpaque = false
        button.border = JBUI.Borders.empty(3, 4)
    }

    override fun accentToggle(text: String, icon: Icon?): JToggleButton = JToggleButton(text, icon).apply { flat(this) }

    // IntelliJ's text fields have no slot for components inside them: the component goes over the field's end,
    // and the field's text stops short of it.
    override fun trailingComponent(field: JTextField, component: JComponent) {
        field.layout = BorderLayout()
        field.add(component, BorderLayout.EAST)
        fun reserve() {
            val gap = BorderFactory.createEmptyBorder(0, 0, 0, component.preferredSize.width)
            field.border = BorderFactory.createCompoundBorder(field.border, gap)
        }
        reserve()
        // A theme switch replaces the border.
        field.addPropertyChangeListener("UI") { reserve() }
    }

    // IntelliJ draws a search field's magnifier itself.
    override fun leadingIcon(field: JTextField, icon: Icon) {
        field.putClientProperty("JTextField.variant", "search")
    }

    // A plain text field has no placeholder in IntelliJ's look; the hint shows as its tooltip.
    override fun placeholder(field: JTextField, text: String) {
        if (field.toolTipText == null) field.toolTipText = text
    }

    override fun outlineError(component: JComponent, error: Boolean) {
        component.putClientProperty("JComponent.outline", if (error) "error" else null)
    }

    override fun heading(label: JLabel) {
        label.font = JBFont.h3()
    }

    // A plain JSplitPane gets Swing's basic divider in IntelliJ's look, drawn as a hatched bar.
    override fun verticalSplit(): JSplitPane = object : JSplitPane(VERTICAL_SPLIT) {
        override fun updateUI() = setUI(LineDividerUI())
    }

    override fun horizontalSplit(): JSplitPane = object : JSplitPane(HORIZONTAL_SPLIT) {
        override fun updateUI() = setUI(LineDividerUI())
    }

    // IntelliJ's button UI makes buttons at least 72 pixels wide; Basic draws the text alone.
    override fun link(button: AbstractButton) {
        button.setUI(BasicButtonUI())
        button.addPropertyChangeListener("UI") { if (button.ui !is BasicButtonUI) button.setUI(BasicButtonUI()) }
        button.isFocusPainted = false
        button.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
    }

    // IntelliJ's split panes offer no choice of arrows.
    override fun expandableBottomOnly(split: JSplitPane) {}

    /** A divider drawn as a line in the border colour across the panel background, as IntelliJ's own splitters. */
    private class LineDividerUI : BasicSplitPaneUI() {
        override fun createDefaultDivider(): BasicSplitPaneDivider = object : BasicSplitPaneDivider(this) {
            override fun paint(g: Graphics) {
                g.color = UIUtil.getPanelBackground()
                g.fillRect(0, 0, width, height)
                g.color = IdeLook.border
                if (orientation == JSplitPane.VERTICAL_SPLIT) {
                    g.fillRect(0, height / 2, width, 1)
                } else {
                    g.fillRect(width / 2, 0, 1, height)
                }
                // The one-touch arrows.
                paintComponents(g)
            }
        }
    }

    // IntelliJ's menus close on every click.
    override fun keepMenuOpen(item: JMenuItem) {}

    /**
     * A button sized to its content, with a background only while hovered or pressed, as in IntelliJ's toolbars; a
     * selected toggle is filled with [accent], its text white. Shared, as it keeps no state of its own.
     */
    private object FlatButtonUI : BasicButtonUI() {

        override fun paint(g: Graphics, c: JComponent) {
            val model = (c as AbstractButton).model
            val fill = when {
                model.isSelected -> accent
                model.isPressed -> JBUI.CurrentTheme.ActionButton.pressedBackground()
                model.isRollover && model.isEnabled -> JBUI.CurrentTheme.ActionButton.hoverBackground()
                else -> null
            }
            if (fill != null) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = fill
                    val arc = JBUIScale.scale(6)
                    g2.fillRoundRect(0, 0, c.width, c.height, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
            super.paint(g, c)
        }

        override fun paintText(g: Graphics, c: JComponent, textRect: Rectangle, text: String) {
            val button = c as AbstractButton
            g.color = when {
                !button.isEnabled -> UIUtil.getLabelDisabledForeground()
                button.isSelected -> JBColor.WHITE
                else -> button.foreground
            }
            g.font = button.font
            UIUtil.drawCenteredString(g as Graphics2D, textRect, text)
        }

        // The background is painted above; Basic would fill a pressed button with its own colour.
        override fun paintButtonPressed(g: Graphics, b: AbstractButton) {}
    }
}
