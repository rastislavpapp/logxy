package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatDarculaLaf
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.extras.FlatAnimatedLafChange
import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import javax.swing.UIManager

class ThemeInfo(val name: String, val className: String, val dark: Boolean)

object Themes {

    /** FlatLaf's own themes. */
    val CORE = listOf(
        ThemeInfo("Dark", FlatDarkLaf::class.java.name, dark = true),
        ThemeInfo("Light", FlatLightLaf::class.java.name, dark = false),
        ThemeInfo("Darcula", FlatDarculaLaf::class.java.name, dark = true),
        ThemeInfo("IntelliJ Light", FlatIntelliJLaf::class.java.name, dark = false),
        ThemeInfo("macOS Dark", FlatMacDarkLaf::class.java.name, dark = true),
        ThemeInfo("macOS Light", FlatMacLightLaf::class.java.name, dark = false),
    )

    /** Themes from the IntelliJ theme pack (Material, One Dark, Nord, …). */
    val INTELLIJ = FlatAllIJThemes.INFOS.map { ThemeInfo(it.name, it.className, it.isDark) }.sortedBy { it.name }

    var current: ThemeInfo = CORE.first()
        private set

    /** Inter for the UI and JetBrains Mono for log text, the same on every platform. */
    fun installFonts() {
        FlatInterFont.install()
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY)
        FlatLaf.setPreferredLightFontFamily(FlatInterFont.FAMILY_LIGHT)
        FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD)
        FlatJetBrainsMonoFont.install()
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY)
    }

    /** The theme chosen last time, or Dark. */
    fun saved(): ThemeInfo = (CORE + INTELLIJ).firstOrNull { it.className == AppState[AppState.THEME] } ?: CORE.first()

    /** Installs the look and feel. Call before any component is created. */
    fun apply(theme: ThemeInfo) {
        UIManager.setLookAndFeel(theme.className)
        current = theme
    }

    /** Switches the look and feel of an already running UI, with a short fade, and remembers it. */
    fun switchTo(theme: ThemeInfo) {
        if (theme.className == current.className) return
        FlatAnimatedLafChange.showSnapshot()
        apply(theme)
        FlatLaf.updateUI()
        FlatAnimatedLafChange.hideSnapshotWithAnimation()
        AppState[AppState.THEME] = theme.className
    }
}
