package io.github.rastislavpapp.logxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteSizeTest {

    @Test
    fun `bytes below one kilobyte are shown exactly`() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1023 B", formatByteSize(1023))
    }

    @Test
    fun `larger sizes use binary units`() {
        assertEquals("1.00 KB", formatByteSize(1024))
        assertEquals("1.50 KB", formatByteSize(1536))
        assertEquals("10.0 MB", formatByteSize(10L * 1024 * 1024))
        assertEquals("2.00 GB", formatByteSize(2L * 1024 * 1024 * 1024))
        assertEquals("512 GB", formatByteSize(512L * 1024 * 1024 * 1024))
        assertEquals("8.00 EB", formatByteSize(Long.MAX_VALUE))
    }

    @Test
    fun `negative sizes are rejected`() {
        assertFailsWith<IllegalArgumentException> { formatByteSize(-1) }
    }
}
