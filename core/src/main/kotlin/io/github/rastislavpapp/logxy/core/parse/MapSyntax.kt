package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.MapKey

/** How to read a field that holds a whole map, so that each of its keys can be shown as a field. */
internal interface MapSyntax {

    /** More distinct keys than this, e.g. ids used as keys, and the map stays one field. */
    val maxKeys: Int

    /** Whether values are JSON values, see [Json]. */
    val json: Boolean

    /** The range of the entries in `bytes[from, to)`, packed as by [MdcMap.pack]; -1 if it is not a map. */
    fun entries(bytes: ByteArray, from: Int, to: Int): Long

    /** The value range of [key] among the [entries] in `bytes[from, to)`, packed; -1 if absent. */
    fun value(bytes: ByteArray, from: Int, to: Int, key: MapKey): Long

    /** Whether [key] gets a field of its own. */
    fun splits(key: String): Boolean = true

    /** Name of the field for [key] of the map field [map]. */
    fun fieldName(map: String, key: String): String
}
