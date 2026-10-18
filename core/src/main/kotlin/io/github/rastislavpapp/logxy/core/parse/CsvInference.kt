package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.Bytes

/** Guesses a CSV parser from a file's first lines. */
internal object CsvInference {

    private val SEPARATORS = listOf(',', ';')

    /**
     * A parser for [sample] if its first line that is not blank is a header row of 2 to [Csv.MAX_COLUMNS] distinct
     * names, none empty or a number, and every other line that is not blank is a valid row with as many cells; else
     * null. Of two separators that fit, the one with more columns wins, then the one the header has first.
     */
    fun infer(sample: List<ByteArray>): Parser.Csv? {
        // The header the way indexing skips it, see CompiledParser.headerLines.
        val rows = sample.filterNot { Bytes.isBlank(it, 0, it.size) }
        val header = rows.firstOrNull() ?: return null
        val fits = SEPARATORS.mapNotNull { separator ->
            val names = headers(header, separator)?.takeIf { rows.allMatch(separator, it.size) }
            names?.let { separator to it }
        }
        val (separator, names) = fits.maxWithOrNull(
            compareBy({ it.second.size }, { -header.indexOf(it.first.code.toByte()) }),
        ) ?: return null
        return Parser.Csv("CSV ($separator)", separator, names)
    }

    private fun List<ByteArray>.allMatch(separator: Char, columns: Int): Boolean {
        val starts = IntArray(Csv.MAX_COLUMNS + 1)
        val ends = IntArray(Csv.MAX_COLUMNS + 1)
        return drop(1).all { Csv.cells(it, 0, it.size, separator.code.toByte(), starts, ends) == columns }
    }

    /** The column names in [header] split at [separator], or null if it is no plausible header row. */
    private fun headers(header: ByteArray, separator: Char): List<String>? {
        val starts = IntArray(Csv.MAX_COLUMNS + 1)
        val ends = IntArray(Csv.MAX_COLUMNS + 1)
        val from = Csv.contentFrom(header, 0, header.size)
        val count = Csv.cells(header, from, header.size, separator.code.toByte(), starts, ends)
        if (count !in 2..Csv.MAX_COLUMNS) return null
        val names = (0..<count).map { i -> String(Csv.decode(header, starts[i], ends[i]), Charsets.UTF_8) }
        val distinct = names.map { it.lowercase() }.toSet().size == count
        return names.takeIf { distinct && names.all { it.isNotEmpty() && it.toDoubleOrNull() == null } }
    }
}
