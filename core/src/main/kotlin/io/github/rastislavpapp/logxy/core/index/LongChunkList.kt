package io.github.rastislavpapp.logxy.core.index

/** A [ChunkList] of longs. */
class LongChunkList : ChunkList<LongArray>(LongArray(0)) {

    override fun newChunk() = LongArray(CHUNK_SIZE)

    fun add(value: Long) {
        chunkForAdd()[size and MASK] = value
        size++
    }

    operator fun get(index: Int): Long = chunkAt(index)[index and MASK]

    operator fun set(index: Int, value: Long) {
        chunkAt(index)[index and MASK] = value
    }

    fun last(): Long = get(size - 1)
}
