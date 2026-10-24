package io.github.rastislavpapp.logxy.core.index

import java.nio.ByteBuffer

/**
 * Finds line starts in a buffer; the hot loop of indexing and parsing. Searches with the Vector API where the CPU runs
 * it as SIMD, else eight bytes at a time with SWAR; see `docs/adr/0002-newline-search-uses-the-vector-api.md`.
 */
object NewlineScanner {

    /** The system property that turns the vector search off when set to [SWAR]. */
    const val PROPERTY = "logxy.newlines"

    const val SWAR = "swar"

    /** Whether this JVM could search with vectors, even when [PROPERTY] turns them off. */
    val vectorSupport: VectorSupport =
        vectorSupport(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent) { VectorNewlineSearch.bitSize }

    // A static final, so the JIT folds the choice.
    private val VECTOR = usesVectors(System.getProperty(PROPERTY), vectorSupport)

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

/** Whether a JVM can search for newlines with vectors, and why not. */
sealed interface VectorSupport {
    /** The CPU runs vectors of [bits] as SIMD instructions. */
    data class Supported(val bits: Int) : VectorSupport

    /** Started without `--add-modules jdk.incubator.vector`. */
    data object NoModule : VectorSupport

    /**
     * The CPU's preferred vectors have only [bits] bits, fewer than [MIN_VECTOR_BITS]: the CPU lacks AVX2, so
     * vectors would be slower than SWAR.
     */
    data class TooNarrow(val bits: Int) : VectorSupport
}

/** One way to search for newlines, with the contracts of [NewlineScanner.scan] and [NewlineScanner.indexOf]. */
internal interface NewlineSearch {
    fun scan(buffer: ByteBuffer, from: Int, to: Int, base: Long, out: LongChunkList)

    fun indexOf(buffer: ByteBuffer, from: Int, to: Int): Int
}

/** Narrower vectors run on SSE only, where the JIT leaves the Vector API as plain Java: 10× slower than SWAR. */
const val MIN_VECTOR_BITS = 256

/**
 * Whether vectors can be used: not without the module, else when the CPU's preferred vector width is wide enough.
 * [preferredBits] loads the Vector API, which throws NoClassDefFoundError without the module, so it is called only
 * once the module is there.
 */
internal fun vectorSupport(moduleLoaded: Boolean, preferredBits: () -> Int): VectorSupport {
    if (!moduleLoaded) return VectorSupport.NoModule
    val bits = preferredBits()
    return if (bits >= MIN_VECTOR_BITS) VectorSupport.Supported(bits) else VectorSupport.TooNarrow(bits)
}

/**
 * Whether to search with vectors: when [support] says the JVM can, unless [override], the value of
 * [NewlineScanner.PROPERTY], is [NewlineScanner.SWAR].
 */
internal fun usesVectors(override: String?, support: VectorSupport): Boolean =
    override != NewlineScanner.SWAR && support is VectorSupport.Supported
