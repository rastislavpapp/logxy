package io.github.rastislavpapp.logxy.core.log

/** Maps level texts to [Level]: the user's [aliases] first, then the built-in names of [Level.of]. */
class LevelMapping(aliases: Map<String, Level> = emptyMap()) {

    val aliases: Map<String, Level> = aliases.mapKeys { normalize(it.key) }

    fun of(text: String): Level = aliases[normalize(text)] ?: Level.of(text)

    override fun equals(other: Any?) = other is LevelMapping && other.aliases == aliases

    override fun hashCode() = aliases.hashCode()

    companion object {
        val DEFAULT = LevelMapping()

        fun normalize(text: String) = text.trim().uppercase()
    }
}
