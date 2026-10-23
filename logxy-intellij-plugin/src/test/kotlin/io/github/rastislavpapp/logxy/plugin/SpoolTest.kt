package io.github.rastislavpapp.logxy.plugin

import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.fileSize
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SpoolTest {

    private val dir = createTempDirectory("spools")

    @AfterTest
    fun cleanUp() {
        Files.walk(dir).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `a live run's spool stays when the configuration starts again`() {
        val run = Spool.start("Application.Busy app", dir)
        val debug = Spool.start("Application.Busy app", dir)

        assertEquals(setOf(run.path, debug.path), spools())
        end(run)
        end(debug)
    }

    @Test
    fun `an ended run's spool goes when the configuration starts again`() {
        val first = Spool.start("Application.Busy app", dir)
        first.onTextAvailable(ProcessEvent(NopProcessHandler(), "INFO started\n"), ProcessOutputType.STDOUT)
        end(first)

        val second = Spool.start("Application.Busy app", dir)

        assertEquals(setOf(second.path), spools())
        assertEquals(0L, second.path.fileSize())
        end(second)
    }

    private fun spools() = Files.list(dir).use { files -> files.toList().toSet() }

    private fun end(spool: Spool) = spool.processTerminated(ProcessEvent(NopProcessHandler(), 0))
}
