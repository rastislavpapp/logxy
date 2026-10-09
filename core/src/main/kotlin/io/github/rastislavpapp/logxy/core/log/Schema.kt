package io.github.rastislavpapp.logxy.core.log

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

/** What a field means, independent of the format it came from. */
enum class Role { TIMESTAMP, LEVEL, MESSAGE, THREAD, LOGGER, EXCEPTION, SOURCE }

class Field(
    val name: String,
    val type: ColumnType,
    val role: Role? = null,
    /** For SPAN fields: every value runs to the end of its record (e.g. a message with its stack trace). */
    internal val toRecordEnd: Boolean = false,
)

class Schema(val fields: List<Field>) {

    fun indexOf(role: Role): Int = fields.indexOfFirst { it.role == role }

    companion object {
        val RAW = Schema(listOf(Field("Line", ColumnType.SPAN, Role.MESSAGE)))
    }
}
