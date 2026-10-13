package io.github.rastislavpapp.logxy.core.index

/** A [ChunkList] of longs. */
class LongChunkList : ChunkList<LongArray>(LongArray(0)) {

    override fun newChunk() = LongArray(CHUNK_SIZE)

    /** Appends [value], starting a new chunk when the last one is full. */
    fun add(value: Long) {
        chunkForAdd()[size and MASK] = value
        size++
    }

    /** The element at [index]; throws IndexOutOfBoundsException at or beyond [size]. */
    operator fun get(index: Int): Long = chunkAt(index)[index and MASK]

    /** Replaces the element at [index], which must be below [size]. */
    operator fun set(index: Int, value: Long) {
        chunkAt(index)[index and MASK] = value
    }

    /** The last element; throws IndexOutOfBoundsException if the list is empty. */
    fun last(): Long = get(size - 1)
}
