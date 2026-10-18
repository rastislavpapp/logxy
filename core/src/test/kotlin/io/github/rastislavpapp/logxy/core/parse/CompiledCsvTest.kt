package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompiledCsvTest {

    @TempDir
    lateinit var dir: Path

    /** The cells of [line] split at [separator] as shown, or null if it is no valid row. */
    private fun cells(line: String, separator: Char = ','): List<String>? {
        val bytes = line.toByteArray()
        val starts = IntArray(Csv.MAX_COLUMNS + 1)
        val ends = IntArray(Csv.MAX_COLUMNS + 1)
        val count = Csv.cells(bytes, 0, bytes.size, separator.code.toByte(), starts, ends)
        if (count < 0) return null
        return List(count) { String(Csv.decode(bytes, starts[it], ends[it])) }
    }

    private fun infer(text: String, csv: Boolean = true) =
        ParserInference.infer(text.trimIndent().lines().map { it.toByteArray() }, csv)

    private fun csv(text: String) = infer(text) as Parser.Csv

    @Test
    fun `splits at the separator and keeps empty cells`() {
        assertEquals(listOf("a", "b", "c"), cells("a,b,c"))
        assertEquals(listOf("a", "", "c", ""), cells("a,,c,"))
        assertEquals(listOf("a", "b,c"), cells("a;b,c", separator = ';'))
        assertEquals(listOf(" a ", " b"), cells(" a , b"))
    }

    @Test
    fun `separators inside quotes belong to the cell`() {
        assertEquals(listOf("Smith, John", "42"), cells("\"Smith, John\",42"))
        assertEquals(listOf("say \"hi\"", "x"), cells("\"say \"\"hi\"\"\",x"))
        assertEquals(listOf("", "x"), cells("\"\",x"))
        // A quote that does not start a cell is text.
        assertEquals(listOf("5\" pipe", "x"), cells("5\" pipe,x"))
    }

    @Test
    fun `rejects a quote that does not close or text after the closing one`() {
        assertNull(cells("a,\"b,c"))
        assertNull(cells("\"ab\"cd,e"))
        assertNull(cells("\"a\" ,b"))
    }

    @Test
    fun `infers the separator and the column names from a header`() {
        val parser = csv("""
            id,name,"note, with comma"
            1,Ann,"hello, world"
            2,Bob,
        """)
        assertEquals(Parser.Csv("CSV (,)", ',', listOf("id", "name", "note, with comma")), parser)
        val semicolons = csv("""
            id;name;price
            1;Ann;3,50
            2;Bob;4,00
        """)
        assertEquals(Parser.Csv("CSV (;)", ';', listOf("id", "name", "price")), semicolons)
    }

    @Test
    fun `quoted cells read without their quotes, also when cut`() {
        fun decode(cell: String) = String(Csv.decode(cell.toByteArray(), 0, cell.length))
        assertEquals("a \"b\" c", decode("\"a \"\"b\"\" c\""))
        assertEquals("plain \"\"", decode("plain \"\""))
        assertEquals("cut", decode("\"cut"))
        assertEquals("", decode("\"\""))
    }

    @Test
    fun `the header is the first line that is not blank`() {
        assertEquals(listOf("a", "b"), ParserInference.infer(listOf(" ", "a,b", "1,2").map { it.toByteArray() }, true)
            .let { (it as Parser.Csv).headers })
    }

    @Test
    fun `a header alone is enough, and a byte order mark is not part of its first name`() {
        assertEquals(listOf("a", "b"), csv("a,b").headers)
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\"a\",b".toByteArray()
        assertEquals(listOf("a", "b"), (ParserInference.infer(listOf(bytes), csv = true) as Parser.Csv).headers)
    }

    @Test
    fun `one row with another number of cells is no CSV`() {
        assertNull(infer("""
            a,b,c
            1,2,3
            1,2
            1,2,3
        """))
        assertNull(infer("""
            a,b
            1,2,3
        """))
        assertNull(infer("""
            a,b
            "1,2
        """))
    }

    @Test
    fun `an implausible header is no CSV`() {
        assertNull(infer("only one column\nmore"))
        assertNull(infer("a,,c\n1,2,3"))
        assertNull(infer("a,b,a\n1,2,3"))
        assertNull(infer("1,2,3\n4,5,6"))
        assertNull(infer((1..Csv.MAX_COLUMNS + 1).joinToString(",") { "c$it" }))
        assertEquals(Csv.MAX_COLUMNS, csv((1..Csv.MAX_COLUMNS).joinToString(",") { "c$it" }).headers.size)
    }

    @Test
    fun `only files that look like CSV are tried`() {
        assertNull(infer("a,b,c\n1,2,3", csv = false))
    }

    @Test
    fun `reads the cells of a row as the columns, and a line that is no row whole in the first`() {
        val compiled = Parser.Csv("CSV (,)", ',', listOf("id", "name")).compile()
        val lines = listOf("1,\"Smith, J\"", "2,\"B \"\"b\"\"\"", "3", "4,\"open", "").map { it.toByteArray() }
        val rows = ParserPreview.preview(compiled, lines).map { it.values }
        val expected = listOf(
            listOf("1", "Smith, J"), listOf("2", "B \"b\""), listOf("3", ""), listOf("4,\"open", ""), null,
        )
        assertEquals(expected, rows)
    }

    @Test
    fun `detects a csv file, and only by its suffix`() {
        val text = "id,name\n1,Ann\n2,Bob\n"
        assertEquals(
            Parser.Csv("CSV (,)", ',', listOf("id", "name")),
            Parser.detect(Files.writeString(dir.resolve("people.CSV"), text)),
        )
        assertEquals(Parser.Raw, Parser.detect(Files.writeString(dir.resolve("people.txt"), text)))
    }

    @Test
    fun `detects a csv file whose sample ends inside a row`() {
        // Rows of 300 bytes: the sample's 256 KB end mid-row, inside a quoted cell, before 1000 lines.
        val row = "1,\"" + "x".repeat(294) + "\"\n"
        val file = Files.writeString(dir.resolve("wide.csv"), "id,note\n" + row.repeat(2000))
        assertEquals(Parser.Csv("CSV (,)", ',', listOf("id", "note")), Parser.detect(file))
    }

    private fun build(path: Path, options: IndexOptions = IndexOptions()) =
        runBlocking { ParsedLog.build(path, CompiledCsv(',', listOf("id", "name")), IndexProgress(), options) }

    private fun ParsedLog.rows() = List(recordCount) { rowValues(it, 1000).toList() }

    @Test
    fun `the header row is no record`() {
        val file = Files.writeString(dir.resolve("a.csv"), "id,name\r\n1,Ann\r\n2,\"B, b\"\r\n3\r\n")
        build(file).use { log ->
            assertEquals(listOf(listOf("1", "Ann"), listOf("2", "B, b"), listOf("3", "")), log.rows())
            assertEquals("1,Ann\r\n", log.sourceText(0))
        }
    }

    @Test
    fun `blank lines before the header are skipped with it`() {
        val file = Files.writeString(dir.resolve("blank.csv"), "\n \nid,name\n1,Ann\n")
        build(file).use { log -> assertEquals(listOf(listOf("1", "Ann")), log.rows()) }
    }

    @Test
    fun `quoted values show, filter and read in full without their escapes`() {
        val file = Files.writeString(dir.resolve("q.csv"), "id,name\n1,\"say \"\"hi\"\"\"\n2,\"plain\"\n3,a\"b\n")
        build(file).use { log ->
            assertEquals(listOf(listOf("1", "say \"hi\""), listOf("2", "plain"), listOf("3", "a\"b")), log.rows())
            assertEquals("say \"hi\"", log.fieldText(0, 1))
            val engine = FilterEngine(log)
            fun rows(value: String) =
                runBlocking { engine.prepare(TextCondition("name", TextOp.EQUALS, value)).run() }.rows.toList()
            assertEquals(listOf(0), rows("say \"hi\""))
            assertEquals(listOf(1), rows("plain"))
            assertEquals(listOf(2), rows("a\"b"))
        }
    }

    @Test
    fun `a line that is no row is found by its text`() {
        val file = Files.writeString(dir.resolve("bad.csv"), "id,name\n1,Ann\n2,Bob,extra\n")
        build(file).use { log ->
            assertEquals(listOf(listOf("1", "Ann"), listOf("2,Bob,extra", "")), log.rows())
            val engine = FilterEngine(log)
            val found = runBlocking { engine.prepare(TextCondition("id", TextOp.MATCHES, "extra")).run() }
            assertEquals(listOf(1), found.rows.toList())
        }
    }

    @Test
    fun `parallel parsing skips the header too`() {
        val content = "id,name\n" + (1..500).joinToString("") { "$it,name $it\n" }
        val file = Files.writeString(dir.resolve("p.csv"), content)
        val single = build(file, IndexOptions(parallelism = 1))
        val parallel = build(file, IndexOptions(parallelism = 8, minRangeSize = 1))
        single.use {
            parallel.use {
                assertEquals(500, single.recordCount)
                assertEquals(single.rows(), parallel.rows())
                assertEquals(listOf("1", "name 1"), parallel.rows().first())
            }
        }
    }

    @Test
    fun `appending to a file with only a header, or an unfinished one`() {
        val file = Files.writeString(dir.resolve("live.csv"), "id,na")
        build(file).use { log ->
            fun write(text: String) {
                Files.writeString(file, text, StandardOpenOption.APPEND)
                log.append(log.readAppended(Files.size(file)))
            }
            assertEquals(0, log.recordCount)
            write("me\n")
            assertEquals(0, log.recordCount)
            write("1,Ann\n2,B")
            assertEquals(listOf(listOf("1", "Ann")), log.rows().take(1))
            write("ob\n")
            assertEquals(listOf(listOf("1", "Ann"), listOf("2", "Bob")), log.rows())
        }
    }
}
