package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LevelMapping
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompiledJsonTest {

    @TempDir
    lateinit var dir: Path

    private fun entries(json: String): List<Pair<String, String>>? {
        val bytes = json.toByteArray()
        val found = ArrayList<Pair<String, String>>()
        val valid = Json.forEachEntry(bytes, 0, bytes.size) { kFrom, kTo, vFrom, vTo ->
            found += String(bytes, kFrom, kTo - kFrom) to String(bytes, vFrom, vTo - vFrom)
        }
        return if (valid) found else null
    }

    @Test
    fun `finds the top-level entries of an object`() {
        assertEquals(
            listOf(
                "a" to "1", "b" to "\"x \\\" } y\"", "c" to "{\"d\":[1,{\"e\":\"]\"}]}", "f" to "true", "g" to "null",
                "h\\\"" to "-1.5e3", "i" to "[]",
            ),
            entries(""" { "a" : 1, "b":"x \" } y", "c":{"d":[1,{"e":"]"}]},"f":true,"g":null,"h\"":-1.5e3,"i":[] } """),
        )
        assertEquals(emptyList(), entries("{}"))
    }

    @Test
    fun `rejects what is no object`() {
        for (text in listOf("", "plain text", "[1,2]", "{\"a\":1", "{\"a\" 1}", "{\"a\":1,}", "{a:1}", "{\"a\":1} trailing", "{\"a\":\"open}")) {
            assertNull(entries(text), text)
        }
    }

    @Test
    fun `decodes strings and leaves other values as written`() {
        fun text(value: String) = value.toByteArray().let { Json.text(it, 0, it.size) }
        assertEquals("line 1\nline 2\tž \"q\" \\ / é 😀", text(""""line 1\nline 2\tž \"q\" \\ \/ é 😀""""))
        assertEquals("lone � and bad \\x", text(""""lone \ud800 and bad \x""""))
        assertEquals("cut at the end", text(""""cut at the end"""))
        assertEquals("{\"a\": 1}", text("""{"a": 1}"""))
        assertEquals("42", text("42"))
    }

    @Test
    fun `pretty-prints an object with strings unescaped`() {
        val json = """{"a":1,"msg":"two\nlines","nested":{"list":[1,"x",{}],"empty":[]}}"""
        val bytes = json.toByteArray()
        assertEquals(
            """
            {
              "a": 1,
              "msg": "two
            lines",
              "nested": {
                "list": [
                  1,
                  "x",
                  {}
                ],
                "empty": []
              }
            }
            """.trimIndent(),
            Json.pretty(bytes, 0, bytes.size),
        )
        assertNull(Json.pretty("not json".toByteArray(), 0, 8))
        assertNull("""{"a" 1}""".toByteArray().let { Json.pretty(it, 0, it.size) })
    }

    private fun nanos(text: String, zone: ZoneId = ZoneOffset.UTC): Long? {
        val parser = JsonTimestampParser(zone)
        val bytes = text.toByteArray()
        return if (parser.parse(bytes, 0, bytes.size)) parser.nanos else null
    }

    private fun epochNanos(iso: String): Long = Instant.parse(iso).let { it.epochSecond * 1_000_000_000 + it.nano }

    @Test
    fun `parses ISO-8601 timestamps`() {
        assertEquals(epochNanos("2026-10-01T08:00:00.123Z"), nanos("2026-10-01T08:00:00.123Z"))
        assertEquals(epochNanos("2026-10-01T06:00:00.123456789Z"), nanos("2026-10-01T08:00:00.123456789+02:00"))
        assertEquals(epochNanos("2026-10-01T10:30:00Z"), nanos("2026-10-01 08:00:00-0230"))
        assertEquals(epochNanos("2026-10-01T07:00:00Z"), nanos("2026-10-01T08:00+01"))
        assertEquals(epochNanos("2026-10-01T06:00:05.5Z"), nanos("2026-10-01T08:00:05,5", ZoneId.of("Europe/Prague")))
        // The cached minute must not leak into the next timestamp's offset.
        assertEquals(epochNanos("2026-10-01T08:00:01Z"), nanos("2026-10-01T08:00:01Z"))
        for (bad in listOf("2026-10-01", "2026-13-01T08:00:00Z", "2026-10-01T08:00:00X", "2026-10-01T08:00:00.Z", "2026-10-01T08:00:00+2")) {
            assertNull(nanos(bad), bad)
        }
    }

    @Test
    fun `parses epoch numbers of any unit`() {
        val expected = epochNanos("2026-10-01T08:00:00.123Z")
        assertEquals(expected, nanos("1790841600.123"))
        assertEquals(expected, nanos("1790841600123"))
        assertEquals(expected, nanos("1790841600123000"))
        assertEquals(expected, nanos("1790841600123000000"))
        assertEquals(epochNanos("2026-10-01T08:00:00Z"), nanos("1790841600"))
        assertNull(nanos("12abc"))
        assertNull(nanos("-"))
    }

    @Test
    fun `reads roles from the first key of each list that a record has`() {
        val parser = CompiledJson(RoleKeys(message = listOf("message", "msg")), ZoneOffset.UTC)
        val lines = listOf(
            """{"msg":"short","message":"long","level":"WARN","@timestamp":"2026-10-01T08:00:00.123Z","logger_name":"a.B"}""",
            """not json""",
        ).map { it.toByteArray() }
        val preview = ParserPreview.preview(parser, lines)
        val names = parser.schema.fields.map { it.name }
        val values = preview[0].values!!
        assertEquals("long", values[names.indexOf("Message")])
        assertEquals("WARN", values[names.indexOf("Level")])
        assertEquals("2026-10-01 08:00:00.123", values[names.indexOf("Timestamp")])
        assertEquals("a.B", values[names.indexOf("Logger")])
        assertEquals(null to false, preview[1].values to preview[1].continuation)
    }

    @Test
    fun `reads Docker, Serilog and GELF records with the default keys`() {
        val parser = CompiledJson(zone = ZoneOffset.UTC)
        val names = parser.schema.fields.map { it.name }
        fun read(line: String, field: String) =
            ParserPreview.preview(parser, listOf(line.toByteArray()))[0].values!![names.indexOf(field)]

        val docker = """{"log":"Started\n","stream":"stdout","time":"2026-10-01T08:00:00.123456789Z"}"""
        assertEquals("Started\n", read(docker, "Message"))
        assertEquals("2026-10-01 08:00:00.123", read(docker, "Timestamp"))

        val serilog = """{"@t":"2026-10-01T08:00:00.123Z","@mt":"{User} logged in","@m":"\"alice\" logged in","@l":"Warning","@x":"System.Exception: boom","User":"alice"}"""
        assertEquals("\"alice\" logged in", read(serilog, "Message"))
        assertEquals("2026-10-01 08:00:00.123", read(serilog, "Timestamp"))
        assertEquals("Warning", read(serilog, "Level"))
        assertEquals("System.Exception: boom", read(serilog, "Exception"))
        assertEquals("{User} logged in", read("""{"@t":"2026-10-01T08:00:00Z","@mt":"{User} logged in"}""", "Message"))

        val gelf = """{"version":"1.1","host":"web-1","short_message":"Failed","full_message":"Failed\nat x","timestamp":1790841600.1234,"level":3}"""
        assertEquals("Failed", read(gelf, "Message"))
        assertEquals("Failed\nat x", read(gelf, "Exception"))
        assertEquals("2026-10-01 08:00:00.123", read(gelf, "Timestamp"))
        assertEquals("3", read(gelf, "Level"))
    }

    @Test
    fun `GELF levels are syslog severities`() {
        val levels = LevelMapping.DEFAULT
        assertEquals(
            listOf(Level.FATAL, Level.FATAL, Level.FATAL, Level.ERROR, Level.WARN, Level.INFO, Level.INFO, Level.DEBUG),
            (0..7).map { levels.of(it.toString()) },
        )
    }

    @Test
    fun `detects JSON lines`() {
        val file = Files.writeString(dir.resolve("app.json"), """
            {"@timestamp":"2026-10-01T08:00:00.123Z","level":"INFO","message":"Started"}
            {"@timestamp":"2026-10-01T08:00:00.124Z","level":"ERROR","message":"Failed","stack_trace":"x\n\tat y"}
        """.trimIndent())
        assertEquals("JSON lines", Parser.detect(file).name)
        assertTrue(Parser.BUILT_IN.last() is Parser.Json)
        assertFalse(CompiledJson().multiLine)
    }
}
