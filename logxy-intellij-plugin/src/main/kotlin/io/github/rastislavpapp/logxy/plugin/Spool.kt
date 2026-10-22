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

private val UNSAFE_IN_NAME = Regex("[^A-Za-z0-9._-]")
private const val MAX_NAME = 60

/**
 * A run's stdout and stderr, in arrival order, without ANSI codes and IDE system lines (the command line, "Process
 * finished"), appended to [path]. The process's streams are read on threads of their own, so writes are serialized;
 * [onGrew] and [onExit] run on those threads.
 */
class Spool private constructor(val path: Path) : ProcessListener {

    private val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

    // Per stream: a code split across chunks of one stream must not swallow the other's text.
    private val stdout = AnsiStripper()
    private val stderr = AnsiStripper()

    @Volatile var onGrew: () -> Unit = {}

    @Volatile var onExit: (exitCode: Int) -> Unit = {}

    /** Line breaks written so far. */
    @Volatile var lines = 0
        private set

    /** The exit code once the process ended, else null. */
    @Volatile var exitCode: Int? = null
        private set

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val stripper = when {
            ProcessOutputType.isStdout(outputType) -> stdout
            ProcessOutputType.isStderr(outputType) -> stderr
            else -> return
        }
        synchronized(this) {
            if (!channel.isOpen) return
            val text = stripper.strip(event.text)
            val bytes = ByteBuffer.wrap(text.toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            lines += text.count { it == '\n' }
        }
        onGrew()
    }

    /** Empties the file, as Clear All empties a console; what the process writes next starts it again. */
    fun clear() {
        synchronized(this) {
            if (channel.isOpen) {
                channel.truncate(0)
            } else {
                FileChannel.open(path, StandardOpenOption.WRITE).use { it.truncate(0) }
            }
            lines = 0
        }
        onGrew()
    }

    override fun processTerminated(event: ProcessEvent) {
        synchronized(this) { channel.close() }
        exitCode = event.exitCode
        onExit(event.exitCode)
    }

    companion object {
        /**
         * A fresh file for a run of the configuration [id] names, in the IDE's system directory, deleting the files
         * of its earlier runs.
         */
        fun start(id: String): Spool {
            val dir = Files.createDirectories(PathManager.getSystemDir().resolve("logxy/runs"))
            val name = id.replace(UNSAFE_IN_NAME, "_").take(MAX_NAME)
            val key = "$name-${Integer.toHexString(id.hashCode())}"
            val all = Files.list(dir).use { files -> files.toList() }
            val earlier = all.filter { "${it.fileName}".startsWith("$key-") }
            // An earlier run's view may still read its file; on Windows that file cannot go yet.
            for (file in earlier) runCatching { Files.delete(file) }
            return Spool(dir.resolve("$key-${System.currentTimeMillis()}.log"))
        }
    }
}
