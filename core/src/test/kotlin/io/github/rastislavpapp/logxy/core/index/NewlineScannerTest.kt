package io.github.rastislavpapp.logxy.core.index

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class NewlineScannerTest {

    private val newline = '\n'.code.toByte()

    @Test
    fun `both searches find the same newlines as a naive scan, in heap, direct and sliced buffers`() {
        val random = Random(42)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(0, 300)) {
                // Mix in bytes that stress the SWAR bit trick: 0x0A neighbours, high-bit bytes, zero.
                when (random.nextInt(6)) {
                    0 -> newline
                    1 -> 0x0B
                    2 -> 0x8A.toByte()
                    3 -> 0
                    else -> random.nextInt(256).toByte()
                }
            }
            val from = nearVectorEdge(random, 0, bytes.size)
            val to = nearVectorEdge(random, from, bytes.size)
            val base = random.nextLong(0, 1L shl 40)
            val expectedStarts = (from..<to).filter { bytes[it] == newline }.map { base + it + 1 }
            val expectedIndex = (from..<to).firstOrNull { bytes[it] == newline } ?: -1

            for (buffer in buffersOf(bytes, random)) {
                for (search in listOf(SwarNewlineSearch, VectorNewlineSearch)) {
                    val starts = LongChunkList()
                    search.scan(buffer, from, to, base, starts)
                    assertEquals(expectedStarts, List(starts.size) { starts[it] }, "$search scan")
                    assertEquals(expectedIndex, search.indexOf(buffer, from, to), "$search indexOf")
                }
            }
        }
    }

    /** A bound in `min..max`, often within two bytes of a multiple of 16, where vector loops hand over to the tail. */
    private fun nearVectorEdge(random: Random, min: Int, max: Int): Int {
        if (random.nextBoolean()) return random.nextInt(min, max + 1)
        val edge = random.nextInt(0, max / 16 + 1) * 16 + random.nextInt(-2, 3)
        return edge.coerceIn(min, max)
    }

    /** The bytes as a heap buffer, a direct one, and slices of both that start inside a larger buffer. */
    private fun buffersOf(bytes: ByteArray, random: Random): List<ByteBuffer> {
        val offset = random.nextInt(1, 70)
        val padded = ByteArray(offset + bytes.size + offset) { newline }
        bytes.copyInto(padded, offset)
        val direct = ByteBuffer.allocateDirect(padded.size).put(padded).clear()
        return listOf(
            ByteBuffer.wrap(bytes),
            ByteBuffer.allocateDirect(bytes.size).put(bytes).clear(),
            ByteBuffer.wrap(padded).slice(offset, bytes.size),
            direct.slice(offset, bytes.size),
        )
    }
}
