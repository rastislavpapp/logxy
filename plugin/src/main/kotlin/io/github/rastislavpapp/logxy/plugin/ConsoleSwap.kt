package io.github.rastislavpapp.logxy.plugin

import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * The one place that knows how a run tab is built: the console is the content [ExecutionConsole.CONSOLE_CONTENT_ID]
 * of the tab's layout, shown in a grid cell's wrapper panel. The cell does not follow a change of the content's
 * component, so the wrapper takes the console's place in that panel too.
 */
object ConsoleSwap {

    /**
     * Wraps the console of [descriptor] in what [wrap] makes of it, which must hold the console; false if the tab has
     * no console content, or it is not shown yet.
     */
    fun install(descriptor: RunContentDescriptor, wrap: (console: JComponent) -> JComponent): Boolean {
        val content = descriptor.runnerLayoutUi?.findContent(ExecutionConsole.CONSOLE_CONTENT_ID) ?: return false
        val console = content.component ?: return false
        val parent = console.parent ?: return false
        val index = parent.components.indexOf(console)
        val constraints = (parent.layout as? BorderLayout)?.getConstraints(console)
        // Taking the console in removes it from the cell.
        val wrapper = wrap(console)
        parent.add(wrapper, constraints, index)
        parent.revalidate()
        content.component = wrapper
        return true
    }
}
