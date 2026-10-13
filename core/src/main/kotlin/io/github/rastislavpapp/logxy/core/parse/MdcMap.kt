package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.Bytes
import io.github.rastislavpapp.logxy.core.log.MapKey

/**
 * The whole MDC as `%X` writes it: `{k1=v1, k2=v2}` in log4j2, the same without braces in logback.
 * A value containing ", " is cut there, as the text does not tell it from the next entry.
 */
internal object MdcMap : MapSyntax {

    override val maxKeys = 32
    override val json = false

    private const val OPEN = '{'.code.toByte()
    private const val CLOSE = '}'.code.toByte()
    const val EQUALS = '='.code.toByte()
    val SEPARATOR = ", ".toByteArray()

    /** The entries of `bytes[from, to)` without braces, packed as [pack]; -1 if it is not a map. */
    override fun entries(bytes: ByteArray, from: Int, to: Int): Long {
        var start = from
        var end = to
        if (end - start >= 2 && bytes[start] == OPEN && bytes[end - 1] == CLOSE) {
            start++
            end--
        }
        if (start == end) return pack(start, end)
        // The first entry must be key=value.
        val first = Bytes.indexOf(bytes, SEPARATOR, start, end).let { if (it < 0) end else it }
        for (i in start..<first) if (bytes[i] == EQUALS) return if (i > start) pack(start, end) else -1
        return -1
    }

    /** Calls [action] with the key range of each entry in `bytes[from, to)`, as found by [entries]. */
    inline fun forEachKey(bytes: ByteArray, from: Int, to: Int, action: (Int, Int) -> Unit) {
        var entry = from
        while (entry < to) {
            var next = Bytes.indexOf(bytes, SEPARATOR, entry, to)
            if (next < 0) next = to
            var equals = entry
            while (equals < next && bytes[equals] != EQUALS) equals++
            if (equals in (entry + 1)..<next) action(entry, equals)
            entry = next + SEPARATOR.size
        }
    }

    /** The value range of [key] (`key=`) among the entries in `bytes[from, to)`, packed; -1 if absent. */
    fun valueOf(bytes: ByteArray, from: Int, to: Int, key: ByteArray): Long {
        var entry = from
        while (entry < to) {
            var next = Bytes.indexOf(bytes, SEPARATOR, entry, to)
            if (next < 0) next = to
            if (Bytes.regionMatches(bytes, entry, next, key)) return pack(entry + key.size, next)
            entry = next + SEPARATOR.size
        }
        return -1
    }

    override fun value(bytes: ByteArray, from: Int, to: Int, key: MapKey) = valueOf(bytes, from, to, key.prefix)

    override fun fieldName(map: String, key: String) = "$map[$key]"

    /** The range `[from, to)` in one long, so map lookups return a range without allocating; read back with [from] and [to]. */
    fun pack(from: Int, to: Int): Long = (from.toLong() shl 32) or to.toLong()
    fun from(packed: Long): Int = (packed ushr 32).toInt()
    fun to(packed: Long): Int = packed.toInt()
}
