package io.github.rastislavpapp.logxy.core.index

/**
 * Append-only list of bytes stored in fixed-size chunks, so growing never copies existing data
 * and never needs a single huge allocation.
 */
class ByteChunkList {

    private var chunks = arrayOfNulls<ByteArray>(16)
    private var current = EMPTY

    var size = 0
        private set

    fun add(value: Byte) {
        val offset = size and MASK
        if (offset == 0) startChunk()
        current[offset] = value
        size++
    }

    operator fun get(index: Int): Byte {
        if (index !in 0..<size) throw IndexOutOfBoundsException("Index $index, size $size")
        return chunks[index ushr SHIFT]!![index and MASK]
    }

    operator fun set(index: Int, value: Byte) {
        if (index !in 0..<size) throw IndexOutOfBoundsException("Index $index, size $size")
        chunks[index ushr SHIFT]!![index and MASK] = value
    }

    fun last(): Byte = get(size - 1)

    fun removeLast() {
        if (size == 0) throw NoSuchElementException("List is empty")
        size--
        if ((size and MASK) != 0) current = chunks[size ushr SHIFT]!!
    }

    fun addAll(other: ByteChunkList) {
        for (i in 0..<other.size) add(other[i])
    }

    private fun startChunk() {
        if (size == Int.MAX_VALUE) throw IllegalStateException("List cannot hold more than ${Int.MAX_VALUE} elements")
        val chunkIndex = size ushr SHIFT
        if (chunkIndex == chunks.size) chunks = chunks.copyOf(chunks.size * 2)
        // A chunk may survive removeLast() calls that crossed a chunk boundary.
        current = chunks[chunkIndex] ?: ByteArray(CHUNK_SIZE).also { chunks[chunkIndex] = it }
    }

    private companion object {
        const val SHIFT = 16
        const val CHUNK_SIZE = 1 shl SHIFT
        const val MASK = CHUNK_SIZE - 1
        val EMPTY = ByteArray(0)
    }
}
