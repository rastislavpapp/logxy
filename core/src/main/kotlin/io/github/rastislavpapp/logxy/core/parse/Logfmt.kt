package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.MapKey

/**
 * logfmt lines: `key=value key2="quoted \"value\""`, pairs separated by spaces. A key without `=`
 * has an empty value. Quoted values are escaped as JSON strings are, so [Json] decodes them.
 */
internal object Logfmt {

    const val SPACE = ' '.code.toByte()
    const val TAB = '\t'.code.toByte()
    const val EQUALS = '='.code.toByte()
    const val QUOTE = '"'.code.toByte()
    private const val BACKSLASH = '\\'.code.toByte()

    /** Whether [b] separates pairs: a space or a tab. */
    fun isSpace(b: Byte) = b == SPACE || b == TAB

    /**
     * Calls [pair] with the key and value range of each pair in `bytes[from, to)`, a quoted value with its
     * quotes; returns false, maybe after some calls, if a key is empty or a quote is not closed.
     */
    inline fun forEachPair(bytes: ByteArray, from: Int, to: Int, pair: (Int, Int, Int, Int) -> Unit): Boolean {
        var i = from
        while (true) {
            while (i < to && isSpace(bytes[i])) i++
            if (i == to) return true
            val keyFrom = i
            while (i < to && !isSpace(bytes[i]) && bytes[i] != EQUALS && bytes[i] != QUOTE) i++
            if (i == keyFrom || (i < to && bytes[i] == QUOTE)) return false
            val keyTo = i
            if (i == to || bytes[i] != EQUALS) {
                pair(keyFrom, keyTo, keyTo, keyTo)
                continue
            }
            i++
            val valueFrom = i
            if (i < to && bytes[i] == QUOTE) {
                i = quotedEnd(bytes, i, to)
                if (i < 0) return false
            } else {
                while (i < to && !isSpace(bytes[i])) i++
            }
            pair(keyFrom, keyTo, valueFrom, i)
        }
    }

    /** The index after the quoted string starting at `bytes[from]`, or -1 if it is not closed. */
    fun quotedEnd(bytes: ByteArray, from: Int, to: Int): Int {
        var i = from + 1
        while (i < to) {
            when (bytes[i]) {
                BACKSLASH -> i += 2
                QUOTE -> return i + 1
                else -> i++
            }
        }
        return -1
    }
}

/** A logfmt line read as a map: its keys are fields, except those in [excluded], shown through fields of their own. */
internal class LogfmtMap(private val excluded: Set<String>) : MapSyntax {

    override val maxKeys = 64
    override val json = true

    override fun entries(bytes: ByteArray, from: Int, to: Int): Long = MdcMap.pack(from, to)

    override fun value(bytes: ByteArray, from: Int, to: Int, key: MapKey): Long {
        val expected = key.bytes
        Logfmt.forEachPair(bytes, from, to) { keyFrom, keyTo, valueFrom, valueTo ->
            if (java.util.Arrays.equals(bytes, keyFrom, keyTo, expected, 0, expected.size)) {
                return MdcMap.pack(valueFrom, valueTo)
            }
        }
        return -1
    }

    override fun splits(key: String) = key !in excluded

    override fun fieldName(map: String, key: String) = key
}
