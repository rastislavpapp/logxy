package io.github.rastislavpapp.logxy.core.log

/** Maps level texts, ignoring case and surrounding spaces, to [Level]; unknown texts map to [Level.UNKNOWN]. */
class LevelMapping(names: Map<String, Level>) {

    val names: Map<String, Level> = names.mapKeys { normalize(it.key) }

    /** The level [text] names, or [Level.UNKNOWN]. */
    fun of(text: String): Level = names[normalize(text)] ?: Level.UNKNOWN

    override fun equals(other: Any?) = other is LevelMapping && other.names == names

    override fun hashCode() = names.hashCode()

    companion object {
        /** The built-in level names, as used until the user edits them. */
        val DEFAULT = LevelMapping(Level.BUILT_IN_NAMES)

        /** [text] as the keys of [names] are stored: trimmed and upper-case, so `warn ` and `WARN` are one name. */
        fun normalize(text: String) = text.trim().uppercase()
    }
}
