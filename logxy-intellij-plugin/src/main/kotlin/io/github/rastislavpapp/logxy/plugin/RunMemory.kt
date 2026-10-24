package io.github.rastislavpapp.logxy.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * What a run configuration's view keeps from run to run, in the project's properties: whether a run tab shows logxy
 * or the console, the hidden columns and the widths they were dragged to, the columns shared by Run and Debug. Not the
 * filter: a run starts showing all its records. [id] names the configuration.
 */
class RunMemory(project: Project, id: String) {

    private val properties = PropertiesComponent.getInstance(project)
    private val prefix = "logxy.run.$id."
    private val logxyKey = "${prefix}logxy"
    private val hiddenKey = "${prefix}hidden"
    private val widthsKey = "${prefix}widths"

    var showsLogxy: Boolean
        get() = properties.getBoolean(logxyKey)
        set(value) = properties.setValue(logxyKey, value)

    var hiddenColumns: Set<String>
        get() = properties.getList(hiddenKey).orEmpty().toSet()
        set(value) = properties.setList(hiddenKey, value.toList())

    /**
     * The widths in pixels the user dragged columns to, by column name. Stored as `width:name`, the width first, as a
     * name may hold a colon and the width cannot.
     */
    var columnWidths: Map<String, Int>
        get() = properties.getList(widthsKey).orEmpty().mapNotNull { entry ->
            val width = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            entry.substringAfter(':') to width
        }.toMap()
        set(value) = properties.setList(widthsKey, value.map { (name, width) -> "$width:$name" })
}
