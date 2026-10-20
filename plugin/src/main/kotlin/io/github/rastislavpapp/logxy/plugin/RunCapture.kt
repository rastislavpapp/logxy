package io.github.rastislavpapp.logxy.plugin

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

private val SPOOL = Key.create<Spool>("logxy.spool")
private val log = logger<RunCapture>()

/** Copies every run's output into a [Spool] and puts a [RunLogView] around the run tab's console. */
class RunCapture(private val project: Project) : ExecutionListener {

    // Before the process starts, so the spool gets its first output too.
    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val spool = Spool.start(configurationId(env))
        handler.putUserData(SPOOL, spool)
        handler.addProcessListener(spool)
    }

    // The run tab exists once the process started, but is shown only after this listener returns.
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val spool = handler.getUserData(SPOOL) ?: return
        ApplicationManager.getApplication().invokeLater({ install(env, handler, spool) }, project.disposed)
    }

    private fun install(env: ExecutionEnvironment, handler: ProcessHandler, spool: Spool) {
        val descriptor = RunContentManager.getInstance(project).allDescriptors
            .firstOrNull { it.processHandler === handler }
        if (descriptor == null) {
            log.warn("logxy: no run tab for ${env.runProfile.name}")
            return
        }
        val memory = RunMemory(project, configurationId(env))
        // Disposed with the tab, e.g. when a rerun replaces it.
        val installed = ConsoleSwap.install(descriptor) { console ->
            RunLogView(console, spool, memory).also { Disposer.register(descriptor, it) }
        }
        if (!installed) log.warn("logxy: found no console in the run tab of ${env.runProfile.name}")
    }

    /** Stays with the configuration across runs: its type and name, or only the name of a run without settings. */
    private fun configurationId(env: ExecutionEnvironment): String =
        env.runnerAndConfigurationSettings?.uniqueID ?: env.runProfile.name
}
