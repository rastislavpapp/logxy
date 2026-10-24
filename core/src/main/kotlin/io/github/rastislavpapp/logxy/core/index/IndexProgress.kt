package io.github.rastislavpapp.logxy.core.index

import java.util.concurrent.atomic.AtomicLong

/** Progress of a background scan; written by worker threads, polled by the UI. */
class IndexProgress {

    @Volatile
    var totalBytes = 0L
        internal set

    private val done = AtomicLong()

    val bytesDone: Long get() = done.get()

    /** [bytesDone] of [totalBytes], from 0 to 1; 0 until the total is known. */
    val fraction: Double get() = if (totalBytes == 0L) 0.0 else (bytesDone.toDouble() / totalBytes).coerceIn(0.0, 1.0)

    /** Counts [bytes] more as scanned; safe from any thread. */
    internal fun add(bytes: Long) {
        done.addAndGet(bytes)
    }

    /**
     * Starts counting from zero again, for when a parse starts over, e.g. because a dictionary column turned out
     * to hold mostly distinct values.
     */
    internal fun reset() {
        done.set(0)
    }
}
