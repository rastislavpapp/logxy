package io.github.rastislavpapp.logxy.core.index

/** A [ChunkList] of bytes. */
class ByteChunkList : ChunkList<ByteArray>(ByteArray(0)) {

    override fun newChunk() = ByteArray(CHUNK_SIZE)

    /** Appends [value], starting a new chunk when the last one is full. */
    fun add(value: Byte) {
        chunkForAdd()[size and MASK] = value
        size++
    }

    /** The element at [index]; throws IndexOutOfBoundsException at or beyond [size]. */
    operator fun get(index: Int): Byte = chunkAt(index)[index and MASK]

    /** Replaces the element at [index], which must be below [size]. */
    operator fun set(index: Int, value: Byte) {
        chunkAt(index)[index and MASK] = value
    }

    /** The last element; throws IndexOutOfBoundsException if the list is empty. */
    fun last(): Byte = get(size - 1)
}
