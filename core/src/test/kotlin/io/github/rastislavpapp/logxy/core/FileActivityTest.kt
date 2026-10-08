package io.github.rastislavpapp.logxy.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FileActivityTest {

    private val now = Instant.parse("2026-10-08T12:00:00Z")

    @Test
    fun `old unchanged file is static`() {
        assertEquals(FileActivity.STATIC, FileActivity.classify(now.minusSeconds(3600), 100, 100, now))
    }

    @Test
    fun `recently modified file is live`() {
        assertEquals(FileActivity.LIVE, FileActivity.classify(now.minusSeconds(10), 100, 100, now))
    }

    @Test
    fun `size change while indexing means live`() {
        assertEquals(FileActivity.LIVE, FileActivity.classify(now.minusSeconds(3600), 100, 150, now))
    }

    @Test
    fun `modification time in the future means live`() {
        assertEquals(FileActivity.LIVE, FileActivity.classify(now.plusSeconds(600), 100, 100, now))
    }
}
