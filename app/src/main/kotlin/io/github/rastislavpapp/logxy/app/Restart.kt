package io.github.rastislavpapp.logxy.app

import java.awt.Window
import java.awt.event.WindowEvent
import java.io.IOException
import java.nio.file.Path
import javax.swing.JOptionPane

/** Starts logxy again in a new JVM, to apply a memory limit: a running JVM cannot change its heap limit. */
object Restart {

    /** Set in a restarted app, so it does not restart itself again. */
    private const val RESTARTED = "LOGXY_RESTARTED"

    /** JAVA_TOOL_OPTIONS from before logxy added its limit, if there were any. */
    private const val ORIGINAL_OPTIONS = "LOGXY_JAVA_TOOL_OPTIONS"

    /** Restarts with the limit from the settings, if one is set; false if this JVM should carry on. */
    fun atStartup(args: List<String>): Boolean {
        val maxMemory = AppSettings.current.maxMemory ?: return false
        return System.getenv(RESTARTED) == null && start(args, maxMemory)
    }

    /**
     * Starts a new app with the limit from the settings and the same tabs open, then closes the main window
     * of [window] as the user would.
     */
    fun now(window: Window?) {
        val main = generateSequence(window) { it.owner }.lastOrNull()
        if (!start((main as? MainFrame)?.tabArgs().orEmpty(), AppSettings.current.maxMemory)) {
            JOptionPane.showMessageDialog(main, "logxy could not be started again; start it yourself to apply the limit.", "Restart", JOptionPane.ERROR_MESSAGE)
            return
        }
        // Closing rather than exiting, so the window state is saved.
        main?.dispatchEvent(WindowEvent(main, WindowEvent.WINDOW_CLOSING))
    }

    /** Starts a new app with [maxMemory] (null for the JVM default); false if it could not be started. */
    fun start(args: List<String>, maxMemory: String?): Boolean {
        // The jpackage launcher passes its arguments to the app, not the JVM, so the limit goes in JAVA_TOOL_OPTIONS.
        val command = System.getProperty("jpackage.app-path")?.let { listOf(it) } ?: listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), MAIN_CLASS,
        )
        val builder = ProcessBuilder(command + args).inheritIO()
        val env = builder.environment()
        val original = if (env[RESTARTED] != null) env[ORIGINAL_OPTIONS] else env["JAVA_TOOL_OPTIONS"]
        val options = listOfNotNull(original?.ifBlank { null }, maxMemory?.let { "-Xmx$it" }).joinToString(" ")
        if (options.isEmpty()) env.remove("JAVA_TOOL_OPTIONS") else env["JAVA_TOOL_OPTIONS"] = options
        if (original.isNullOrBlank()) env.remove(ORIGINAL_OPTIONS) else env[ORIGINAL_OPTIONS] = original
        env[RESTARTED] = "1"
        return try {
            builder.start()
            true
        } catch (e: IOException) {
            System.err.println("Cannot restart logxy: $e")
            false
        }
    }
}
