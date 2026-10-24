package io.github.rastislavpapp.logxy.core.config

import io.github.rastislavpapp.logxy.core.index.NewlineScanner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The options the packaged launcher starts the JVM with, such as the memory limit and whether the newline search
 * uses vectors.
 *
 * The launcher passes the JVM [installed], `logxy.vmoptions` in the app directory, with `-XX:VMOptionsFile`.
 * The user's options live in [user] instead, as the app directory does not survive an upgrade, and [install]
 * copies them to [installed]. The JVM does not start if the installed file is missing or holds anything but
 * options, not even a comment, so it is only ever replaced whole.
 */
class VmOptions(val installed: Path, val user: Path) {

    /**
     * What [install] did: [UNCHANGED] when both files already held the same options, [CHANGED] when it copied
     * the user's options to [installed], [FAILED] when it could not write [installed].
     */
    enum class Install { UNCHANGED, CHANGED, FAILED }

    /** The options as shipped, from [DEFAULT_FILE] next to [installed]; empty if it is missing. */
    val defaults: List<String> get() = read(installed.resolveSibling(DEFAULT_FILE)).orEmpty()

    /** The user's options; [defaults] until some are saved. */
    fun current(): List<String> = read(user) ?: defaults

    /**
     * Whether the last [install] could not write [installed], so options saved now do not reach the launcher.
     * False until an install has tried to write it; an edit to either file since then does not change it.
     */
    var installFailed = false
        private set

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
        installFailed = try {
            write(installed, options)
            false
        } catch (_: IOException) {
            true
        }
        return if (installFailed) Install.FAILED else Install.CHANGED
    }

    companion object {
        /** The file the launcher reads, and the user's copy of it in the settings directory. */
        const val FILE = "logxy.vmoptions"

        /** The file of the options as shipped, next to the installed [FILE]. */
        const val DEFAULT_FILE = "logxy.default.vmoptions"

        private const val XMX = "-Xmx"

        /** What follows `-Xmx` in the last such option in [options], as written (`8g`, `8192m`); null if none. */
        fun memoryLimit(options: List<String>): String? =
            options.lastOrNull { it.startsWith(XMX) }?.removePrefix(XMX)

        /**
         * [options] with their heap limit replaced by `-Xmx`[limit], e.g. `-Xmx8g` for `8g`, or by the heap
         * limit in [defaults] when [limit] is null. [limit] is not checked: a value the JVM rejects stops it
         * from starting.
         */
        fun withMemoryLimit(options: List<String>, limit: String?, defaults: List<String>): List<String> {
            val heap = if (limit == null) defaults.filter(::limitsHeap) else listOf("$XMX$limit")
            return options.filterNot(::limitsHeap) + heap
        }

        private fun limitsHeap(option: String) = option.startsWith(XMX) || option.startsWith("-XX:MaxRAMPercentage=")

        private const val NEWLINES = "-D${NewlineScanner.PROPERTY}="
        private const val SWAR_ONLY = "$NEWLINES${NewlineScanner.SWAR}"

        /** Whether [options] let [NewlineScanner] search with vectors, which it does by default where supported. */
        fun vectorSearch(options: List<String>): Boolean = options.lastOrNull { it.startsWith(NEWLINES) } != SWAR_ONLY

        /** [options] with the vector newline search turned on, or, if not [on], off, so that it uses SWAR only. */
        fun withVectorSearch(options: List<String>, on: Boolean): List<String> =
            options.filterNot { it.startsWith(NEWLINES) } + if (on) emptyList() else listOf(SWAR_ONLY)

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
