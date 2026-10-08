package io.github.rastislavpapp.logxy.core.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LongChunkListTest {

    @Test
    fun `stores values across chunk boundaries`() {
        val list = LongChunkList()
        val count = 200_000
        for (i in 0..<count) list.add(i * 3L)
        assertEquals(count, list.size)
        assertEquals(0L, list[0])
        assertEquals(65_536 * 3L, list[65_536])
        assertEquals((count - 1) * 3L, list.last())
    }

    @Test
    fun `removeLast across a chunk boundary allows adding again`() {
        val list = LongChunkList()
        for (i in 0..65_536) list.add(i.toLong())
        list.removeLast()
        list.removeLast()
        list.add(-1)
        list.add(-2)
        assertEquals(65_537, list.size)
        assertEquals(-1L, list[65_535])
        assertEquals(-2L, list[65_536])
    }

    @Test
    fun `out of range access fails`() {
        val list = LongChunkList()
        list.add(1)
        assertFailsWith<IndexOutOfBoundsException> { list[1] }
        assertFailsWith<IndexOutOfBoundsException> { list[-1] }
    }
}
