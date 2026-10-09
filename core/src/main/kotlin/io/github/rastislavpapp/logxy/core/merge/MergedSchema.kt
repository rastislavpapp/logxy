package io.github.rastislavpapp.logxy.core.merge

import io.github.rastislavpapp.logxy.core.log.ColumnType
import io.github.rastislavpapp.logxy.core.log.Field
import io.github.rastislavpapp.logxy.core.log.Role
import io.github.rastislavpapp.logxy.core.log.Schema

/**
 * The fields of several logs in one schema: a source field naming the file, then each part's fields,
 * matched by role, else by name (ignoring case) and type. Unmatched fields get their own column.
 */
internal class MergedSchema(parts: List<Schema>) {

    val schema: Schema

    /** For each part, the part's field index for each merged field, or -1. */
    private val partFields: List<IntArray>

    init {
        val fields = arrayListOf(Field(SOURCE_NAME, ColumnType.DICT, Role.SOURCE))
        val mappings = parts.map { part ->
            val mapping = HashMap<Int, Int>()
            for ((index, field) in part.fields.withIndex()) {
                val existing = fields.indices.firstOrNull { merged ->
                    merged !in mapping && fields[merged].type == field.type &&
                        if (field.role != null) fields[merged].role == field.role
                        else fields[merged].role == null && fields[merged].name.equals(field.name, ignoreCase = true)
                }
                val merged = existing ?: fields.size.also { fields += Field(uniqueName(field.name, fields), field.type, field.role) }
                mapping[merged] = index
            }
            mapping
        }
        schema = Schema(fields)
        partFields = mappings.map { mapping -> IntArray(fields.size) { mapping[it] ?: -1 } }
    }

    /** The field of [part] shown in merged [field], or -1 if the part has none there. */
    fun partField(part: Int, field: Int): Int = partFields[part][field]

    private fun uniqueName(name: String, fields: List<Field>): String =
        (sequenceOf(name) + generateSequence(2) { it + 1 }.map { "$name $it" })
            .first { candidate -> fields.none { it.name.equals(candidate, ignoreCase = true) } }

    companion object {
        const val SOURCE = 0
        const val SOURCE_NAME = "Source"
    }
}
