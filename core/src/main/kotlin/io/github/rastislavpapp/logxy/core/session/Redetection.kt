package io.github.rastislavpapp.logxy.core.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Settings for detecting the parser of a single file again while it reads as Raw text. A process's output can start
 * with a preamble, so the first detection may see no records yet and find no parser.
 *
 * @property window how long after the session starts a detection is retried.
 * @property gap the least time between two detections while the file grows.
 */
class Redetection(val window: Duration = 30.seconds, val gap: Duration = 1.seconds)

/**
 * Tells a session with [Redetection] when to detect again: inside the window from [start], at most once per gap
 * after the file [grew], and once more when its writer ended, whatever the gap. Not thread-safe apart from
 * [writerEnded].
 */
internal class RedetectionClock(
    private val redetection: Redetection,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private var started: TimeMark? = null
    private var detected: TimeMark? = null
    private var grown = false

    @Volatile private var ended = false
    private var endDetected = false

    fun start() {
        started = time.markNow()
        detected = started
    }

    fun grew() {
        grown = true
    }

    /** Callable from any thread. */
    fun writerEnded() {
        ended = true
    }

    /** Whether to detect now; a true counts as the detection. */
    fun due(): Boolean {
        val started = started ?: return false
        if (started.elapsedNow() > redetection.window) return false
        if (ended && !endDetected) {
            endDetected = true
            return true
        }
        if (!grown || detected!!.elapsedNow() < redetection.gap) return false
        grown = false
        detected = time.markNow()
        return true
    }
}
