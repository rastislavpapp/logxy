package io.github.rastislavpapp.logxy.core.parse

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LogFormatTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `detects the matching built-in format`() {
        val file = Files.writeString(dir.resolve("a.log"), """
            2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started
            2026-10-01 08:00:00.002 [main] ERROR com.example.App - Failed
            java.lang.IllegalStateException: boom
            	at com.example.App.main(App.java:10)
        """.trimIndent())
        assertEquals(LogFormat.BUILT_IN[0], LogFormat.detect(file))
    }

    @Test
    fun `prefers the more specific of two matching formats`() {
        val file = Files.writeString(dir.resolve("b.log"), "2026-10-01 08:00:00.001 INFO  [main] com.example.App - Started\n")
        assertEquals(LogFormat.BUILT_IN[1], LogFormat.detect(file))
    }

    @Test
    fun `detects a format whose first record is followed by a huge indented dump`() {
        val file = Files.writeString(dir.resolve("d.log"), "2026-10-01 08:00:00.001 [main] INFO  a.B - xml:\n" +
            (1..5000).joinToString("") { "    <item id=\"$it\"/>\n" })
        assertEquals(LogFormat.BUILT_IN[0], LogFormat.detect(file))
    }

    @Test
    fun `falls back to raw text`() {
        val file = Files.writeString(dir.resolve("c.log"), "hello\nworld\n")
        assertEquals(LogFormat.Raw, LogFormat.detect(file))
    }
}
