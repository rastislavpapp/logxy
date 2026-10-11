package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.formatByteSize
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.SplittableRandom

private const val BATCHES_PER_SECOND = 10
private const val KEPT_ROTATIONS = 5

/** Appends log lines in small batches, ending each batch mid-line to exercise partial-line handling. */
fun simulateLogging(
    file: Path,
    linesPerSecond: Int,
    rotateAt: Long?,
    mdc: Boolean = false,
    json: Boolean = false,
    rabbitmq: Boolean = false,
) {
    require(linesPerSecond > 0) { "rate must be positive" }
    val random = SplittableRandom()
    val generator = if (rabbitmq) RabbitMqGenerator(random) else LogGenerator(random, mdc, json = json)
    val rotation = rotateAt?.let { ", rotating at ${formatByteSize(it)}" } ?: ""
    println("Appending about $linesPerSecond lines/s to $file$rotation. Ctrl+C stops.")

    var out = open(file)
    var pending = ""
    var carry = 0.0
    while (true) {
        generator.timeMillis = System.currentTimeMillis()
        carry += linesPerSecond.toDouble() / BATCHES_PER_SECOND
        val lines = carry.toInt()
        carry -= lines
        val batch = StringBuilder(pending)
        repeat(lines) { batch.append(generator.next()) }
        // Hold back the second half of the last record until the next batch.
        val split = if (lines > 0) batch.length - batch.length.coerceAtMost(40) / 2 else batch.length
        out.write(batch.substring(0, split).toByteArray())
        out.flush()
        pending = batch.substring(split)

        if (rotateAt != null && Files.size(file) >= rotateAt) {
            out.write(pending.toByteArray())
            out.close()
            pending = ""
            rotate(file)
            println("Rotated $file")
            out = open(file)
        }
        Thread.sleep(1000L / BATCHES_PER_SECOND)
    }
}

private fun open(file: Path): OutputStream =
    Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

private fun rotate(file: Path) {
    fun numbered(n: Int) = file.resolveSibling("${file.fileName}.$n")
    for (n in KEPT_ROTATIONS - 1 downTo 1) {
        if (Files.exists(numbered(n))) Files.move(numbered(n), numbered(n + 1), StandardCopyOption.REPLACE_EXISTING)
    }
    Files.move(file, numbered(1), StandardCopyOption.REPLACE_EXISTING)
}
