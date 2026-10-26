package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.parse.Parser
import io.github.rastislavpapp.logxy.core.query.Query
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.query.and
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Insets
import java.awt.Window
import java.nio.file.Path
import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The filter bar of a host without the filter's text form, as in the IDE plugin. */
class FilterBarTest {

    private val host = VisualOnlyHost()
    private var applied = 0

    @BeforeTest
    fun installLook() {
        Look.current = PlainLook
    }

    @Test
    fun `a saved filter that does not parse is not applied`() = onUiThread {
        val bar = FilterBar({ applied++ }, ColumnNames(), host)

        bar.load(SavedFilter("broken", null, "message == "))

        assertEquals(0, applied)
        assertEquals("", bar.current().query)
    }

    @Test
    fun `a saved filter shows in the visual editor and is applied`() = onUiThread {
        val bar = FilterBar({ applied++ }, ColumnNames(), host)

        bar.load(SavedFilter("slow", null, "message == \"*timeout*\""))

        assertEquals(1, applied)
        assertEquals("message == \"*timeout*\"", bar.current().query)
    }

    @Test
    fun `a renamed field keeps its conditions without applying them`() = onUiThread {
        val bar = FilterBar({ applied++ }, ColumnNames(), host)
        bar.load(SavedFilter("orders", null, "Line == \"*Order*\""))

        bar.renameField("Line", "Message")

        assertEquals(1, applied)
        assertEquals("Message == \"*Order*\"", bar.current().query)
    }

    @Test
    fun `a remembered Advanced mode does not hide the visual editor`() = onUiThread {
        host.state[UiState.FILTER_MODE] = "advanced"
        val bar = FilterBar({ applied++ }, ColumnNames(), host)

        bar.edit { it.and(Query.Rule("logger", QueryOp.EQUALS, "db")) }

        assertEquals("logger == \"db\"", bar.current().query)
    }

    private fun onUiThread(test: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { failure = runCatching(test).exceptionOrNull() }
        failure?.let { throw it }
    }
}

private class VisualOnlyHost : LogViewHost {
    override val settings = object : WritableSettings {
        override var current = Settings()
        override fun updateOrShowError(parent: Component, settings: Settings): Boolean {
            current = settings
            return true
        }
        override fun addListener(listener: () -> Unit) {}
        override fun removeListener(listener: () -> Unit) {}
    }
    override val state = object : UiState {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
    override val uiDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override fun detailPane(): DetailPane? = null
    override fun queryText(onApply: () -> Unit): QueryText? = null
    override val settingsDialogs: SettingsDialogs? = null
    override fun chooseParsersPerFile(
        owner: Window?,
        paths: List<Path>,
        names: List<String>,
        descriptions: List<String>,
        current: List<Parser?>,
    ): List<Parser?>? = null
    override val moreMemoryHint: String? = null
}

/** Plain Swing values, enough for components to be made and laid out. */
private object PlainLook : Look {
    override fun icon(path: String, size: Int): Icon = ImageIcon()
    override fun scale(value: Int) = value
    override fun scale(value: Float) = value
    override val accent: Color = Color.BLUE
    override val error: Color = Color.RED
    override val warning: Color = Color.ORANGE
    override val errorText: Color = Color.RED
    override val border: Color = Color.GRAY
    override val monospacedFont = Font(Font.MONOSPACED, Font.PLAIN, 12)
    override val smallFont = Font(Font.SANS_SERIF, Font.PLAIN, 10)
    override val cellMargins = Insets(0, 0, 0, 0)
    override fun flat(button: AbstractButton) {}
    override fun link(button: AbstractButton) {}
    override fun accentToggle(text: String, icon: Icon?) = JToggleButton(text, icon)
    override fun trailingComponent(field: JTextField, component: JComponent) {}
    override fun leadingIcon(field: JTextField, icon: Icon) {}
    override fun placeholder(field: JTextField, text: String) {}
    override fun outlineError(component: JComponent, error: Boolean) {}
    override fun heading(label: JLabel) {}
    override fun expandableBottomOnly(split: JSplitPane) {}
    override fun keepMenuOpen(item: JMenuItem) {}
}
