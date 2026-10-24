package io.github.rastislavpapp.logxy.plugin

import com.intellij.openapi.util.Key
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A run tab's console or its logxy [view], as the Console | logxy switch in the tab's toolbar picks them; [memory]
 * keeps the choice for the next run. While the view shows, its actions take the place of the console's [toolbar].
 * A debug tab has no switch: its view is a sub-tab of its own.
 */
class ConsoleSwitch(
    console: JComponent,
    private val toolbar: ConsoleToolbar,
    private val view: RunLogView,
    private val memory: RunMemory,
) : JPanel(CardLayout()) {

    private val cards = layout as CardLayout

    var showsLogxy = memory.showsLogxy
        private set

    init {
        add(console, CONSOLE)
        add(view, LOGXY)
        showView(showsLogxy)
    }

    /** Shows logxy, or the console. */
    fun showView(logxy: Boolean) {
        showsLogxy = logxy
        memory.showsLogxy = logxy
        cards.show(this, if (logxy) LOGXY else CONSOLE)
        toolbar.show(if (logxy) view.actions else null, view)
    }

    companion object {
        private const val CONSOLE = "console"
        private const val LOGXY = "logxy"

        /** The switch of a run, on its process handler, where the run tab's top toolbar finds it. */
        val KEY = Key.create<ConsoleSwitch>("logxy.switch")
    }
}
