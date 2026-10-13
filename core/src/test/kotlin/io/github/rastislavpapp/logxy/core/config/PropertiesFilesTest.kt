package io.github.rastislavpapp.logxy.core.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertiesFilesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `missing file loads as empty`() {
        assertTrue(PropertiesFiles.load(dir.resolve("missing.properties")).isEmpty)
    }

    @Test
    fun `store replaces the file with sorted keys and leaves no temporary file`() {
        val file = dir.resolve("nested/state.properties")
        PropertiesFiles.store(file, Properties().apply { this["old"] = "1" }, "state")
        val properties = Properties().apply {
            this["b"] = "2"
            this["a"] = "1"
            this["c"] = "x = y"
        }
        PropertiesFiles.store(file, properties, "state")

        assertEquals(properties, PropertiesFiles.load(file))
        assertEquals(listOf("a", "b", "c"), Files.readAllLines(file).filterNot { it.startsWith("#") }.map { it.substringBefore('=') })
        assertEquals(listOf("state.properties"), Files.list(file.parent).map { it.fileName.toString() }.toList())
    }
}
