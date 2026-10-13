package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.PropertiesFiles
import io.github.rastislavpapp.logxy.core.config.Settings
import org.slf4j.LoggerFactory
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** Remembers windows' position, size, monitor and (for frames) maximized state between runs, each under its own key. */
object WindowState {

    const val MAIN = "main"

    private val log = LoggerFactory.getLogger(WindowState::class.java)
    private val path = Settings.directory().resolve("window.properties")

    /**
     * Restores the state saved under [key] and saves it when the window closes. Call before showing
     * [window], after giving it its default size, which applies when nothing was saved yet.
     */
    fun install(window: Window, key: String) {
        restore(window, key)
        var normalBounds = window.bounds
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = remember()
            override fun componentResized(e: ComponentEvent) = remember()

            // Maximized bounds are not worth keeping: un-maximizing should return to the normal ones.
            private fun remember() {
                if (window.isShowing && !isMaximized(window)) normalBounds = window.bounds
            }
        })
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = save(window, key, normalBounds)
            override fun windowClosed(e: WindowEvent) = save(window, key, normalBounds)
        })
        if (window is Frame) restoreUnmaximized(window) { normalBounds }
    }

    private fun isMaximized(window: Window) =
        window is Frame && window.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH

    private fun restoreUnmaximized(frame: Frame, normalBounds: () -> Rectangle) {
        // Bounds to return to when un-maximized; window managers forget them if the window started maximized.
        var restoreBounds = frame.bounds
        frame.addWindowStateListener { e ->
            val wasMaximized = e.oldState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
            val isMaximized = e.newState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
            if (!wasMaximized && isMaximized) restoreBounds = normalBounds()
            if (wasMaximized && !isMaximized) {
                val target = restoreBounds
                SwingUtilities.invokeLater { frame.bounds = target }
            }
        }
    }

    private fun restore(window: Window, key: String) {
        try {
            val properties = PropertiesFiles.load(path)

            // The main window's entries had no prefix before other windows were remembered too.
            fun value(name: String): String? {
                properties.getProperty("$key.$name")?.let { return it }
                return if (key == MAIN) properties.getProperty(name) else null
            }
            val x = value("x") ?: return
            val saved = Rectangle(x.toInt(), value("y")!!.toInt(), value("width")!!.toInt(), value("height")!!.toInt())
            val screen = screenFor(saved, value("screen"))
            val area = usableArea(screen)
            window.bounds = if (area.intersects(saved)) fitInto(saved, area) else centeredIn(saved, area)
            if (window is Frame && value("maximized").toBoolean()) window.extendedState = Frame.MAXIMIZED_BOTH
        } catch (e: Exception) {
            log.warn("Cannot restore the window from {}", path, e)
        }
    }

    /** The monitor showing most of [bounds]; else the one saved by id; else the primary one. */
    private fun screenFor(bounds: Rectangle, id: String?): GraphicsConfiguration {
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
        val overlapping = screens.maxByOrNull { screen ->
            val overlap = screen.bounds.intersection(bounds)
            if (overlap.isEmpty) 0L else overlap.width.toLong() * overlap.height
        }
        if (overlapping != null && !overlapping.bounds.intersection(bounds).isEmpty) return overlapping
        return screens.firstOrNull { it.device.iDstring == id }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    }

    private fun usableArea(screen: GraphicsConfiguration): Rectangle {
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
        val bounds = screen.bounds
        return Rectangle(
            bounds.x + insets.left, bounds.y + insets.top,
            bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom,
        )
    }

    /** [bounds] shrunk to fit [area] if needed and moved fully into it, so the title bar is reachable. */
    private fun fitInto(bounds: Rectangle, area: Rectangle): Rectangle {
        val width = bounds.width.coerceIn(200, area.width)
        val height = bounds.height.coerceIn(150, area.height)
        val x = bounds.x.coerceIn(area.x, area.x + area.width - width)
        val y = bounds.y.coerceIn(area.y, area.y + area.height - height)
        return Rectangle(x, y, width, height)
    }

    /** The saved monitor is gone: keep the size, but center the window on [area]. */
    private fun centeredIn(bounds: Rectangle, area: Rectangle): Rectangle {
        val fitted = fitInto(bounds, area)
        val x = area.x + (area.width - fitted.width) / 2
        val y = area.y + (area.height - fitted.height) / 2
        return Rectangle(x, y, fitted.width, fitted.height)
    }

    private fun save(window: Window, key: String, normalBounds: Rectangle) {
        try {
            val properties = PropertiesFiles.load(path)
            listOf("x", "y", "width", "height", "maximized", "screen").forEach { properties.remove(it) }
            properties["$key.x"] = normalBounds.x.toString()
            properties["$key.y"] = normalBounds.y.toString()
            properties["$key.width"] = normalBounds.width.toString()
            properties["$key.height"] = normalBounds.height.toString()
            properties["$key.screen"] = window.graphicsConfiguration.device.iDstring
            if (window is Frame) properties["$key.maximized"] = isMaximized(window).toString()
            PropertiesFiles.store(path, properties, "logxy window state")
        } catch (e: Exception) {
            log.warn("Cannot save the window state to {}", path, e)
        }
    }
}
