package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import org.slf4j.LoggerFactory
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** Sets up logxy's own logging from `log4j2.xml` in the settings directory, which users can edit while it runs. */
object Logging {

    private const val CONFIG = "log4j2.xml"

    /** Must run before anything logs: Log4j reads its configuration once, on first use. */
    fun init() {
        val directory = Settings.directory()
        System.setProperty("logxy.logDir", directory.resolve("logs").toString())
        // A configuration given with -D wins.
        if (System.getProperty("log4j2.configurationFile") == null) {
            val config = directory.resolve(CONFIG)
            try {
                Files.createDirectories(directory)
                Logging::class.java.getResourceAsStream("/$CONFIG")!!.use { Files.copy(it, config) }
            } catch (_: FileAlreadyExistsException) {
            } catch (e: Exception) {
                System.err.println("Cannot write $config: $e")
            }
            // Without the file, Log4j falls back to the copy bundled in the jar.
            if (Files.exists(config)) System.setProperty("log4j2.configurationFile", config.toUri().toString())
        }
        val log = LoggerFactory.getLogger(Logging::class.java)
        Thread.setDefaultUncaughtExceptionHandler { thread, e -> log.error("Uncaught exception in {}", thread.name, e) }
        log.info("logxy {} on Java {} ({})", Logging::class.java.`package`?.implementationVersion ?: "development build", Runtime.version(), System.getProperty("java.home"))
    }
}
