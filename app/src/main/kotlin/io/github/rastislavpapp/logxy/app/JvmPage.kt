package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.config.VmOptions
import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.MIN_VECTOR_BITS
import io.github.rastislavpapp.logxy.core.index.NewlineScanner
import io.github.rastislavpapp.logxy.core.index.VectorSupport
import io.github.rastislavpapp.logxy.ui.Icons
import net.miginfocom.swing.MigLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * The JVM's options: the most memory logxy may use, and whether it searches for newlines with vectors. They are kept
 * in the launcher's [VmOptions] rather than the [Settings], and apply only to a newly started JVM. Without the
 * packaged launcher there is nothing to keep them in, so the page only tells.
 */
class JvmPage : PreferencesPage("JVM", Icons.memory) {

    private val vmOptions = Restart.vmOptions

    /** The `-Xmx` value as the file has it, so an untouched page saves nothing back. */
    private val initialLimit = vmOptions?.let { VmOptions.memoryLimit(it.current()) }.orEmpty()
    private val xmx = JTextField(initialLimit).apply { isEnabled = vmOptions != null }

    private val support = NewlineScanner.vectorSupport

    /** Whether the next start may use vectors: what the file says, or without it what this JVM does. */
    private val initialVectors = vmOptions?.let { VmOptions.vectorSearch(it.current()) }
        ?: (System.getProperty(NewlineScanner.PROPERTY) != NewlineScanner.SWAR)
    private val vectors = JCheckBox("Search for newlines with SIMD instructions (Java's Vector API)").apply {
        isSelected = initialVectors && support is VectorSupport.Supported
        isEnabled = vmOptions != null && support is VectorSupport.Supported
    }

    init {
        layout = MigLayout("insets 0", "[grow, fill]", "[]rel[]para[][]para[]unrel[]rel[]para[]")
        add(heading("Memory"), "wrap")
        addNote(memoryHint())
        // Its own panel, so that the field stays beside its label in the stretched column.
        val limit = JPanel(MigLayout("insets 0", "[][100!]")).apply {
            add(JLabel("-Xmx"))
            add(xmx)
        }
        add(limit, "wrap")
        // The packaged launcher's defaults raise the JVM's; see the jpackage task.
        val example = "For example 8g or 8192m; empty for the default, " +
            if (vmOptions != null) "half of this computer's memory." else "a quarter of this computer's memory."
        addNote(example)
        add(heading("Newline search"), "wrap")
        add(vectors, "wrap")
        addNote(vectorHint())
        if (vmOptions != null) {
            // Restart.atStartup installs the user's file, so an edit there applies from the next start.
            val file = vmOptions.user.fileName
            val hint = "Any other Java option can be added to $file; it applies from the next start."
            addNote(hint)
        }
    }

    override val savedIn: String
        get() = vmOptions?.let { "JVM options are saved in ${it.user}" }
            ?: "Not saved: started without its launcher, logxy has no JVM options file"

    private fun memoryHint(): String {
        val now = "logxy can use up to ${formatByteSize(Runtime.getRuntime().maxMemory())} now."
        return when {
            vmOptions == null -> "$now Started without its launcher, logxy takes the limit from Java's -Xmx option."
            vmOptions.installFailed ->
                "$now It cannot write ${vmOptions.installed}, so a limit set here does not apply. " +
                    "Move logxy to a folder you can write to."
            else -> "$now Large logs, and many open at once, need more. A new limit applies after a restart."
        }
    }

    /** Why the search uses vectors or not, and so why the checkbox may be off. */
    private fun vectorHint(): String = when (support) {
        is VectorSupport.Supported -> {
            val now = "This computer runs ${support.bits}-bit vectors, which index logs about twice as fast. " +
                "In use now: ${NewlineScanner.implementation}."
            if (vmOptions == null) {
                "$now Started without its launcher, logxy uses them unless Java's " +
                    "-D${NewlineScanner.PROPERTY}=${NewlineScanner.SWAR} option turns them off."
            } else {
                "$now A change applies after a restart."
            }
        }
        VectorSupport.NoModule ->
            "Unavailable: logxy was started without Java's Vector API module (--add-modules jdk.incubator.vector)."
        is VectorSupport.TooNarrow ->
            "Unavailable: this processor's vectors are ${support.bits} bits wide. The search needs $MIN_VECTOR_BITS " +
                "(AVX2), as narrower ones are slower than searching without them."
    }

    private fun heading(text: String) = JLabel(text).apply { putClientProperty("FlatLaf.styleClass", "h4") }

    override fun applyTo(settings: Settings) = settings

    /** The launcher's options with this page's changes; null without the packaged launcher or if nothing changed. */
    fun options(): List<String>? {
        val vmOptions = vmOptions ?: return null
        val current = vmOptions.current()
        var options = current
        val limit = xmx.text.trim()
        if (limit != initialLimit) {
            options = VmOptions.withMemoryLimit(options, limit.ifEmpty { null }, vmOptions.defaults)
        }
        // A disabled checkbox shows what this computer can do, not a choice to save.
        if (vectors.isEnabled && vectors.isSelected != initialVectors) {
            options = VmOptions.withVectorSearch(options, vectors.isSelected)
        }
        return options.takeIf { it != current }
    }
}
