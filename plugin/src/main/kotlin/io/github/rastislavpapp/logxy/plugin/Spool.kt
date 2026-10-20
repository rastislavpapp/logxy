package io.github.rastislavpapp.logxy.plugin

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Key
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// CSI sequences (colours, cursor moves); ColoredProcessHandler already decodes them, a plain OSProcessHandler does not.
private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
private val UNSAFE_IN_NAME = Regex("[^A-Za-z0-9._-]")

/**
 * A run's stdout and stderr, in arrival order, without ANSI codes and IDE system lines, appended to [path]. The
 * process reads its streams on separate threads, so writes are serialized; [onGrew] and [onExit] run on those threads.
 */
class Spool private constructor(val path: Path) : ProcessListener {

    private val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

    @Volatile var onGrew: () -> Unit = {}

    @Volatile var onExit: (exitCode: Int) -> Unit = {}

    /** The exit code once the process ended, else null. */
    @Volatile var exitCode: Int? = null
        private set

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (!ProcessOutputType.isStdout(outputType) && !ProcessOutputType.isStderr(outputType)) return
        val bytes = ByteBuffer.wrap(ANSI.replace(event.text, "").toByteArray())
        synchronized(this) {
            if (!channel.isOpen) return
            while (bytes.hasRemaining()) channel.write(bytes)
        }
        onGrew()
    }

    override fun processTerminated(event: ProcessEvent) {
        synchronized(this) { channel.close() }
        exitCode = event.exitCode
        onExit(event.exitCode)
    }

    companion object {
        /** A fresh file for a run of [configuration], deleting the files of its earlier runs. */
        fun start(configuration: String): Spool {
            val dir = Files.createDirectories(PathManager.getSystemDir().resolve("logxy/runs"))
            val name = configuration.replace(UNSAFE_IN_NAME, "_").take(MAX_NAME)
            val hash = Integer.toHexString(configuration.hashCode())
            val key = "$name-$hash"
            val all = Files.list(dir).use { files -> files.toList() }
            val earlier = all.filter { "${it.fileName}".startsWith("$key-") }
            // An earlier run's tab may still read its file; on Windows that file cannot go yet.
            for (file in earlier) runCatching { Files.delete(file) }
            return Spool(dir.resolve("$key-${System.currentTimeMillis()}.log"))
        }

        private const val MAX_NAME = 60
    }
}
