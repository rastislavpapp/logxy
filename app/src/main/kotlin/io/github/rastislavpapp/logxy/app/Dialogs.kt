package io.github.rastislavpapp.logxy.app

import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.JDialog

/**
 * Sizes the dialog from its content (which FlatLaf scales on HiDPI screens), at least [fraction] of
 * the usable screen area, but never larger than it.
 */
fun JDialog.fitToScreen(fraction: Double) {
    pack()
    val configuration = owner?.graphicsConfiguration ?: graphicsConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    val screen = configuration.bounds
    val maxWidth = screen.width - insets.left - insets.right
    val maxHeight = screen.height - insets.top - insets.bottom
    size = Dimension(
        maxOf(width, (maxWidth * fraction).toInt()).coerceAtMost(maxWidth),
        maxOf(height, (maxHeight * fraction).toInt()).coerceAtMost(maxHeight),
    )
    minimumSize = Dimension(minOf(preferredSize.width, maxWidth) / 2, minOf(preferredSize.height, maxHeight) / 2)
    setLocationRelativeTo(owner)
}
