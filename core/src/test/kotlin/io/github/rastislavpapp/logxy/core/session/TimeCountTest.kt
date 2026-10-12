package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.index.IndexProgress
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.parse.PatternFormat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeCountTest {

    @TempDir
    lateinit var dir: Path

    private val format = PatternFormat("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n", ZoneOffset.UTC)

    private fun TimeHistogram.total() = (firstBin..lastBin).sumOf { bin -> (0..<TimeHistogram.CATEGORIES).sumOf { count(it, bin) } }

    @Test
    fun `fills up from the earliest times, reaching all expected ones`() {
        val content = buildString {
            for (i in 0..<1000) append("2026-10-01 08:%02d:%02d.000 [t] ${if (i % 10 == 0) "ERROR" else "INFO "} a.B - m\n".format(i / 60, i % 60))
        }
        val file = Files.writeString(dir.resolve("count.log"), content)
        runBlocking { ParsedLog.build(file, format, IndexProgress()) }.use { log ->
            val from = log.timestampNanos(0)
            val to = log.timestampNanos(999)
            val partials = ArrayList<TimeHistogram>()
            lateinit var before: TimeHistogram
            lateinit var after: TimeHistogram
            // No progress is reported if the first task happens to finish last, so count again until some is.
            repeat(50) {
                if (partials.isNotEmpty()) return@repeat
                val result = runBlocking {
                    TimeCount(log, null, 0, 1000, 600, from, to, taskRows = 100).run { synchronized(partials) { partials += it } }
                }
                before = result.first
                after = result.second
            }
            assertEquals(600, before.total())
            assertEquals(400, after.total())
            assertEquals(100, (before.firstBin..before.lastBin).sumOf { before.count(TimeHistogram.ERROR, it) } +
                (after.firstBin..after.lastBin).sumOf { after.count(TimeHistogram.ERROR, it) })
            assertTrue(partials.isNotEmpty())
            for ((i, partial) in partials.withIndex()) {
                // Each counts a run of whole tasks from the first, and reaches the last expected time.
                assertEquals(0, partial.total() % 100)
                if (i > 0) assertTrue(partial.total() > partials[i - 1].total())
                assertTrue(partial.binStart(partial.lastBin + 1) > to)
                assertTrue(partial.binStart(partial.firstBin) <= from)
            }
        }
    }
}
