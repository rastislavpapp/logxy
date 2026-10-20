package io.github.rastislavpapp.logxy.plugin

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.UIManager

/**
 * The FlatLaf keys the app's UI reads that IntelliJ's look and feel may lack, with IntelliJ's nearest equivalents.
 * Only missing keys are set, and the ones set are logged: that list is what the shared UI must stop relying on.
 * A theme change replaces the defaults, so this runs again for each view.
 */
object LafShim {

    private val log = logger<LafShim>()

    fun apply() {
        val defaults = UIManager.getDefaults()
        val filled = ArrayList<String>()
        fun fill(key: String, value: () -> Any) {
            if (defaults[key] == null) {
                defaults[key] = value()
                filled += key
            }
        }
        fill("Actions.Yellow") { JBColor(0xEDA200, 0xF0A732) }
        fill("Actions.Red") { JBColor(0xDB5860, 0xC75450) }
        fill("Component.accentColor") { JBUI.CurrentTheme.Focus.focusColor() }
        fill("Component.borderColor") { JBColor.border() }
        fill("Component.error.focusedBorderColor") { JBUI.CurrentTheme.Focus.errorColor(true) }
        fill("Component.linkColor") { JBUI.CurrentTheme.Link.Foreground.ENABLED }
        fill("Label.disabledForeground") { UIManager.getColor("Label.foreground").darker() }
        fill("MenuItem.selectionBackground") { JBUI.CurrentTheme.List.Selection.background(true) }
        fill("Table.cellMargins") { JBUI.insets(1, 3) }
        fill("small.font") { JBFont.small() }
        fill("monospaced.font") {
            val scheme = EditorColorsManager.getInstance().globalScheme
            Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
        }
        if (filled.isNotEmpty()) log.info("logxy: filled missing UI defaults $filled")
    }
}
