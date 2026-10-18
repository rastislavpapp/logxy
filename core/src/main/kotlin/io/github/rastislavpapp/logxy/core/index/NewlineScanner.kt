package io.github.rastislavpapp.logxy.core.index

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Finds line starts in a buffer eight bytes at a time; the hot loop of indexing. */
object NewlineScanner {

    private const val NEWLINES = 0x0A0A0A0A0A0A0A0AL
    private const val LOW7 = 0x7F7F7F7F7F7F7F7FL

    /** Experiment: `-Dlogxy.newlines=vector` uses [VectorNewlineScanner]; a static final, so the JIT folds it. */
    private val VECTOR = System.getProperty("logxy.newlines") == "vector"

    /**
     * Appends `base + i + 1` to [out] for every `\n` at buffer index `i` in `from..<to`,
     * i.e. the file offset where the next line starts.
     */
    fun scan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList) {
        if (VECTOR) VectorNewlineScanner.scan(buffer, from, to, base, out) else swarScan(buffer, from, to, base, out)
    }

    /** Index of the first `\n` in `buffer[from, to)`, or -1. */
    fun indexOf(buffer: ByteBuffer, from: Int, to: Int): Int =
        if (VECTOR) VectorNewlineScanner.indexOf(buffer, from, to) else swarIndexOf(buffer, from, to)

    fun swarScan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList) {
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

    fun swarIndexOf(buffer: ByteBuffer, from: Int, to: Int): Int {
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
