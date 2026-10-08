package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.extras.FlatAnimatedLafChange

enum class Theme { LIGHT, DARK }

object Themes {

    var current: Theme = Theme.LIGHT
        private set

    /** Installs the look and feel. Call before any component is created. */
    fun apply(theme: Theme) {
        when (theme) {
            Theme.LIGHT -> FlatLightLaf.setup()
            Theme.DARK -> FlatDarkLaf.setup()
        }
        current = theme
    }

    /** Switches the look and feel of an already running UI, with a short fade. */
    fun switchTo(theme: Theme) {
        if (theme == current) return
        FlatAnimatedLafChange.showSnapshot()
        apply(theme)
        FlatLaf.updateUI()
        FlatAnimatedLafChange.hideSnapshotWithAnimation()
    }
}
