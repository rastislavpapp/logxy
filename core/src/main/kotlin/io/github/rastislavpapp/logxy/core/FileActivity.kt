package io.github.rastislavpapp.logxy.core

import java.time.Duration
import java.time.Instant

/** Whether a file looks finished or is still being written to. A heuristic, not a guarantee. */
enum class FileActivity {
    STATIC,
    LIVE,
    ;

    companion object {
        val DEFAULT_LIVE_WINDOW: Duration = Duration.ofSeconds(60)

        /**
         * A file is live if it was modified within [liveWindow] or its size changed while it was indexed.
         * A modification time in the future (clock skew) also counts as recent.
         */
        fun classify(
            lastModified: Instant,
            sizeWhenIndexed: Long,
            currentSize: Long,
            now: Instant = Instant.now(),
            liveWindow: Duration = DEFAULT_LIVE_WINDOW,
        ): FileActivity = when {
            sizeWhenIndexed != currentSize -> LIVE
            Duration.between(lastModified, now) < liveWindow -> LIVE
            else -> STATIC
        }
    }
}
