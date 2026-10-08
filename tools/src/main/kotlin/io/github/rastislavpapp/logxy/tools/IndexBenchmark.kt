package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.formatByteSize
import io.github.rastislavpapp.logxy.core.index.IndexOptions
import io.github.rastislavpapp.logxy.core.index.LineIndex
import io.github.rastislavpapp.logxy.core.index.LongChunkList
import io.github.rastislavpapp.logxy.core.index.NewlineScanner
import kotlinx.coroutines.runBlocking
import java.lang.foreign.Arena
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.random.Random

private const val BUFFER_SIZE = 1 shl 20
private const val MAX_MAPPING = 1L shl 30

/**
 * Compares positional reads with short-lived memory mappings for the newline scan, single- and
 * multi-threaded, then measures the full [LineIndex] build and random line access.
 * Runs on a warm page cache after the first iteration.
 */
fun benchmarkIndexing(file: Path, iterations: Int) {
    val threads = Runtime.getRuntime().availableProcessors()
    FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        println("File: $file (${formatByteSize(size)}), $threads CPUs, $iterations iterations\n")
        val variants = listOf(
            "pread, 1 thread" to { scan(channel, 1, ::preadRange) },
            "pread, $threads threads" to { scan(channel, threads, ::preadRange) },
            "mmap,  1 thread" to { scan(channel, 1, ::mappedRange) },
            "mmap,  $threads threads" to { scan(channel, threads, ::mappedRange) },
        )
        val newlineCounts = variants.map { (name, run) -> measure(name, size, iterations, run) }
        check(newlineCounts.distinct().size == 1) { "Variants disagree on newline count: $newlineCounts" }
    }

    println()
    var index: LineIndex? = null
    measure("LineIndex.build (default options)", Files.size(file), iterations) {
        index?.close()
        val built = runBlocking { LineIndex.build(file, options = IndexOptions()) }
        index = built
        built.lineCount.toLong()
    }
    index!!.use { measureRandomAccess(it) }
}

private fun measure(name: String, bytes: Long, iterations: Int, run: () -> Long): Long {
    var result = 0L
    val times = DoubleArray(iterations) {
        val started = System.nanoTime()
        result = run()
        (System.nanoTime() - started) / 1e9
    }
    val sorted = times.sorted()
    val best = sorted.first()
    val median = sorted[sorted.size / 2]
    println(
        "%-36s best %6.3f s (%5.2f GB/s)   median %6.3f s   first %6.3f s   -> %,d".format(
            name, best, bytes / best / 1e9, median, times.first(), result,
        )
    )
    return result
}

private fun scan(channel: FileChannel, threads: Int, scanRange: (FileChannel, Long, Long) -> Int): Long {
    val size = channel.size()
    val rangeSize = size / threads
    Executors.newFixedThreadPool(threads).use { pool ->
        val futures = List(threads) { i ->
            val start = i * rangeSize
            val end = if (i == threads - 1) size else start + rangeSize
            pool.submit<Int> { scanRange(channel, start, end) }
        }
        return futures.sumOf { it.get().toLong() }
    }
}

private fun preadRange(channel: FileChannel, start: Long, end: Long): Int {
    val out = LongChunkList()
    Arena.ofConfined().use { arena ->
        val buffer = arena.allocate(BUFFER_SIZE.toLong(), 8).asByteBuffer()
        var position = start
        while (position < end) {
            buffer.clear().limit(minOf(BUFFER_SIZE.toLong(), end - position).toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) break
            }
            NewlineScanner.scan(buffer, 0, buffer.position(), position, out)
            position += buffer.position()
        }
    }
    return out.size
}

private fun mappedRange(channel: FileChannel, start: Long, end: Long): Int {
    val out = LongChunkList()
    Arena.ofConfined().use { arena ->
        var position = start
        while (position < end) {
            val length = minOf(MAX_MAPPING, end - position)
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, position, length, arena).asByteBuffer()
            NewlineScanner.scan(buffer, 0, length.toInt(), position, out)
            position += length
        }
    }
    return out.size
}

private fun measureRandomAccess(index: LineIndex) {
    val random = Random(1)
    val samples = 200_000
    var chars = 0L
    repeat(10_000) { chars += index.lineText(random.nextInt(index.lineCount), 4096).length }
    val started = System.nanoTime()
    repeat(samples) { chars += index.lineText(random.nextInt(index.lineCount), 4096).length }
    val micros = (System.nanoTime() - started) / 1e3 / samples
    println("\nRandom lineText (max 4096 bytes): %.2f µs per line (%,d lines)".format(micros, index.lineCount))
}
