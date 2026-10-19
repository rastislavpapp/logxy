package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.config.VmOptions
import org.slf4j.LoggerFactory
import java.awt.Window
import java.awt.event.WindowEvent
import java.io.IOException
import java.nio.file.Path
import javax.swing.JOptionPane

/** Starts logxy again through its launcher, to apply changed [VmOptions]: a running JVM keeps its heap limit. */
object Restart {

    private val log = LoggerFactory.getLogger(Restart::class.java)

    /** Set in a restarted app, so it does not restart itself again. */
    private const val RESTARTED = "LOGXY_RESTARTED"

    /** The launcher's options; null when logxy was not started by the packaged launcher, which names its file. */
    val vmOptions: VmOptions? = System.getProperty("logxy.vmoptions")?.let { installed ->
        VmOptions(Path.of(installed), Settings.directory().resolve(VmOptions.FILE))
    }

    /**
     * Installs the user's [VmOptions] and restarts if the launcher started this JVM with others, as on the
     * first start after an upgrade; false if this JVM should carry on.
     */
    fun atStartup(args: List<String>): Boolean {
        val options = vmOptions ?: return false
        if (System.getenv(RESTARTED) != null) return false
        val installed = try {
            options.install()
        } catch (e: IOException) {
            log.warn("Cannot save the JVM options to {}", options.user, e)
            return false
        }
        if (installed == VmOptions.Install.FAILED) log.warn("Cannot write {}", options.installed)
        return installed == VmOptions.Install.CHANGED && start(args)
    }

    /**
     * Starts a new app with the same tabs open, then closes the main window of [window] as the user would.
     */
    fun now(window: Window?) {
        val main = generateSequence(window) { it.owner }.lastOrNull()
        if (!start((main as? MainFrame)?.tabArgs().orEmpty())) {
            val message = "logxy could not be started again; start it yourself to apply the changed options."
            JOptionPane.showMessageDialog(main, message, "Restart", JOptionPane.ERROR_MESSAGE)
            return
        }
        // Closing rather than exiting, so the window state is saved.
        main?.dispatchEvent(WindowEvent(main, WindowEvent.WINDOW_CLOSING))
    }

    /** Starts a new app through the launcher; false if there is none or it could not be started. */
    private fun start(args: List<String>): Boolean {
        val launcher = System.getProperty("jpackage.app-path") ?: return false
        val builder = ProcessBuilder(listOf(launcher) + args).inheritIO()
        builder.environment()[RESTARTED] = "1"
        return try {
            builder.start()
            true
        } catch (e: IOException) {
            log.error("Cannot restart logxy", e)
            false
        }
    }
}
