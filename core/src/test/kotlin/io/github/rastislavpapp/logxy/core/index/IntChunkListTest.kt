package io.github.rastislavpapp.logxy.core.index

import kotlin.test.Test
import kotlin.test.assertEquals

class IntChunkListTest {

    @Test
    fun `addAll copies across chunk boundaries of both lists`() {
        val sizes = listOf(0, 1, 65_535, 65_536, 65_537, 200_000)
        for (targetSize in listOf(0, 3, 65_530, 65_536)) {
            for (sourceSize in sizes) {
                val target = IntChunkList()
                for (i in 0..<targetSize) target.add((-i))
                val source = IntChunkList()
                for (i in 0..<sourceSize) source.add((i * 7))
                target.addAll(source)
                assertEquals(targetSize + sourceSize, target.size)
                for (i in 0..<targetSize) assertEquals((-i), target[i])
                for (i in 0..<sourceSize) assertEquals((i * 7), target[targetSize + i])
            }
        }
    }
}
