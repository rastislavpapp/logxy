package io.github.rastislavpapp.logxy.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.github.rastislavpapp.logxy.core.config.SavedFilter
import io.github.rastislavpapp.logxy.core.log.Level

/**
 * What a run configuration's view keeps from run to run, in the project's properties: whether it shows logxy or the
 * console, the filter and the hidden columns. [id] names the configuration.
 */
class RunMemory(project: Project, id: String) {

    private val properties = PropertiesComponent.getInstance(project)
    private val prefix = "logxy.run.$id."
    private val logxyKey = "${prefix}logxy"
    private val queryKey = "${prefix}filter.query"
    private val levelKey = "${prefix}filter.level"
    private val hiddenKey = "${prefix}hidden"

    var showsLogxy: Boolean
        get() = properties.getBoolean(logxyKey)
        set(value) = properties.setValue(logxyKey, value)

    /** The filter of the last run, null if it had none. */
    var filter: SavedFilter?
        get() {
            val query = properties.getValue(queryKey) ?: return null
            val levelName = properties.getValue(levelKey)
            val level = Level.entries.find { it.name == levelName }
            return SavedFilter("", level, query)
        }
        set(value) {
            properties.setValue(queryKey, value?.query)
            properties.setValue(levelKey, value?.minimumLevel?.name)
        }

    var hiddenColumns: Set<String>
        get() = properties.getList(hiddenKey).orEmpty().toSet()
        set(value) = properties.setList(hiddenKey, value.toList())
}
