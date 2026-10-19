package io.github.rastislavpapp.logxy.core.index

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class NewlineSearchChoiceTest {

    @Test
    fun `vectors of 256 bits or more are used`() {
        assertTrue(usesVectors(override = null, moduleLoaded = true) { 256 })
        assertTrue(usesVectors(override = null, moduleLoaded = true) { 512 })
    }

    @Test
    fun `narrower vectors fall back to SWAR, as SSE-only CPUs run them as plain Java`() {
        assertFalse(usesVectors(override = null, moduleLoaded = true) { 128 })
        assertFalse(usesVectors(override = null, moduleLoaded = true) { 64 })
    }

    @Test
    fun `without the module the vector width is never asked for`() {
        assertFalse(usesVectors(override = null, moduleLoaded = false) { fail("loads the Vector API") })
    }

    @Test
    fun `the swar override wins over any vector width`() {
        assertFalse(usesVectors(override = "swar", moduleLoaded = true) { 512 })
    }

    @Test
    fun `an override other than swar does not force vectors`() {
        assertFalse(usesVectors(override = "vector", moduleLoaded = true) { 128 })
    }
}
