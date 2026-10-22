package io.github.rastislavpapp.logxy.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RedetectionClockTest {

    private val time = TestTimeSource()
    private val clock = RedetectionClock(Redetection(window = 30.seconds, gap = 1.seconds), time).also { it.start() }

    @Test
    fun `output growing many times a second is detected at most once a second`() {
        var detections = 0
        // Ten seconds of output growing every 50 ms.
        repeat(200) {
            time += 50.milliseconds
            clock.grew()
            if (clock.due()) detections++
        }
        assertEquals(10, detections)
    }

    @Test
    fun `nothing is detected while the file does not grow`() {
        time += 5.seconds
        assertFalse(clock.due())
    }

    @Test
    fun `nothing is detected after the window`() {
        time += 31.seconds
        clock.grew()
        assertFalse(clock.due())
        clock.writerEnded()
        assertFalse(clock.due())
    }

    @Test
    fun `the writer's end is detected once, whatever the gap`() {
        clock.writerEnded()
        assertTrue(clock.due())
        assertFalse(clock.due())
    }

    @Test
    fun `nothing is detected before the start`() {
        val unstarted = RedetectionClock(Redetection(), time)
        unstarted.grew()
        time += 2.seconds
        assertFalse(unstarted.due())
    }
}
