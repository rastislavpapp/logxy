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

/** Copies every run's output into a [Spool] and puts a [LogxyConsole] around the run tab's console. */
class RunCapture(private val project: Project) : ExecutionListener {

    // Before the process starts, so the spool gets its first output too.
    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val spool = Spool.start(env.runProfile.name)
        handler.putUserData(SPOOL, spool)
        handler.addProcessListener(spool)
        log.info("logxy: spooling ${env.runProfile.name} into ${spool.path}")
    }

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
        val installed = ConsoleSwap.install(descriptor) { console ->
            LogxyConsole(project, env.runProfile.name, console, spool).also { Disposer.register(descriptor, it) }
        }
        log.info("logxy: ${if (installed) "wrapped" else "found no console content in"} ${env.runProfile.name}")
    }
}
