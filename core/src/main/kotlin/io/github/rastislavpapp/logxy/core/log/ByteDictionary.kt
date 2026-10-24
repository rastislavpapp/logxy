package io.github.rastislavpapp.logxy.core.log

/**
 * Assigns dense ids to distinct byte sequences, looked up straight from a buffer without creating
 * strings. Not thread-safe.
 */
class ByteDictionary {

    private val keys = ArrayList<ByteArray>()
    private val texts = ArrayList<String?>()
    private var table = IntArray(64) { -1 }

    val size get() = keys.size

    /** The id of `bytes[from, to)`, adding it (as a copy) with the next free id if it is new. */
    fun idOf(bytes: ByteArray, from: Int, to: Int): Int {
        val hash = hash(bytes, from, to)
        var slot = hash and (table.size - 1)
        while (true) {
            val id = table[slot]
            if (id < 0) break
            if (keys[id].contentEquals(bytes, from, to)) return id
            slot = (slot + 1) and (table.size - 1)
        }
        val id = keys.size
        keys += bytes.copyOfRange(from, to)
        texts += null
        table[slot] = id
        if (keys.size * 2 > table.size) rehash()
        return id
    }

    /** The id of `bytes[from, to)`, or -1 if it has none; adds nothing. */
    fun find(bytes: ByteArray, from: Int, to: Int): Int {
        var slot = hash(bytes, from, to) and (table.size - 1)
        while (true) {
            val id = table[slot]
            if (id < 0 || keys[id].contentEquals(bytes, from, to)) return id
            slot = (slot + 1) and (table.size - 1)
        }
    }

    /** The bytes of [id]; the dictionary's own array, not to be changed. */
    fun key(id: Int): ByteArray = keys[id]

    /**
     * Maps the ids of this dictionary to those of [target]: element i is the id in [target] of this dictionary's
     * key i. Adds the keys [target] lacks.
     */
    fun idsIn(target: ByteDictionary): IntArray = IntArray(size) { id -> keys[id].let { target.idOf(it, 0, it.size) } }

    /** The bytes of [id] decoded as UTF-8, decoded once and kept. */
    fun text(id: Int): String = texts[id] ?: String(keys[id], Charsets.UTF_8).also { texts[id] = it }

    private fun rehash() {
        table = IntArray(table.size * 2) { -1 }
        for (id in keys.indices) {
            val key = keys[id]
            var slot = hash(key, 0, key.size) and (table.size - 1)
            while (table[slot] >= 0) slot = (slot + 1) and (table.size - 1)
            table[slot] = id
        }
    }

    private fun ByteArray.contentEquals(other: ByteArray, from: Int, to: Int): Boolean =
        size == to - from && java.util.Arrays.equals(this, 0, size, other, from, to)

    // Eight bytes per step: a multiply per byte makes long keys, like message templates, slow to hash.
    private fun hash(bytes: ByteArray, from: Int, to: Int): Int {
        var h = -0x340D631B7BDDDCDBL
        var i = from
        while (i + 8 <= to) {
            val word = (bytes[i].toLong() and 0xFF) or
                (bytes[i + 1].toLong() and 0xFF shl 8) or
                (bytes[i + 2].toLong() and 0xFF shl 16) or
                (bytes[i + 3].toLong() and 0xFF shl 24) or
                (bytes[i + 4].toLong() and 0xFF shl 32) or
                (bytes[i + 5].toLong() and 0xFF shl 40) or
                (bytes[i + 6].toLong() and 0xFF shl 48) or
                (bytes[i + 7].toLong() shl 56)
            h = (h xor word) * MULTIPLIER
            i += 8
        }
        while (i < to) h = (h xor (bytes[i++].toLong() and 0xFF)) * MULTIPLIER
        h = h xor (h ushr 29)
        return (h xor (h ushr 32)).toInt()
    }

    private companion object {
        const val MULTIPLIER = -0x61C8864680B583EBL
    }
}
