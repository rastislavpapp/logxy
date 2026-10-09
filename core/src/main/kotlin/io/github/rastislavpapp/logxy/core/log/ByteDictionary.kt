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

    fun key(id: Int): ByteArray = keys[id]

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

    private fun ByteArray.contentEquals(other: ByteArray, from: Int, to: Int): Boolean {
        if (size != to - from) return false
        for (i in indices) if (this[i] != other[from + i]) return false
        return true
    }

    private fun hash(bytes: ByteArray, from: Int, to: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (i in from..<to) h = (h xor bytes[i].toInt()) * 0x01000193
        return h xor (h ushr 16)
    }
}
