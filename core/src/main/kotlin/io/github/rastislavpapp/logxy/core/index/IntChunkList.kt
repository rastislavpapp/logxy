package io.github.rastislavpapp.logxy.core.index

/** A [ChunkList] of ints. */
class IntChunkList : ChunkList<IntArray>(IntArray(0)) {

    override fun newChunk() = IntArray(CHUNK_SIZE)

    fun add(value: Int) {
        chunkForAdd()[size and MASK] = value
        size++
    }

    operator fun get(index: Int): Int = chunkAt(index)[index and MASK]

    operator fun set(index: Int, value: Int) {
        chunkAt(index)[index and MASK] = value
    }

    fun last(): Int = get(size - 1)
}
