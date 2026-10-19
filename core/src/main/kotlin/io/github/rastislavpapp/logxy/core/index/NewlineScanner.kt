package io.github.rastislavpapp.logxy.core.index

import java.nio.ByteBuffer

/**
 * Finds line starts in a buffer; the hot loop of indexing and parsing. Searches with the Vector API where the CPU runs
 * it as SIMD, else eight bytes at a time with SWAR; see `docs/adr/0002-newline-search-uses-the-vector-api.md`.
 */
object NewlineScanner {

    // A static final, so the JIT folds the choice. The module check comes first: without the module, touching
    // VectorNewlineSearch throws NoClassDefFoundError.
    private val VECTOR = usesVectors(
        System.getProperty("logxy.newlines"),
        ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent,
    ) { VectorNewlineSearch.bitSize }

    /** The search this JVM runs, e.g. `vector, 512-bit` or `SWAR`, for logxy's own log. */
    val implementation: String = if (VECTOR) "vector, ${VectorNewlineSearch.bitSize}-bit" else "SWAR"

    /**
     * Appends `base + i + 1` to [out] for every `\n` at buffer index `i` in `from..<to`,
     * i.e. the file offset where the next line starts.
     */
    fun scan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList) {
        if (VECTOR) {
            VectorNewlineSearch.scan(buffer, from, to, base, out)
        } else {
            SwarNewlineSearch.scan(buffer, from, to, base, out)
        }
    }

    /** Index of the first `\n` in `buffer[from, to)`, or -1. */
    fun indexOf(buffer: ByteBuffer, from: Int, to: Int): Int =
        if (VECTOR) VectorNewlineSearch.indexOf(buffer, from, to) else SwarNewlineSearch.indexOf(buffer, from, to)
}

/** One way to search for newlines, with the contracts of [NewlineScanner.scan] and [NewlineScanner.indexOf]. */
internal interface NewlineSearch {
    fun scan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList)

    fun indexOf(buffer: ByteBuffer, from: Int, to: Int): Int
}

/** Narrower vectors run on SSE only, where the JIT leaves the Vector API as plain Java: 10× slower than SWAR. */
private const val MIN_VECTOR_BITS = 256

/**
 * Whether to search with vectors: never with [override] `swar` or without the module, else when the CPU's preferred
 * vector width is wide enough. [preferredBits] loads the Vector API, so it is called only once the module is there.
 */
internal fun usesVectors(override: String?, moduleLoaded: Boolean, preferredBits: () -> Int): Boolean =
    override != "swar" && moduleLoaded && preferredBits() >= MIN_VECTOR_BITS
