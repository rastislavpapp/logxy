package io.github.rastislavpapp.logxy.core.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The options the packaged launcher starts the JVM with, such as the memory limit. The launcher hands the JVM
 * [installed] (`logxy.vmoptions` in the app directory) with `-XX:VMOptionsFile`; the user's options live in
 * [user], which, unlike the app directory, survives an upgrade, and [install] copies them over. The JVM does not
 * start if the installed file is missing or holds anything but options, not even a comment, so it is only ever
 * replaced whole.
 */
class VmOptions(val installed: Path, val user: Path) {

    /** What [install] did: nothing, as the files already matched; copied the user's options; or failed to. */
    enum class Install { UNCHANGED, CHANGED, FAILED }

    /** The options as shipped, from [DEFAULT_FILE] next to [installed]; empty if it is missing. */
    val defaults: List<String> get() = read(installed.resolveSibling(DEFAULT_FILE)).orEmpty()

    /** The user's options; [defaults] until some are saved. */
    fun current(): List<String> = read(user) ?: defaults

    /** Whether the launcher reads the user's options, so a restart applies them. */
    fun isInstalled(): Boolean = current() == read(installed)

    /** Saves [options] as the user's, then [install]s them; throws if the user's file cannot be written. */
    fun save(options: List<String>): Install {
        write(user, options)
        return install()
    }

    /**
     * Copies the user's options to [installed] where they differ, as after an upgrade. On the first start it
     * saves [defaults] as the user's, and throws if that file cannot be written. [Install.CHANGED] means the
     * running JVM still has the old options.
     */
    fun install(): Install {
        var options = read(user)
        if (options == null) {
            options = defaults
            write(user, options)
        }
        if (options == read(installed)) return Install.UNCHANGED
        return try {
            write(installed, options)
            Install.CHANGED
        } catch (_: IOException) {
            Install.FAILED
        }
    }

    companion object {
        /** The file the launcher reads, and the user's copy of it in the settings directory. */
        const val FILE = "logxy.vmoptions"

        /** Shipped next to [FILE]: the options to start from. */
        const val DEFAULT_FILE = "logxy.default.vmoptions"

        private const val XMX = "-Xmx"

        /** The `-Xmx` heap limit in [options], in bytes; null if there is none or it cannot be read. */
        fun memoryLimit(options: List<String>): Long? {
            val limit = options.lastOrNull { it.startsWith(XMX) } ?: return null
            val match = Regex("""(\d+)([kmgt]?)""").matchEntire(limit.removePrefix(XMX).lowercase()) ?: return null
            val (amount, unit) = match.destructured
            val shift = when (unit) {
                "k" -> 10
                "m" -> 20
                "g" -> 30
                "t" -> 40
                else -> 0
            }
            return amount.toLong() shl shift
        }

        /** [options] with the heap limit set to [gigabytes], or for null to the one in [defaults]. */
        fun withMemoryLimit(options: List<String>, gigabytes: Int?, defaults: List<String>): List<String> {
            val limit = if (gigabytes == null) defaults.filter(::limitsHeap) else listOf("$XMX${gigabytes}g")
            return options.filterNot(::limitsHeap) + limit
        }

        private fun limitsHeap(option: String) = option.startsWith(XMX) || option.startsWith("-XX:MaxRAMPercentage=")

        /** The options in [path], which the JVM splits at whitespace; null if the file does not exist. */
        private fun read(path: Path): List<String>? {
            if (!Files.exists(path)) return null
            return Files.readString(path).split(Regex("""\s+""")).filter { it.isNotEmpty() }
        }

        /** Writes [options] one per line. */
        private fun write(path: Path, options: List<String>) =
            PropertiesFiles.replace(path) { writer -> options.forEach { writer.write("$it\n") } }
    }
}
