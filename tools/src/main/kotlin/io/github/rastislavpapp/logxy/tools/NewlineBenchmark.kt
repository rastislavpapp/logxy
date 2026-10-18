package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.index.LongChunkList
import io.github.rastislavpapp.logxy.core.index.NewlineScanner
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Experiment: the newline scan alone, on the first 8 MB of [file] in memory. Which scanner runs is chosen per JVM
 * with `-Dlogxy.newlines=vector` (and `-Dlogxy.vectorBits`), see [NewlineScanner].
 */
@State(Scope.Benchmark)
open class NewlineBenchmark {
    @Param("")
    @JvmField
    var file = ""

    private lateinit var direct: ByteBuffer
    private lateinit var heap: ByteBuffer
    private var size = 0

    @Setup
    fun setUp() {
        FileChannel.open(Path.of(file), StandardOpenOption.READ).use { channel ->
            size = minOf(channel.size(), 8L shl 20).toInt()
            direct = ByteBuffer.allocateDirect(size)
            channel.read(direct, 0)
        }
        val bytes = ByteArray(size)
        direct.get(0, bytes)
        heap = ByteBuffer.wrap(bytes)
    }

    /** Line indexing: every line start of a direct buffer, as LineIndex scans the file. */
    @Benchmark
    fun scan(): Int {
        val out = LongChunkList()
        NewlineScanner.scan(direct, 0, size, 0, out)
        return out.size
    }

    /** Parsing: the next newline, line after line of a heap buffer, as LineReader reads the file. */
    @Benchmark
    fun indexOfPerLine(): Int {
        var lines = 0
        var i = 0
        while (true) {
            val newline = NewlineScanner.indexOf(heap, i, size)
            if (newline < 0) return lines
            lines++
            i = newline + 1
        }
    }
}
