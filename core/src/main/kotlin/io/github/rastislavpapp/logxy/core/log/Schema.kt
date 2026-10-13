package io.github.rastislavpapp.logxy.core.log

import io.github.rastislavpapp.logxy.core.parse.MapSyntax

/** How a field's values are stored, see [ParsedLog]. */
enum class ColumnType {
    /** Epoch nanoseconds. */
    TIMESTAMP,

    /** Index into a small dictionary of level texts, each mapped to a [Level]. */
    LEVEL,

    /** Index into a dictionary of distinct values; for low-cardinality text. */
    DICT,

    /** Position of the text in the file; for high-cardinality text such as messages. */
    SPAN,
}

/** What a field means, independent of the parser it came from. */
enum class Role { TIMESTAMP, LEVEL, MESSAGE, THREAD, LOGGER, EXCEPTION, SOURCE }

/** A field of a log's records: a column of the table unless [hidden], named [name], stored as [type]. */
class Field internal constructor(
    val name: String,
    val type: ColumnType,
    val role: Role? = null,
    /** For SPAN fields: every value runs to the end of its record (e.g. a message with its stack trace). */
    internal val toRecordEnd: Boolean = false,
    /** Shown through other fields instead of as a column, e.g. an MDC map split into its keys. */
    val hidden: Boolean = false,
    /** For a SPAN holding a whole map, e.g. an MDC map or a JSON object, how to read its entries. */
    internal val map: MapSyntax? = null,
    /** For a field taken from a map: the map's field and the entry's key. Its values are read from the map. */
    internal val mapKey: MapKey? = null,
    /** Values are JSON values: strings are quoted and escaped, see [io.github.rastislavpapp.logxy.core.parse.Json]. */
    internal val json: Boolean = false,
    /** Hidden if no record has a value, e.g. a role that the keys of a JSON log do not fill. */
    internal val optional: Boolean = false,
) {
    internal val isMap get() = map != null

    /**
     * This field stored as another [type] (a DICT field turned SPAN), or [hidden] changed: shown once a record has an
     * optional field, hidden while none has it or when a map is split into its keys.
     */
    internal fun copy(type: ColumnType = this.type, hidden: Boolean = this.hidden) =
        Field(name, type, role, toRecordEnd, hidden, map, mapKey, json, optional)
}

/** A key of a map field; see [Field.mapKey]. */
class MapKey internal constructor(internal val field: Int, val key: String) {
    internal val bytes = key.toByteArray()

    /** `key=`, as an MDC entry starts. */
    internal val prefix = "$key=".toByteArray()
}

/** The fields of a log's records, in column order; a field's index is how [Columns] and readers address it. */
class Schema(val fields: List<Field>) {

    /** The index of the first field with [role], or -1 if none has it. */
    fun indexOf(role: Role): Int = fields.indexOfFirst { it.role == role }

    companion object {
        /** Raw text: one field, the whole line. */
        val RAW = Schema(listOf(Field("Line", ColumnType.SPAN, Role.MESSAGE)))
    }
}
