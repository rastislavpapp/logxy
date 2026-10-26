package io.github.rastislavpapp.logxy.plugin

import com.intellij.execution.ExecutionListener
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RemoteRunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentWithExecutorListener
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

private val SPOOL = Key.create<Spool>("logxy.spool")
private val log = logger<RunCapture>()

/**
 * Copies the output of every Run and Debug into a [Spool] and shows a [RunLogView] of it as the last sub-tab of the
 * run or debug tab. Not of a Debug that attaches to a process running elsewhere (Remote JVM Debug): it has no output
 * of its own.
 */
class RunCapture(private val project: Project) : ExecutionListener {

    // Before the process starts, so the spool gets its first output too.
    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        capture(env, handler)
    }

    private fun capture(env: ExecutionEnvironment, handler: ProcessHandler): Spool? {
        if (env.runProfile is RemoteRunProfile) return null
        val spool = Spool.start(configurationId(env))
        log.info("logxy: the output of ${env.runProfile.name} goes to ${spool.path}")
        handler.putUserData(SPOOL, spool)
        handler.addProcessListener(spool)
        return spool
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int,
    ) {
        val spool = handler.getUserData(SPOOL) ?: return
        log.info("logxy: ${env.runProfile.name} ended with exit code $exitCode, its output in ${spool.path}")
    }

    // The run tab exists once the process started, but is shown only after this listener returns. A debug tab can
    // come later: in 2026.2 the debugger builds it in the background, and it shows once built.
    // The IDE leaves out processStarting for a handler that is already started: PyCharm's debugpy debugger starts it
    // while it builds the tab. Its program starts after that, so the spool still gets the first output.
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val spool = handler.getUserData(SPOOL)
            ?: capture(env, handler)?.also { log.info("logxy: ${env.runProfile.name} was started early, caught now") }
            ?: return
        ApplicationManager.getApplication().invokeLater({ installWhenShown(env, handler, spool) }, project.disposed)
    }

    /** Installs the view in the tab of [handler] now if it is there, else once it shows, while the process runs. */
    private fun installWhenShown(env: ExecutionEnvironment, handler: ProcessHandler, spool: Spool) {
        val descriptor = RunContentManager.getInstance(project).allDescriptors
            .firstOrNull { it.processHandler === handler }
        if (descriptor != null) return install(env, handler, spool, descriptor)
        if (handler.isProcessTerminated) return log.warn("logxy: no tab showed for ${env.runProfile.name} while it ran")
        val waiting = Disposer.newDisposable(project, "logxy: waiting for the tab of ${env.runProfile.name}")
        handler.addProcessListener(
            object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) = Disposer.dispose(waiting)
            },
            waiting,
        )
        project.messageBus.connect(waiting).subscribe(
            RunContentManager.TOPIC,
            object : RunContentWithExecutorListener {
                override fun contentSelected(descriptor: RunContentDescriptor?, executor: Executor) {
                    if (descriptor?.processHandler !== handler) return
                    Disposer.dispose(waiting)
                    install(env, handler, spool, descriptor)
                }

                override fun contentRemoved(descriptor: RunContentDescriptor?, executor: Executor) {}
            },
        )
    }

    private fun install(
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        spool: Spool,
        descriptor: RunContentDescriptor,
    ) {
        val memory = RunMemory(project, configurationId(env))
        // Disposed with the tab, e.g. when a rerun replaces it; the tab's toolbar finds it on the handler.
        val view = RunLogView(spool, memory, IdeHost(project))
        Disposer.register(descriptor, view)
        val debug = env.executor.id == DefaultDebugExecutor.EXECUTOR_ID
        val place = if (debug) ActionPlaces.DEBUGGER_TOOLBAR else ActionPlaces.RUNNER_TOOLBAR
        val content = RunTabLayout.addSubTab(descriptor, view, view.actions, place)
            ?: return log.warn("logxy: found no layout in the tab of ${env.runProfile.name}")
        handler.putUserData(RunLogView.KEY, view)
        // A debug tab opens on the debugger's own sub-tabs, which a breakpoint selects again anyway.
        if (!debug) RunTabLayout.followSelection(descriptor, content, memory.showsLogxy) { memory.showsLogxy = it }
    }

    /** Stays with the configuration across runs: its type and name, or only the name of a run without settings. */
    private fun configurationId(env: ExecutionEnvironment): String =
        env.runnerAndConfigurationSettings?.uniqueID ?: env.runProfile.name
}
