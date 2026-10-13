package io.github.rastislavpapp.logxy.core.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParserInferenceTest {

    private fun infer(text: String) = ParserInference.infer(text.trimIndent().lines().map { it.toByteArray() })

    private fun pattern(text: String) = (infer(text) as Parser.Pattern).pattern

    /** Field name to value of each line that starts a record, as the inferred parser reads it. */
    private fun read(text: String): List<Map<String, String>> {
        val lines = text.trimIndent().lines().map { it.toByteArray() }
        val compiled = ParserInference.infer(lines)!!.compile()!!
        val names = compiled.schema.fields.map { it.name }
        return ParserPreview.preview(compiled, lines).mapNotNull { line -> line.values?.let { names.zip(it).toMap() } }
    }

    @Test
    fun `log4j layout with thread, level and logger`() {
        val text = """
            2026-10-01 08:00:00.001 [main] INFO  com.example.App - Started
            2026-10-01 08:00:00.002 [http-nio-8080-exec-1] ERROR com.example.Api - Failed - badly
            java.lang.IllegalStateException: boom
            	at com.example.App.main(App.java:10)
            2026-10-01 08:00:01.003 [main] DEBUG com.example.App - Done
        """
        assertEquals("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %p %c - %m%n", pattern(text))
        assertEquals("date [thread] LEVEL logger - message", infer(text)!!.name)
        val second = read(text)[1]
        assertEquals("http-nio-8080-exec-1", second["Thread"])
        assertEquals("ERROR", second["Level"])
        assertEquals("com.example.Api", second["Logger"])
        assertEquals("Failed - badly", second["Message"]!!.lines().first())
    }

    @Test
    fun `level before thread, and logger in brackets`() {
        assertEquals("%d{yyyy-MM-dd HH:mm:ss.SSS} %p [%t] %c - %m%n", pattern("""
            2026-10-01 08:00:00.001 INFO  [main] com.example.App - Started
            2026-10-01 08:00:00.002 WARN  [worker-2] com.example.Job - Slow
        """))
        assertEquals("%d{yyyy-MM-dd HH:mm:ss.SSS} %p [%c] (%t) %m%n", pattern("""
            2026-10-01 08:00:00.001 INFO  [com.example.App] (main) Started
            2026-10-01 08:00:00.002 WARN  [com.example.Job] (worker-2) Slow
        """))
    }

    @Test
    fun `Spring Boot layouts`() {
        val boot2 = """
            2026-10-08 12:00:00.123  INFO 12345 --- [           main] c.e.demo.DemoApplication                 : Started
            2026-10-08 12:00:00.456 ERROR 12345 --- [nio-8080-exec-1] o.a.c.c.C.Tomcat                         : Failed: x
        """
        assertEquals("%d{yyyy-MM-dd HH:mm:ss.SSS} %5p %pid --- [%t] %c : %m%n", pattern(boot2))
        assertEquals("Failed: x", read(boot2)[1]["Message"])
        val boot3named = """
            2026-10-08T12:00:00.123+02:00  WARN 12345 --- [demo] [           main] c.e.demo.DemoApplication                 : Slow
            2026-10-08T12:00:00.124+02:00  INFO 12345 --- [demo] [  restartedMain] c.e.demo.DemoApplication                 : Up
        """
        assertEquals(
            "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p %pid --- [%X{Field 1}] [%t] %c : %m%n",
            pattern(boot3named),
        )
        assertEquals("restartedMain", read(boot3named)[1]["Thread"])
    }

    @Test
    fun `MDC map and key=value columns`() {
        val map = """
            2026-10-01 08:00:00.001 [main] INFO  com.example.App {} - Started
            2026-10-01 08:00:00.002 [http-1] ERROR com.example.Api {breadcrumbId=ID-1, tenant=acme} - GET /a - done
        """
        assertEquals("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %p %15c %X - %m%n", pattern(map))
        assertEquals(listOf("com.example.App", "com.example.Api"), read(map).map { it["Logger"] })
        assertEquals("{breadcrumbId=ID-1, tenant=acme}", read(map)[1]["MDC"])
        val text = """
            2026-10-01 08:00:00,001 INFO user=alice com.example.App - Started
            2026-10-01 08:00:00,002 WARN user=bob com.example.Api - Slow
        """
        assertEquals("%d{yyyy-MM-dd HH:mm:ss,SSS} %p user=%X{user} %c - %m%n", pattern(text))
        assertEquals("bob", read(text)[1]["user"])
    }

    @Test
    fun `pipe separated columns`() {
        val text = """
            2026-10-01T08:00:00Z | INFO | main | com.example.App | Started | ok
            2026-10-01T08:00:01Z | ERROR | pool-1-thread-2 | com.example.Api | Failed
        """
        assertEquals("%d{yyyy-MM-dd'T'HH:mm:ssXXX} | %p | %t | %c | %m%n", pattern(text))
        assertEquals("Started | ok", read(text)[0]["Message"])
    }

    @Test
    fun `python logging layout`() {
        val text = """
            2026-10-01 08:00:00,123 INFO myapp.server: Listening on 8080
            2026-10-01 08:00:01,456 WARNING myapp.db: Slow query
        """
        assertEquals("%d{yyyy-MM-dd HH:mm:ss,SSS} %p %c: %m%n", pattern(text))
    }

    @Test
    fun `level and message only, with the timestamp in brackets`() {
        assertEquals("[%d{yyyy-MM-dd HH:mm:ss}] %p %m%n", pattern("""
            [2026-10-01 08:00:00] INFO Starting
            [2026-10-01 08:00:01] ERROR Could not connect to db
        """))
    }

    @Test
    fun `month names and epoch milliseconds`() {
        assertEquals("%d{dd MMM yyyy HH:mm:ss,SSS} %p [%t] %m%n", pattern("""
            01 Oct 2026 08:00:00,001 INFO [main] Started
            01 Oct 2026 08:00:00,002 WARN [main] Slow
        """))
        assertEquals("%d{UNIX_MILLIS} %p %m%n", pattern("""
            1759305600123 INFO Started
            1759305600456 ERROR Failed
        """))
    }

    @Test
    fun `no level`() {
        assertEquals("%d{HH:mm:ss.SSS} [%t] %m%n", pattern("""
            08:00:00.001 [main] Starting the server
            08:00:00.002 [worker] Accepted a connection
        """))
    }

    @Test
    fun `no timestamp, level first`() {
        assertEquals("[%p] %m%n", pattern("""
            [INFO] Scanning for projects...
            [INFO] Building app 1.0
            [WARNING] Deprecated API
        """))
    }

    @Test
    fun `JSON lines with keys of their own`() {
        val parser = infer("""
            {"when":"2026-10-01T08:00:00.001Z","lvl":"info","text":"Started the application","svc":"api","src":"com.example.App"}
            {"when":"2026-10-01T08:00:00.002Z","lvl":"error","text":"Failed to connect to the database","svc":"api","src":"com.example.Db"}
        """) as Parser.Json
        assertEquals("JSON lines: when, lvl, text, src", parser.name)
        assertEquals(listOf("when"), parser.keys.timestamp.take(1))
        assertEquals(listOf("lvl"), parser.keys.level.take(1))
        assertEquals(listOf("text"), parser.keys.message.take(1))
        assertEquals(listOf("src"), parser.keys.logger.take(1))
    }

    @Test
    fun `JSON lines with the usual keys`() {
        assertEquals(Parser.Json("JSON lines"), infer("""
            {"@timestamp":"2026-10-01T08:00:00.001Z","level":"INFO","message":"Started"}
            {"time":1759305600123,"level":30,"msg":"pino"}
        """))
    }

    @Test
    fun `trace ids`() {
        val text = """
            2026-10-01 08:00:00.001 INFO  [main] [0085d56c176215c3a2e13730c53f314b-25ae18aeba775129] com.example.App - Started
            2026-10-01 08:00:00.002 WARN  [main] [644e8c5a5dddb08241637080ece543a2-39a3fa60f1ce3492] com.example.App - Slow
        """
        assertEquals("%d{yyyy-MM-dd HH:mm:ss.SSS} %p [%t] [%X{Trace}] %c - %m%n", pattern(text))
    }

    @Test
    fun `nothing for syslog, whose level does not lead the line and whose date has no year`() {
        assertNull(infer("""
            Oct 12 09:00:00 shop-1 shop-api[4242]: INFO Started
            Oct 12 09:00:01 shop-1 shop-api[4242]: WARN Slow
        """))
    }

    @Test
    fun `nothing for text without structure`() {
        assertNull(infer("""
            hello world
            this is just some text
        """))
    }
}
