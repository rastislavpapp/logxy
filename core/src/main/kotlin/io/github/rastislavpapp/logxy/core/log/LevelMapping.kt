package io.github.rastislavpapp.logxy.core.log

/** Maps level texts, ignoring case and surrounding spaces, to [Level]; unknown texts map to [Level.UNKNOWN]. */
class LevelMapping(names: Map<String, Level>) {

    val names: Map<String, Level> = names.mapKeys { normalize(it.key) }

    fun of(text: String): Level = names[normalize(text)] ?: Level.UNKNOWN

    override fun equals(other: Any?) = other is LevelMapping && other.names == names

    override fun hashCode() = names.hashCode()

    companion object {
        val DEFAULT = LevelMapping(Level.BUILT_IN_NAMES)

        fun normalize(text: String) = text.trim().uppercase()
    }
}
