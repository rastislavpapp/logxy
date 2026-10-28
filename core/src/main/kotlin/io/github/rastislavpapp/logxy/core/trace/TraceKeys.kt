package io.github.rastislavpapp.logxy.core.trace

import io.github.rastislavpapp.logxy.core.index.ByteChunkList
import io.github.rastislavpapp.logxy.core.index.IntChunkList

/**
 * Numbers the distinct trace ids, by their bytes, from 0 in the order they were added. All ids share one byte list
 * and a table of ints, so a trace id costs its bytes plus about 16, without an object of its own: a trace id column
 * is mostly distinct.
 *
 * Not thread-safe, but a [view] reads the keys there were when it was taken on any thread, as the lists only grow.
 */
internal class TraceKeys {
    private val bytes = ByteChunkList()

    /** Where each key starts in [bytes]; it ends where the next one starts. */
    private val starts = IntChunkList()
    private val hashes = IntChunkList()
    private var table = IntArray(INITIAL_SLOTS) { -1 }

    val size get() = starts.size

    /** The number of `source[from, to)`, adding it with the next number if it is new. */
    fun numberOf(source: ByteArray, from: Int, to: Int): Int {
        val hash = hash(source, from, to)
        var slot = hash and (table.size - 1)
        while (true) {
            val number = table[slot]
            if (number < 0) break
            if (hashes[number] == hash && equals(number, source, from, to)) return number
            slot = (slot + 1) and (table.size - 1)
        }
        val number = size
        starts.add(bytes.size)
        hashes.add(hash)
        for (i in from..<to) bytes.add(source[i])
        table[slot] = number
        if (size * 2 > table.size) rehash()
        return number
    }

    /** The number of `source[from, to)`, or -1 if it has none; adds nothing. */
    fun find(source: ByteArray, from: Int, to: Int): Int {
        val hash = hash(source, from, to)
        var slot = hash and (table.size - 1)
        while (true) {
            val number = table[slot]
            if (number < 0 || (hashes[number] == hash && equals(number, source, from, to))) return number
            slot = (slot + 1) and (table.size - 1)
        }
    }

    /** The keys there are now, to read on other threads. */
    fun view(): KeyView = KeyView(bytes, starts, size, bytes.size)

    private fun equals(number: Int, source: ByteArray, from: Int, to: Int): Boolean {
        val start = starts[number]
        val end = if (number + 1 < size) starts[number + 1] else bytes.size
        if (end - start != to - from) return false
        for (i in 0..<to - from) if (bytes[start + i] != source[from + i]) return false
        return true
    }

    private fun rehash() {
        table = IntArray(table.size * 2) { -1 }
        for (number in 0..<size) {
            var slot = hashes[number] and (table.size - 1)
            while (table[slot] >= 0) slot = (slot + 1) and (table.size - 1)
            table[slot] = number
        }
    }

    // The same mix as ByteDictionary's, a byte at a time: trace ids are short.
    private fun hash(source: ByteArray, from: Int, to: Int): Int {
        var h = -0x340D631B7BDDDCDBL
        for (i in from..<to) h = (h xor (source[i].toLong() and 0xFF)) * MULTIPLIER
        h = h xor (h ushr 29)
        return (h xor (h ushr 32)).toInt()
    }

    private companion object {
        const val INITIAL_SLOTS = 64
        const val MULTIPLIER = -0x61C8864680B583EBL
    }
}

/** The first [count] keys of a [TraceKeys], which take [byteCount] bytes; readable on any thread. */
internal class KeyView(
    private val bytes: ByteChunkList,
    private val starts: IntChunkList,
    val count: Int,
    private val byteCount: Int,
) {
    private fun start(number: Int) = starts[number]
    private fun end(number: Int) = if (number + 1 < count) starts[number + 1] else byteCount

    fun key(number: Int): ByteArray {
        val start = start(number)
        return ByteArray(end(number) - start) { bytes[start + it] }
    }

    /** Compares keys [a] and [b] by their bytes, unsigned, as UTF-8 text sorts by code point. */
    fun compare(a: Int, b: Int): Int {
        val aStart = start(a)
        val bStart = start(b)
        val aLength = end(a) - aStart
        val bLength = end(b) - bStart
        for (i in 0..<minOf(aLength, bLength)) {
            val difference = (bytes[aStart + i].toInt() and 0xFF) - (bytes[bStart + i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return aLength - bLength
    }
}
