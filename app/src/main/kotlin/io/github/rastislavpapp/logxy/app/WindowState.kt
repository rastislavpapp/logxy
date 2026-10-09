package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.util.Properties
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Remembers the main window's position, size, monitor and maximized state between runs. */
object WindowState {

    private val path = Settings.directory().resolve("window.properties")

    /** Restores the saved state (call before showing [frame]) and saves it when the window closes. */
    fun install(frame: JFrame) {
        restore(frame)
        var normalBounds = frame.bounds
        // Bounds to return to when un-maximized; window managers forget them if the window started maximized.
        var restoreBounds = frame.bounds
        frame.addWindowStateListener { e ->
            val wasMaximized = e.oldState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
            val isMaximized = e.newState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
            if (!wasMaximized && isMaximized) restoreBounds = normalBounds
            if (wasMaximized && !isMaximized) {
                val target = restoreBounds
                SwingUtilities.invokeLater { frame.bounds = target }
            }
        }
        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = remember()
            override fun componentResized(e: ComponentEvent) = remember()

            // Maximized bounds are not worth keeping: un-maximizing should return to the normal ones.
            private fun remember() {
                if (frame.isShowing && frame.extendedState and Frame.MAXIMIZED_BOTH == 0) normalBounds = frame.bounds
            }
        })
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = save(frame, normalBounds)
            override fun windowClosed(e: WindowEvent) = save(frame, normalBounds)
        })
    }

    private fun restore(frame: JFrame) {
        val properties = Properties()
        try {
            if (!Files.exists(path)) return
            Files.newBufferedReader(path).use { properties.load(it) }
            val saved = Rectangle(
                properties.getProperty("x").toInt(), properties.getProperty("y").toInt(),
                properties.getProperty("width").toInt(), properties.getProperty("height").toInt(),
            )
            val screen = screenFor(saved, properties.getProperty("screen"))
            val area = usableArea(screen)
            frame.bounds = if (area.intersects(saved)) fitInto(saved, area) else centeredIn(saved, area)
            if (properties.getProperty("maximized").toBoolean()) frame.extendedState = Frame.MAXIMIZED_BOTH
        } catch (e: Exception) {
            System.err.println("Cannot restore the window from $path: $e")
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
        return Rectangle(area.x + (area.width - fitted.width) / 2, area.y + (area.height - fitted.height) / 2, fitted.width, fitted.height)
    }

    private fun save(frame: JFrame, normalBounds: Rectangle) {
        val properties = Properties()
        properties["x"] = normalBounds.x.toString()
        properties["y"] = normalBounds.y.toString()
        properties["width"] = normalBounds.width.toString()
        properties["height"] = normalBounds.height.toString()
        properties["maximized"] = (frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH).toString()
        properties["screen"] = frame.graphicsConfiguration.device.iDstring
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { properties.store(it, "logxy window state") }
        } catch (e: Exception) {
            System.err.println("Cannot save the window state to $path: $e")
        }
    }
}
