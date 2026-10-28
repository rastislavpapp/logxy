package io.github.rastislavpapp.logxy.core.parse

import kotlin.test.Test
import kotlin.test.assertEquals

class PrettyPrintTest {

    @Test
    fun `indents a JSON-lines record and unescapes its strings`() {
        val record = """{"level":"ERROR","stack_trace":"boom\n\tat Db.query","context":{"items":[1,2]}}"""
        val expected = listOf(
            "{",
            "  \"level\": \"ERROR\",",
            "  \"stack_trace\": \"boom",
            "\tat Db.query\",",
            "  \"context\": {",
            "    \"items\": [",
            "      1,",
            "      2",
            "    ]",
            "  }",
            "}",
        )
        assertEquals(expected.joinToString("\n"), PrettyPrint.text(record))
    }

    @Test
    fun `keeps empty objects and arrays on one line`() {
        val record = """{"a":1,"nested":{"list":[1,"x",{}],"empty":[]}}"""
        assertEquals(
            """
            {
              "a": 1,
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
            PrettyPrint.text(record),
        )
    }

    @Test
    fun `indents a JSON payload in a message, keeping the text around it`() {
        val record = """2026-10-01 08:00:00.001 INFO [main] Sent {"id":7,"note":"a\nb"} in 3 ms"""
        val expected = "2026-10-01 08:00:00.001 INFO [main] Sent {\n  \"id\": 7,\n  \"note\": \"a\nb\"\n} in 3 ms"
        assertEquals(expected, PrettyPrint.text(record))
    }

    @Test
    fun `leaves what is no well-formed JSON as written`() {
        val records = listOf(
            "a lone { here",
            "[main] index [0] out of range, ids [1, 2]",
            """bad {"a" 1} and {"b":tru} and {"c":01} and {"d":1,}""",
            """unclosed {"a":[1""",
            "Map{a=1, b=[x]}",
            "{}",
        )
        for (record in records) assertEquals(record, PrettyPrint.text(record))
    }

    @Test
    fun `finds a payload inside a bracket that is none`() {
        assertEquals("{ x {\n  \"a\": 1\n} }", PrettyPrint.text("""{ x {"a":1} }"""))
    }

    @Test
    fun `leaves a payload over the size cap as written`() {
        val big = """{"a":"${"x".repeat(PrettyPrint.MAX_PAYLOAD_BYTES)}","b":{"c":1}}"""
        assertEquals("big $big", PrettyPrint.text("big $big"))
    }

    @Test
    fun `stays fast on many brackets that open no payload`() {
        val record = "{[".repeat(500_000)
        assertEquals(record, PrettyPrint.text(record))
    }

    @Test
    fun `indents an XML payload in a message, keeping the text around it`() {
        val record = """INFO Reply: <env:Body a="1 > 0"><ns:item id='2'>x &amp; y</ns:item><empty/></env:Body> sent"""
        val expected = """
            INFO Reply: <env:Body a="1 > 0">
              <ns:item id='2'>x &amp; y</ns:item>
              <empty/>
            </env:Body> sent
        """.trimIndent()
        assertEquals(expected, PrettyPrint.text(record))
    }

    @Test
    fun `keeps XML comments, CDATA and processing instructions as written`() {
        val record = "<a>\n  <?pi x?><!-- <b> --><c><![CDATA[<not/> a tag]]></c> text </a>"
        val expected = """
            <a>
              <?pi x?>
              <!-- <b> -->
              <c>
                <![CDATA[<not/> a tag]]>
              </c>
              text
            </a>
        """.trimIndent()
        assertEquals(expected, PrettyPrint.text(record))
    }

    @Test
    fun `indents XML with a DOCTYPE without resolving its entities`() {
        val record = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY e SYSTEM "file:///etc/passwd">]><a><b>&e;</b></a>"""
        val expected = """
            <?xml version="1.0"?><!DOCTYPE a [<!ENTITY e SYSTEM "file:///etc/passwd">]><a>
              <b>&e;</b>
            </a>
        """.trimIndent()
        assertEquals(expected, PrettyPrint.text(record))
    }

    @Test
    fun `leaves what is no well-formed XML as written`() {
        val records = listOf(
            "if a < b then",
            "List<String> and Map<String, List<Integer>>",
            "<a><b></a></b>",
            "<a><b/>",
            "<a x=1></a>",
            "<a>1 < 2</a>",
        )
        for (record in records) assertEquals(record, PrettyPrint.text(record))
    }

    @Test
    fun `leaves an XML payload over the size cap as written`() {
        val big = "<a><b>${"x".repeat(PrettyPrint.MAX_PAYLOAD_BYTES)}</b><c/></a>"
        assertEquals("big $big", PrettyPrint.text("big $big"))
    }

    @Test
    fun `stays fast on many XML openers that never close`() {
        val record = "<!--<![CDATA[<?<!DOCTYPE <a ".repeat(40_000)
        assertEquals(record, PrettyPrint.text(record))
    }

    @Test
    fun `keeps characters beyond ASCII`() {
        assertEquals("ž <a>\n  <č>é</č>\n</a>", PrettyPrint.text("ž <a><č>é</č></a>"))
        assertEquals("ž {\n  \"č\": \"😀\"\n}", PrettyPrint.text("""ž {"č":"😀"}"""))
    }

    @Test
    fun `lays out the payload that starts first where two overlap`() {
        val record = """{"body":"<a><b/></a>"}"""
        assertEquals("{\n  \"body\": \"<a><b/></a>\"\n}", PrettyPrint.text(record))
    }

    @Test
    fun `indents each of several payloads, arrays too`() {
        val record = """a=[{"x":1}] b={"y":[]}"""
        assertEquals("a=[\n  {\n    \"x\": 1\n  }\n] b={\n  \"y\": []\n}", PrettyPrint.text(record))
    }
}
