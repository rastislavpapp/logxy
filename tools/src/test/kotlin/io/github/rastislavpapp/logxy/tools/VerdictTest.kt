package io.github.rastislavpapp.logxy.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class VerdictTest {

    @Test
    fun `is slower past the tolerance plus the noise allowance`() {
        // Limit: 0.050 s * 1.25 + 0.002 s = 0.0645 s.
        assertEquals(Verdict.SLOWER, verdict(0.065, baseline = 0.050, tolerance = 0.25, noise = 0.002))
        assertEquals(Verdict.OK, verdict(0.064, baseline = 0.050, tolerance = 0.25, noise = 0.002))
    }

    @Test
    fun `is faster below the tolerance minus the noise allowance`() {
        // Limit: 0.050 s * 0.75 - 0.002 s = 0.0355 s.
        assertEquals(Verdict.FASTER, verdict(0.035, baseline = 0.050, tolerance = 0.25, noise = 0.002))
        assertEquals(Verdict.OK, verdict(0.036, baseline = 0.050, tolerance = 0.25, noise = 0.002))
    }
}
