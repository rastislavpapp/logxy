package io.github.rastislavpapp.logxy.core.trace

import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceStepsTest {

    private fun assertWithinOnePercent(exact: Long, estimate: Long, what: String) =
        assertTrue(abs(estimate - exact) <= exact / 100, "$what: $estimate, exactly $exact")

    @Test
    fun `a pair's median and p95 are within 1% of the exact nearest rank`() {
        val random = SplittableRandom(152)
        for (round in 0..<20) {
            val counter = StepCounter()
            // Spread over many orders of magnitude, from a microsecond to a minute.
            val durations = LongArray(1 + random.nextInt(5_000)) {
                exp(random.nextDouble(ln(1_000.0), ln(60e9))).toLong()
            }
            for ((trace, duration) in durations.withIndex()) counter.add(0, 1, duration, trace)
            val steps = counter.toSteps(listOf("a", "b"))
            val exact = DurationStatistics.of(durations)!!
            assertWithinOnePercent(exact.median, steps.median(0), "median, round $round")
            assertWithinOnePercent(exact.p95, steps.p95(0), "p95, round $round")
        }
    }

    @Test
    fun `a pair of one step, or of equal steps, has that time as its median and p95 exactly`() {
        val counter = StepCounter()
        counter.add(0, 1, 610_000_000, trace = 0)
        repeat(3) { counter.add(1, 2, 20_109_000, trace = it) }
        val steps = counter.toSteps(listOf("a", "b", "c"))
        assertEquals(listOf(610_000_000L, 610_000_000L), listOf(steps.median(0), steps.p95(0)))
        assertEquals(listOf(20_109_000L, 20_109_000L), listOf(steps.median(1), steps.p95(1)))
    }

    @Test
    fun `steps of 0, and a step taken back, leave the percentiles exact where they can be`() {
        val counter = StepCounter()
        repeat(3) { counter.add(0, 1, 0, trace = it) }
        counter.add(0, 1, 1_000_000, trace = 3)
        counter.remove(0, 1, 1_000_000, trace = 3)
        val steps = counter.toSteps(listOf("a", "b"))
        assertEquals(listOf(3L, 0L, 0L), listOf(steps.count(0).toLong(), steps.median(0), steps.p95(0)))
    }
}
