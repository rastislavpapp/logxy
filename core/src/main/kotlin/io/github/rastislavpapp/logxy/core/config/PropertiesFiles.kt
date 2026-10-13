package io.github.rastislavpapp.logxy.core.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Properties

/** Reads and writes the `.properties` files logxy keeps its settings and state in. */
object PropertiesFiles {

    /** The properties in [path]; empty if the file does not exist. */
    fun load(path: Path): Properties = Properties().apply {
        if (Files.exists(path)) Files.newBufferedReader(path).use { load(it) }
    }

    /** Writes [properties] to [path] with sorted keys, replacing the file only once the new one is complete. */
    fun store(path: Path, properties: Properties, comment: String) {
        val sorted = SortedProperties().apply { putAll(properties) }
        val directory = path.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "logxy", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { sorted.store(it, comment) }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** Writes keys in sorted order, so the file stays readable and diffs stay small. */
private class SortedProperties : Properties() {
    override val entries: MutableSet<MutableMap.MutableEntry<Any, Any>>
        get() = Collections.synchronizedSet(sortedSetOf(compareBy { it.key.toString() }, *super.entries.toTypedArray()))
}
