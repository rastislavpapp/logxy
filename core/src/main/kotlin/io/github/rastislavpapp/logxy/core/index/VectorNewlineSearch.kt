package io.github.rastislavpapp.logxy.core.index

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.VectorSpecies
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Searches for newlines a whole vector of bytes per step with the incubating Vector API. Loading this class needs
 * `--add-modules jdk.incubator.vector`; [NewlineScanner] checks for the module before it touches it.
 */
internal object VectorNewlineSearch : NewlineSearch {

    // Always the preferred width: a wider species than the CPU's runs as plain Java, 10× slower than SWAR. A static
    // final, so the JIT sees a constant species and emits SIMD code.
    private val SPECIES: VectorSpecies<Byte> = ByteVector.SPECIES_PREFERRED

    private const val NEWLINE = '\n'.code.toByte()

    val bitSize: Int get() = SPECIES.vectorBitSize()

    override fun scan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList) {
        val segment = wholeSegment(buffer)
        val step = SPECIES.length()
        var i = from
        val bound = from + SPECIES.loopBound(to - from)
        while (i < bound) {
            var mask = ByteVector.fromMemorySegment(SPECIES, segment, i.toLong(), ByteOrder.LITTLE_ENDIAN)
                .eq(NEWLINE).toLong()
            while (mask != 0L) {
                out.add(base + i + java.lang.Long.numberOfTrailingZeros(mask) + 1)
                mask = mask and (mask - 1)
            }
            i += step
        }
        while (i < to) {
            if (buffer.get(i) == NEWLINE) out.add(base + i + 1)
            i++
        }
    }

    override fun indexOf(buffer: ByteBuffer, from: Int, to: Int): Int {
        // Parsing calls this once per line on a heap buffer, where loading from the array is 8-15% faster than from
        // a segment of it: the segment costs a little per call, which short lines do not repay.
        if (buffer.hasArray()) {
            val offset = buffer.arrayOffset()
            val found = indexOfArray(buffer.array(), offset + from, offset + to)
            return if (found < 0) -1 else found - offset
        }
        val segment = wholeSegment(buffer)
        val step = SPECIES.length()
        var i = from
        val bound = from + SPECIES.loopBound(to - from)
        while (i < bound) {
            val mask = ByteVector.fromMemorySegment(SPECIES, segment, i.toLong(), ByteOrder.LITTLE_ENDIAN)
                .eq(NEWLINE).toLong()
            if (mask != 0L) return i + java.lang.Long.numberOfTrailingZeros(mask)
            i += step
        }
        while (i < to) {
            if (buffer.get(i) == NEWLINE) return i
            i++
        }
        return -1
    }

    private fun indexOfArray(bytes: ByteArray, from: Int, to: Int): Int {
        val step = SPECIES.length()
        var i = from
        val bound = from + SPECIES.loopBound(to - from)
        while (i < bound) {
            val mask = ByteVector.fromArray(SPECIES, bytes, i).eq(NEWLINE).toLong()
            if (mask != 0L) return i + java.lang.Long.numberOfTrailingZeros(mask)
            i += step
        }
        while (i < to) {
            if (bytes[i] == NEWLINE) return i
            i++
        }
        return -1
    }

    /**
     * The whole of [buffer], heap or direct, ignoring its position and limit, so that offsets in the segment are
     * indexes in the buffer.
     */
    private fun wholeSegment(buffer: ByteBuffer): MemorySegment {
        if (!buffer.hasArray()) return MemorySegment.ofBuffer(buffer.duplicate().clear())
        val array = MemorySegment.ofArray(buffer.array())
        return if (buffer.arrayOffset() == 0) array else array.asSlice(buffer.arrayOffset().toLong())
    }
}
