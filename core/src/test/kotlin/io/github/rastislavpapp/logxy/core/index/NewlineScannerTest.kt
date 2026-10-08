package io.github.rastislavpapp.logxy.core.index

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class NewlineScannerTest {

    @Test
    fun `finds the same newlines as a naive scan`() {
        val random = Random(42)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(0, 100)) {
                // Mix in bytes that stress the bit trick: 0x0A neighbours, high-bit bytes, zero.
                when (random.nextInt(6)) {
                    0 -> '\n'.code.toByte()
                    1 -> 0x0B
                    2 -> 0x8A.toByte()
                    3 -> 0
                    else -> random.nextInt(256).toByte()
                }
            }
            val from = random.nextInt(0, bytes.size + 1)
            val to = random.nextInt(from, bytes.size + 1)
            val base = random.nextLong(0, 1L shl 40)

            val actual = LongChunkList()
            NewlineScanner.scan(ByteBuffer.wrap(bytes), from, to, base, actual)

            val expected = (from..<to).filter { bytes[it] == '\n'.code.toByte() }.map { base + it + 1 }
            assertEquals(expected, List(actual.size) { actual[it] })
        }
    }
}
