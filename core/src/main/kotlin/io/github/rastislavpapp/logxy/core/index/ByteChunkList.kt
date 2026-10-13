package io.github.rastislavpapp.logxy.core.index

/** A [ChunkList] of bytes. */
class ByteChunkList : ChunkList<ByteArray>(ByteArray(0)) {

    override fun newChunk() = ByteArray(CHUNK_SIZE)

    fun add(value: Byte) {
        chunkForAdd()[size and MASK] = value
        size++
    }

    operator fun get(index: Int): Byte = chunkAt(index)[index and MASK]

    operator fun set(index: Int, value: Byte) {
        chunkAt(index)[index and MASK] = value
    }

    fun last(): Byte = get(size - 1)
}
