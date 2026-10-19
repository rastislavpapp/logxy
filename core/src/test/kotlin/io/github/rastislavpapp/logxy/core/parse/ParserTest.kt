package io.github.rastislavpapp.logxy.core.parse

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `detects the matching built-in parser`() {
        val file = Files.writeString(dir.resolve("a.log"), """
            2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started
            2026-10-01 08:00:00.002 [main] ERROR com.example.App - Failed
            java.lang.IllegalStateException: boom
            	at com.example.App.main(App.java:10)
        """.trimIndent())
        assertEquals(Parser.BUILT_IN[0], Parser.detect(file))
    }

    @Test
    fun `guesses a layout that no built-in parser reads`() {
        val file =
            Files.writeString(dir.resolve("b.log"), "2026-10-01 08:00:00.001 INFO  [main] com.example.App - Started\n")
        assertEquals(
            Parser.Pattern("Detected parser", "%d{yyyy-MM-dd HH:mm:ss.SSS} %p [%t] %c - %m%n"),
            Parser.detect(file),
        )
    }

    @Test
    fun `guessed JSON keys replace the built-in JSON parser`() {
        val line = "{\"when\":\"2026-10-01T08:00:00Z\",\"lvl\":\"info\",\"msg\":\"Started\"}\n"
        val file = Files.writeString(dir.resolve("j.log"), line)
        assertEquals("JSON lines: when, lvl", Parser.detect(file).name)
    }

    @Test
    fun `detects a file again once it changed`() {
        val file =
            Files.writeString(dir.resolve("c.log"), "2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started\n")
        assertEquals(Parser.BUILT_IN[0], Parser.detect(file))
        assertEquals(Parser.BUILT_IN[0], Parser.detect(file))
        val json = "{\"@timestamp\":\"2026-10-01T08:00:00Z\",\"level\":\"INFO\",\"message\":\"Started\"}\n"
        Files.writeString(file, json)
        assertEquals(Parser.Json("JSON lines"), Parser.detect(file))
    }

    @Test
    fun `a built-in parser wins a tie with the guess`() {
        val file = Files.writeString(dir.resolve("t.log"), "08:00:00.001 [main] INFO  com.example.App - Started\n")
        assertEquals(Parser.BUILT_IN[1], Parser.detect(file))
    }

    @Test
    fun `detects a parser whose first record is followed by a huge indented dump`() {
        val file = Files.writeString(dir.resolve("d.log"), "2026-10-01 08:00:00.001 [main] INFO  a.B - xml:\n" +
            (1..5000).joinToString("") { "    <item id=\"$it\"/>\n" })
        assertEquals(Parser.BUILT_IN[0], Parser.detect(file))
    }

    @Test
    fun `the user's parser wins a tie with a built-in one`() {
        val file =
            Files.writeString(dir.resolve("e.log"), "2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started\n")
        val mine = Parser.Pattern("Mine", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n")
        assertEquals(mine, Parser.detect(file, preferred = listOf(mine)))
    }

    @Test
    fun `detects Spring Boot layouts`() {
        // Spring Boot pads the logger to 40 characters.
        val app = "c.e.demo.DemoApplication".padEnd(40)
        val boot2 = Files.writeString(
            dir.resolve("boot2.log"),
            "2026-10-08 12:00:00.123  INFO 12345 --- [           main] $app : Started\n",
        )
        assertEquals("Detected parser", Parser.detect(boot2).name)
        val boot3 = Files.writeString(
            dir.resolve("boot3.log"),
            "2026-10-08T12:00:00.123+02:00  WARN 12345 --- [           main] $app : Slow\n",
        )
        assertEquals("Spring Boot 3", Parser.detect(boot3).name)
        val boot3named = Files.writeString(
            dir.resolve("boot3n.log"),
            "2026-10-08T12:00:00.123+02:00  WARN 12345 --- [demo] [           main] $app : Slow\n" +
                "2026-10-08T12:00:00.124+02:00  INFO 12345 --- [demo] [  restartedMain] $app : Up\n",
        )
        assertEquals("Detected parser", Parser.detect(boot3named).name)
    }

    @Test
    fun `detects Python's default logging layout`() {
        val file = Files.writeString(dir.resolve("app.py.log"), """
            2026-10-13 10:00:00,123 - app.db - WARNING - slow query
            2026-10-13 10:00:00,456 - app.web - CRITICAL - down
            Traceback (most recent call last):
              File "app.py", line 1, in <module>
            2026-10-13 10:00:01,002 - root - INFO - ok
        """.trimIndent())
        val parser = Parser.detect(file)
        assertEquals("Python logging", parser.name)
        val preview = ParserPreview.preview(parser.compile()!!, Files.readAllLines(file).map { it.toByteArray() })
        assertEquals(listOf("2026-10-13 10:00:00,123", "app.db", "WARNING", "slow query"), preview[0].values)
        assertEquals(null, preview[2].values)
    }

    @Test
    fun `detects logfmt`() {
        val file = Files.writeString(dir.resolve("go.log"), """
            time=2026-10-13T10:00:00Z level=info msg="listening" addr=:8080
            time=2026-10-13T10:00:01Z level=warn msg="slow request" path=/api duration=2.1s
        """.trimIndent())
        assertEquals(Parser.Logfmt("logfmt"), Parser.detect(file))
    }

    @Test
    fun `detects access logs, common and combined`() {
        val common = Files.writeString(dir.resolve("access.log"), """
            203.0.113.7 - - [13/Oct/2026:10:00:00 +0200] "GET / HTTP/1.1" 200 512
            203.0.113.8 - bob [13/Oct/2026:10:00:01 +0200] "POST /login HTTP/1.1" 302 -
        """.trimIndent())
        assertEquals(Parser.AccessLog("Access log"), Parser.detect(common))
        val combined = Files.writeString(dir.resolve("access-combined.log"), """
            203.0.113.7 - - [13/Oct/2026:10:00:00 +0200] "GET / HTTP/1.1" 200 512 "-" "curl/8.0"
            203.0.113.8 - - [13/Oct/2026:10:00:01 +0200] "GET /a HTTP/2.0" 404 0 "https://example.com/" "Mozilla/5.0"
        """.trimIndent())
        assertEquals(Parser.AccessLog("Access log"), Parser.detect(combined))
    }

    @Test
    fun `a pattern log whose messages hold key=value pairs is not logfmt`() {
        val file = Files.writeString(dir.resolve("kv.log"), """
            2026-10-01 08:00:00.001 [main] INFO  com.example.App - user=alice action=login result=ok
            2026-10-01 08:00:00.002 [main] WARN  com.example.App - user=bob action=login result=denied
        """.trimIndent())
        assertEquals(Parser.BUILT_IN[0], Parser.detect(file))
        val lines =
            listOf("user=alice action=login", "a=1", "hello world a=1 b=2", " a=1 b=2", "a=\"open b=2", "\"a\"=1 b=2")
        val matcher = Parser.Logfmt("logfmt").compile().newMatcher()
        val matched = lines.map { it.toByteArray().let { b -> matcher.match(b, 0, b.size) } }
        assertEquals(listOf(true, false, false, false, false, false), matched)
    }

    @Test
    fun `preview shows fields of matching lines`() {
        val lines = listOf("2026-10-01 08:00:00.001 [main] INFO  a.B - hello", "\tat x.Y(Y.java:1)", "nonsense")
            .map { it.toByteArray() }
        val preview = ParserPreview.preview(Parser.BUILT_IN[0].compile()!!, lines)
        assertEquals(listOf("2026-10-01 08:00:00.001", "main", "INFO", "a.B", "hello"), preview[0].values)
        assertEquals(null to true, preview[1].values to preview[1].continuation)
        assertEquals(null to false, preview[2].values to preview[2].continuation)
    }

    @Test
    fun `falls back to raw text`() {
        val file = Files.writeString(dir.resolve("c.log"), "hello\nworld\n")
        assertEquals(Parser.Raw, Parser.detect(file))
    }

    @Test
    fun `detects a log4j2 layout with the MDC map`() {
        val file = Files.writeString(dir.resolve("mdc.log"), """
            2026-10-01 08:00:00.001 [main] INFO  com.example.App {} - Started
            2026-10-01 08:00:00.002 [http-1] INFO  com.example.Api {breadcrumbId=ID-1, tenant=acme} - GET /a - done
        """.trimIndent())
        assertEquals("Detected parser", Parser.detect(file).name)
    }

    @Test
    fun `tells whether a parser fits a file before opening it`() {
        val file = Files.writeString(dir.resolve("m.log"), "2026-10-01 08:00:00.001 [main] INFO  a.B - hello\n")
        assertEquals(null, Parser.BUILT_IN[0].mismatch(file))
        assertEquals(null, Parser.Raw.mismatch(file))
        assertTrue(Parser.Json("JSON").mismatch(file)!!.startsWith("No entry in the file matches the parser."))
        assertEquals("The pattern has no fields", Parser.Pattern("Broken", "just text").mismatch(file))
        // A start the first bytes cannot decide passes.
        val items = (1..400_000).joinToString("") { "    <item id=\"$it\"/>\n" }
        val dump = Files.writeString(dir.resolve("dump.log"), items)
        assertEquals(null, Parser.Json("JSON").mismatch(dump))
    }
}
