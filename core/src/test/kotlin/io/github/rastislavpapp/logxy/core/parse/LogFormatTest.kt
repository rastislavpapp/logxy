package io.github.rastislavpapp.logxy.core.parse

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

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
    fun `guesses a layout that no built-in format reads`() {
        val file = Files.writeString(dir.resolve("b.log"), "2026-10-01 08:00:00.001 INFO  [main] com.example.App - Started\n")
        assertEquals(
            LogFormat.Pattern("date LEVEL [thread] logger - message", "%d{yyyy-MM-dd HH:mm:ss.SSS} %p [%t] %c - %m%n"),
            LogFormat.detect(file),
        )
    }

    @Test
    fun `guessed JSON keys replace the built-in JSON format`() {
        val file = Files.writeString(dir.resolve("j.log"), "{\"when\":\"2026-10-01T08:00:00Z\",\"lvl\":\"info\",\"msg\":\"Started\"}\n")
        assertEquals("JSON lines: when, lvl", LogFormat.detect(file).name)
    }

    @Test
    fun `detects a file again once it changed`() {
        val file = Files.writeString(dir.resolve("c.log"), "2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started\n")
        assertEquals(LogFormat.BUILT_IN[0], LogFormat.detect(file))
        assertEquals(LogFormat.BUILT_IN[0], LogFormat.detect(file))
        Files.writeString(file, "{\"@timestamp\":\"2026-10-01T08:00:00Z\",\"level\":\"INFO\",\"message\":\"Started\"}\n")
        assertEquals(LogFormat.Json("JSON lines"), LogFormat.detect(file))
    }

    @Test
    fun `a built-in format wins a tie with the guess`() {
        val file = Files.writeString(dir.resolve("t.log"), "08:00:00.001 [main] INFO  com.example.App - Started\n")
        assertEquals(LogFormat.BUILT_IN[1], LogFormat.detect(file))
    }

    @Test
    fun `detects a format whose first record is followed by a huge indented dump`() {
        val file = Files.writeString(dir.resolve("d.log"), "2026-10-01 08:00:00.001 [main] INFO  a.B - xml:\n" +
            (1..5000).joinToString("") { "    <item id=\"$it\"/>\n" })
        assertEquals(LogFormat.BUILT_IN[0], LogFormat.detect(file))
    }

    @Test
    fun `the user's format wins a tie with a built-in one`() {
        val file = Files.writeString(dir.resolve("e.log"), "2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started\n")
        val mine = LogFormat.Pattern("Mine", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n")
        assertEquals(mine, LogFormat.detect(file, preferred = listOf(mine)))
    }

    @Test
    fun `detects Spring Boot layouts`() {
        val boot2 = Files.writeString(dir.resolve("boot2.log"),
            "2026-10-08 12:00:00.123  INFO 12345 --- [           main] c.e.demo.DemoApplication                 : Started\n")
        assertEquals("date LEVEL pid --- [thread] logger : message", LogFormat.detect(boot2).name)
        val boot3 = Files.writeString(dir.resolve("boot3.log"),
            "2026-10-08T12:00:00.123+02:00  WARN 12345 --- [           main] c.e.demo.DemoApplication                 : Slow\n")
        assertEquals("Spring Boot 3", LogFormat.detect(boot3).name)
        val boot3named = Files.writeString(dir.resolve("boot3n.log"),
            "2026-10-08T12:00:00.123+02:00  WARN 12345 --- [demo] [           main] c.e.demo.DemoApplication                 : Slow\n" +
                "2026-10-08T12:00:00.124+02:00  INFO 12345 --- [demo] [  restartedMain] c.e.demo.DemoApplication                 : Up\n")
        assertEquals("date LEVEL pid --- [field] [thread] logger : message", LogFormat.detect(boot3named).name)
    }

    @Test
    fun `preview shows fields of matching lines`() {
        val lines = listOf("2026-10-01 08:00:00.001 [main] INFO  a.B - hello", "\tat x.Y(Y.java:1)", "nonsense")
            .map { it.toByteArray() }
        val preview = FormatPreview.preview(LogFormat.BUILT_IN[0].compile()!!, lines)
        assertEquals(listOf("2026-10-01 08:00:00.001", "main", "INFO", "a.B", "hello"), preview[0].values)
        assertEquals(null to true, preview[1].values to preview[1].continuation)
        assertEquals(null to false, preview[2].values to preview[2].continuation)
    }

    @Test
    fun `falls back to raw text`() {
        val file = Files.writeString(dir.resolve("c.log"), "hello\nworld\n")
        assertEquals(LogFormat.Raw, LogFormat.detect(file))
    }

    @Test
    fun `detects a log4j2 layout with the MDC map`() {
        val file = Files.writeString(dir.resolve("mdc.log"), """
            2026-10-01 08:00:00.001 [main] INFO  com.example.App {} - Started
            2026-10-01 08:00:00.002 [http-1] INFO  com.example.Api {breadcrumbId=ID-1, tenant=acme} - GET /a - done
        """.trimIndent())
        assertEquals("date [thread] LEVEL logger {MDC} - message", LogFormat.detect(file).name)
    }

    @Test
    fun `tells whether a format fits a file before opening it`() {
        val file = Files.writeString(dir.resolve("m.log"), "2026-10-01 08:00:00.001 [main] INFO  a.B - hello\n")
        assertEquals(null, LogFormat.BUILT_IN[0].mismatch(file))
        assertEquals(null, LogFormat.Raw.mismatch(file))
        assertTrue(LogFormat.Json("JSON").mismatch(file)!!.startsWith("No entry in the file matches the parser."))
        assertEquals("The pattern has no fields", LogFormat.Pattern("Broken", "just text").mismatch(file))
        // A start the first bytes cannot decide passes.
        val dump = Files.writeString(dir.resolve("dump.log"), (1..400_000).joinToString("") { "    <item id=\"$it\"/>\n" })
        assertEquals(null, LogFormat.Json("JSON").mismatch(dump))
    }
}
