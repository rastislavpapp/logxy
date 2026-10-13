package io.github.rastislavpapp.logxy.core.index

/**
 * Append-only list of primitives stored in fixed-size chunks, so growing never copies existing data
 * and never needs a single huge allocation. Generic over the chunk array type [A] (e.g. `LongArray`),
 * not the element type, so values are never boxed; subclasses add the typed accessors.
 */
abstract class ChunkList<A : Any> internal constructor(empty: A) {

    // Volatile: filters read the list on other threads while the owner thread appends (see LogData).
    @Volatile
    private var chunks = arrayOfNulls<Any>(16)
    private var current = empty

    var size = 0
        protected set

    /** A new chunk of [CHUNK_SIZE] elements. */
    protected abstract fun newChunk(): A

    /** The chunk the next added element goes into, at offset `size and MASK`. */
    protected fun chunkForAdd(): A {
        if ((size and MASK) == 0) startChunk()
        return current
    }

    /** The chunk holding [index], at offset `index and MASK`. */
    @Suppress("UNCHECKED_CAST")
    protected fun chunkAt(index: Int): A {
        if (index !in 0..<size) throw IndexOutOfBoundsException("Index $index, size $size")
        return chunks[index ushr SHIFT] as A
    }

    /** Drops the last element, e.g. a line or record that turned out incomplete; its chunk stays allocated for the next add. Throws if empty. */
    @Suppress("UNCHECKED_CAST")
    fun removeLast() {
        if (size == 0) throw NoSuchElementException("List is empty")
        size--
        if ((size and MASK) != 0) current = chunks[size ushr SHIFT] as A
    }

    /** Appends all of [other] by copying whole runs of its chunks, e.g. to join ranges indexed in parallel. */
    fun addAll(other: ChunkList<A>) {
        require(other !== this) { "Cannot add a list to itself" }
        var copied = 0
        while (copied < other.size) {
            val offset = size and MASK
            if (offset == 0) startChunk()
            val sourceOffset = copied and MASK
            val count = minOf(CHUNK_SIZE - offset, CHUNK_SIZE - sourceOffset, other.size - copied)
            System.arraycopy(other.chunks[copied ushr SHIFT]!!, sourceOffset, current, offset, count)
            size += count
            copied += count
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun startChunk() {
        if (size == Int.MAX_VALUE) throw IllegalStateException("List cannot hold more than ${Int.MAX_VALUE} elements")
        val chunkIndex = size ushr SHIFT
        if (chunkIndex == chunks.size) chunks = chunks.copyOf(chunks.size * 2)
        // A chunk may survive removeLast() calls that crossed a chunk boundary.
        current = chunks[chunkIndex] as A? ?: newChunk().also { chunks[chunkIndex] = it }
    }

    internal companion object {
        const val SHIFT = 16
        const val CHUNK_SIZE = 1 shl SHIFT
        const val MASK = CHUNK_SIZE - 1
    }
}
