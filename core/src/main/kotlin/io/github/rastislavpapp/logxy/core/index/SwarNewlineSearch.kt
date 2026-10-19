package io.github.rastislavpapp.logxy.core.index

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Searches for newlines eight bytes at a time in a `long`; the fallback where vectors would not be SIMD. */
internal object SwarNewlineSearch : NewlineSearch {

    private const val NEWLINES = 0x0A0A0A0A0A0A0A0AL
    private const val LOW7 = 0x7F7F7F7F7F7F7F7FL

    override fun scan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var i = from
        val wordEnd = to - 7
        while (i < wordEnd) {
            // SWAR: high bit of each byte in `found` is set exactly where the byte equals '\n'.
            val x = buffer.getLong(i) xor NEWLINES
            var found = (((x and LOW7) + LOW7) or x or LOW7).inv()
            while (found != 0L) {
                out.add(base + i + (java.lang.Long.numberOfTrailingZeros(found) ushr 3) + 1)
                found = found and (found - 1)
            }
            i += 8
        }
        while (i < to) {
            if (buffer.get(i) == '\n'.code.toByte()) out.add(base + i + 1)
            i++
        }
    }

    override fun indexOf(buffer: ByteBuffer, from: Int, to: Int): Int {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var i = from
        val wordEnd = to - 7
        while (i < wordEnd) {
            val x = buffer.getLong(i) xor NEWLINES
            val found = (((x and LOW7) + LOW7) or x or LOW7).inv()
            if (found != 0L) return i + (java.lang.Long.numberOfTrailingZeros(found) ushr 3)
            i += 8
        }
        while (i < to) {
            if (buffer.get(i) == '\n'.code.toByte()) return i
            i++
        }
        return -1
    }
}
