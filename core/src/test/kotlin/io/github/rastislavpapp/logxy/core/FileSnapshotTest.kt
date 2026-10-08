package io.github.rastislavpapp.logxy.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSnapshotTest {

    @TempDir
    lateinit var dir: Path

    private fun file(content: String) = Files.writeString(dir.resolve("app.log"), content)

    @Test
    fun `unchanged and grown`() {
        val file = file("line 1\n")
        val before = FileSnapshot.take(file)!!
        assertEquals(FileChange.UNCHANGED, before.changeTo(FileSnapshot.take(file), 7))
        Files.writeString(file, "line 2\n", StandardOpenOption.APPEND)
        assertEquals(FileChange.GROWN, before.changeTo(FileSnapshot.take(file), 7))
    }

    @Test
    fun `truncated file has shrunk`() {
        val file = file("line 1\nline 2\n")
        val before = FileSnapshot.take(file)!!
        Files.writeString(file, "new\n")
        assertEquals(FileChange.SHRUNK, before.changeTo(FileSnapshot.take(file), 14))
    }

    @Test
    fun `rotation by rename is a replacement`() {
        val file = file("old content\n")
        val before = FileSnapshot.take(file)!!
        Files.move(file, dir.resolve("app.log.1"))
        Files.writeString(file, "a new file that is already longer\n")
        assertEquals(FileChange.REPLACED, before.changeTo(FileSnapshot.take(file), 12))
    }

    @Test
    fun `same identity with different first bytes is a replacement`() {
        val now = Instant.now()
        val before = FileSnapshot(10, now, "id", "2026-10-01 a".toByteArray())
        val sameStart = FileSnapshot(20, now, "id", "2026-10-01 a and more".toByteArray())
        val otherStart = FileSnapshot(20, now, "id", "2026-10-02 b and more".toByteArray())
        assertEquals(FileChange.GROWN, before.changeTo(sameStart, 10))
        assertEquals(FileChange.REPLACED, before.changeTo(otherStart, 10))
    }

    @Test
    fun `deleted file is missing`() {
        val file = file("x\n")
        val before = FileSnapshot.take(file)!!
        Files.delete(file)
        assertNull(FileSnapshot.take(file))
        assertEquals(FileChange.MISSING, before.changeTo(null, 2))
    }
}
