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
    fun `addAll copies across chunk boundaries of both lists`() {
        val sizes = listOf(0, 1, 65_535, 65_536, 65_537, 200_000)
        for (targetSize in listOf(0, 3, 65_530, 65_536)) {
            for (sourceSize in sizes) {
                val target = LongChunkList()
                for (i in 0..<targetSize) target.add(-i.toLong())
                val source = LongChunkList()
                for (i in 0..<sourceSize) source.add(i * 7L)
                target.addAll(source)
                assertEquals(targetSize + sourceSize, target.size)
                for (i in 0..<targetSize) assertEquals(-i.toLong(), target[i])
                for (i in 0..<sourceSize) assertEquals(i * 7L, target[targetSize + i])
            }
        }
    }

    @Test
    fun `addAll after removeLast across a chunk boundary`() {
        val target = LongChunkList()
        for (i in 0..65_536) target.add(i.toLong())
        target.removeLast()
        target.removeLast()
        val source = LongChunkList()
        for (i in 0..<10) source.add(-i.toLong())
        target.addAll(source)
        assertEquals(65_545, target.size)
        assertEquals(65_534L, target[65_534])
        for (i in 0..<10) assertEquals(-i.toLong(), target[65_535 + i])
    }

    @Test
    fun `out of range access fails`() {
        val list = LongChunkList()
        list.add(1)
        assertFailsWith<IndexOutOfBoundsException> { list[1] }
        assertFailsWith<IndexOutOfBoundsException> { list[-1] }
    }
}
