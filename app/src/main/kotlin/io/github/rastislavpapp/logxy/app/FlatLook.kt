package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import io.github.rastislavpapp.logxy.ui.Look
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
import javax.swing.UIManager

/** The [Look] from FlatLaf's UI defaults and client properties. */
object FlatLook : Look {

    // FlatSVGIcon picks the `_dark` variant in dark themes by itself.
    override fun icon(path: String, size: Int): Icon = FlatSVGIcon("expui/$path.svg", size, size)

    override fun scale(value: Int): Int = UIScale.scale(value)

    override fun scale(value: Float): Float = UIScale.scale(value)

    override val accent: Color get() = UIManager.getColor("Component.accentColor")

    override val error: Color get() = UIManager.getColor("Actions.Red")

    override val warning: Color get() = UIManager.getColor("Actions.Yellow")

    override val errorText: Color get() = UIManager.getColor("Component.error.focusedBorderColor")

    override val isDark: Boolean get() = FlatLaf.isLafDark()

    override val border: Color get() = UIManager.getColor("Component.borderColor")

    override val monospacedFont: Font
        get() = UIManager.getFont("monospaced.font")
            ?: Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Table.font").size)

    override val smallFont: Font get() = UIManager.getFont("small.font") ?: UIManager.getFont("Label.font")

    override val cellMargins: Insets get() = UIManager.getInsets("Table.cellMargins") ?: Insets(0, 0, 0, 0)

    override fun flat(button: AbstractButton) {
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
    }

    // FlatLaf's minimum button width would pad a text this plain.
    override fun link(button: AbstractButton) {
        button.putClientProperty(FlatClientProperties.MINIMUM_WIDTH, 0)
        button.foreground = UIManager.getColor("Component.linkColor")
    }

    override fun accentToggle(text: String, icon: Icon?): JToggleButton = JToggleButton(text, icon).apply {
        putClientProperty("FlatLaf.style", "selectedBackground: \$Component.accentColor; selectedForeground: #ffffff")
        flat(this)
    }

    override fun trailingComponent(field: JTextField, component: JComponent) {
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, component)
    }

    override fun leadingIcon(field: JTextField, icon: Icon) {
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, icon)
    }

    override fun placeholder(field: JTextField, text: String) {
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, text)
    }

    override fun outlineError(component: JComponent, error: Boolean) {
        val outline = if (error) FlatClientProperties.OUTLINE_ERROR else null
        component.putClientProperty(FlatClientProperties.OUTLINE, outline)
    }

    override fun heading(label: JLabel) {
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3")
    }

    override fun expandableBottomOnly(split: JSplitPane) {
        split.putClientProperty(
            FlatClientProperties.SPLIT_PANE_EXPANDABLE_SIDE,
            FlatClientProperties.SPLIT_PANE_EXPANDABLE_SIDE_LEFT,
        )
    }

    override fun keepMenuOpen(item: JMenuItem) {
        item.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true)
    }
}
