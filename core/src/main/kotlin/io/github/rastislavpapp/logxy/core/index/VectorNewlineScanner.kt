package io.github.rastislavpapp.logxy.core.index

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.VectorSpecies
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [NewlineScanner] with the Vector API: compares a whole vector of bytes to `\n` per step. Experimental: needs
 * `--add-modules jdk.incubator.vector`. The vector width comes from `-Dlogxy.vectorBits` (128, 256 or 512), by
 * default the widest the CPU has; it must be a constant for the JIT to emit SIMD code, hence a static final.
 */
object VectorNewlineScanner {

    private val SPECIES: VectorSpecies<Byte> = when (System.getProperty("logxy.vectorBits")) {
        "128" -> ByteVector.SPECIES_128
        "256" -> ByteVector.SPECIES_256
        "512" -> ByteVector.SPECIES_512
        else -> ByteVector.SPECIES_PREFERRED
    }

    private const val NEWLINE = '\n'.code.toByte()

    fun scan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList) {
        if (buffer.hasArray()) {
            scanArray(buffer.array(), buffer.arrayOffset() + from, buffer.arrayOffset() + to, base - buffer.arrayOffset(), out)
            return
        }
        // A segment of the whole buffer, whatever its position: indexes are absolute.
        val segment = MemorySegment.ofBuffer(buffer.duplicate().clear())
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

    private fun scanArray(bytes: ByteArray, from: Int, to: Int, base: Long, out: LongChunkList) {
        val step = SPECIES.length()
        var i = from
        val bound = from + SPECIES.loopBound(to - from)
        while (i < bound) {
            var mask = ByteVector.fromArray(SPECIES, bytes, i).eq(NEWLINE).toLong()
            while (mask != 0L) {
                out.add(base + i + java.lang.Long.numberOfTrailingZeros(mask) + 1)
                mask = mask and (mask - 1)
            }
            i += step
        }
        while (i < to) {
            if (bytes[i] == NEWLINE) out.add(base + i + 1)
            i++
        }
    }

    fun indexOf(buffer: ByteBuffer, from: Int, to: Int): Int {
        if (buffer.hasArray()) {
            val offset = buffer.arrayOffset()
            val found = indexOfArray(buffer.array(), offset + from, offset + to)
            return if (found < 0) -1 else found - offset
        }
        val segment = MemorySegment.ofBuffer(buffer.duplicate().clear())
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
}
