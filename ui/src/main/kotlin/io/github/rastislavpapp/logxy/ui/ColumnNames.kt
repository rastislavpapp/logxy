package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.log.Field

/**
 * The names the columns show: a rename for this view only, else the alias saved with the parser, else the field's
 * own name. Filters keep using the fields' own names. Used on the EDT only.
 */
class ColumnNames {

    /** The aliases saved with the parser: field name to the name shown. */
    var saved: Map<String, String> = emptyMap()

    // A field renamed back to its own name maps to itself, so its saved alias is not shown either.
    private val renamed = HashMap<String, String>()

    fun of(field: Field): String = of(field.name)

    fun of(field: String): String = renamed[field] ?: saved[field] ?: field

    /** Shows [field] as [name] in this view only. */
    fun rename(field: String, name: String) {
        renamed[field] = name
    }

    /** Shows [field] as [name] in [saved], ready to be saved with the parser; drops its rename for this view. */
    fun renameSaved(field: String, name: String) {
        renamed -= field
        saved = if (name == field) saved - field else saved + (field to name)
    }

    /** Another of [fields] that [name] would be mistaken for, by its own name or the one it shows; case is ignored. */
    fun clash(field: Field, name: String, fields: List<Field>): Field? = fields.firstOrNull {
        it != field && (it.name.equals(name, ignoreCase = true) || of(it).equals(name, ignoreCase = true))
    }
}
