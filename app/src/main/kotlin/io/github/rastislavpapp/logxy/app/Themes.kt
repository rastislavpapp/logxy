package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.extras.FlatAnimatedLafChange

enum class Theme { LIGHT, DARK }

object Themes {

    var current: Theme = Theme.DARK
        private set

    /** Installs the look and feel. Call before any component is created. */
    fun apply(theme: Theme) {
        // Default FlatLaf scroll bars have no visible track, and the thumb gets tiny for millions of rows.
        val track = if (theme == Theme.DARK) "lighten(@background,6%)" else "darken(@background,6%)"
        FlatLaf.setGlobalExtraDefaults(
            mapOf(
                "ScrollBar.track" to track,
                "ScrollBar.width" to "14",
                "ScrollBar.minimumThumbSize" to "10,48",
            )
        )
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
