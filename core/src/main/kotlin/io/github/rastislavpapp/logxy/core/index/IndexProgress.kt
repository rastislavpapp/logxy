package io.github.rastislavpapp.logxy.core.index

import java.util.concurrent.atomic.AtomicLong

/** Progress of a background scan; written by worker threads, polled by the UI. */
class IndexProgress {

    @Volatile
    var totalBytes = 0L
        internal set

    private val done = AtomicLong()

    val bytesDone: Long get() = done.get()

    val fraction: Double get() = if (totalBytes == 0L) 0.0 else (bytesDone.toDouble() / totalBytes).coerceIn(0.0, 1.0)

    internal fun add(bytes: Long) {
        done.addAndGet(bytes)
    }

    internal fun reset() {
        done.set(0)
    }
}
