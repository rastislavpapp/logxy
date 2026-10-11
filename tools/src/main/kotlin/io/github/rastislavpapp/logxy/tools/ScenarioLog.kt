package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.formatByteSize
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln

private const val SECOND = 1000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Milliseconds between records, at load 1, of the run that measures how large records are. */
private const val CALIBRATION_GAP = 100.0

/**
 * One day of a service's log, with things in it worth finding: traffic that follows the day, a nightly
 * batch job, a pool leak whose warnings grow all day, an incident at 14:20 (warnings building up, a burst
 * of errors, then a restart with no logs for 3 minutes) and a short error spike at 19:40. The rate is chosen
 * so that [size] bytes cover about the whole day; the log ends at [size] or at midnight.
 */
fun generateScenarioLog(
    file: Path,
    size: Long,
    seed: Long,
    mdc: Boolean = false,
    json: Boolean = false,
    rabbitmq: Boolean = false,
) {
    // Errors and warnings are only written where the scenario places them.
    val placed = setOf("ERROR", "WARN")
    val day = Day(LocalDate.of(2026, 10, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    fun write(perMilli: Double, random: SplittableRandom, sink: (ByteArray) -> Boolean) {
        val generator = if (rabbitmq) RabbitMqGenerator(random, placed) else LogGenerator(random, mdc, placed, json)
        var time = day.start.toDouble()
        while (time < day.start + DAY) {
            time += -ln(1 - random.nextDouble()) / (perMilli * day.load(time.toLong()))
            day.gapEnd(time.toLong())?.let { time = it.toDouble() }
            if (time >= day.start + DAY) return
            val at = time.toLong()
            // The generator adds a few milliseconds of its own; it gets the time just before.
            generator.timeMillis = at - 3
            val roll = random.nextDouble()
            val level = when {
                roll < day.errorShare(at) -> "ERROR"
                roll < day.errorShare(at) + day.warningShare(at) -> "WARN"
                else -> null
            }
            if (!sink(generator.next(level).toByteArray())) return
        }
    }

    // A thin run over the whole day tells how many bytes the mix of records takes.
    var sampleBytes = 0L
    write(1.0 / CALIBRATION_GAP, SplittableRandom(seed + 1)) { sampleBytes += it.size; true }
    val perMilli = size.toDouble() / sampleBytes / CALIBRATION_GAP

    val started = System.nanoTime()
    var written = 0L
    var lines = 0L
    Files.createDirectories(file.parent)
    BufferedOutputStream(Files.newOutputStream(file), 1 shl 20).use { out ->
        write(perMilli, SplittableRandom(seed)) { record ->
            out.write(record)
            written += record.size
            lines++
            written < size
        }
    }
    val seconds = (System.nanoTime() - started) / 1e9
    println("Wrote ${formatByteSize(written)} ($lines records) to $file in %.1f s".format(seconds))
}

/** What happens over the day starting at [start], in epoch milliseconds. */
private class Day(val start: Long) {
    private val batch = at(2, 0)..<at(2, 12)
    private val buildUp = at(14, 5)..<at(14, 20)
    private val incident = at(14, 20)..<at(14, 24)
    private val restart = at(14, 24)..<at(14, 27)
    private val spike = at(19, 40)..<at(19, 42)

    private fun at(hour: Int, minute: Int) = start + hour * HOUR + minute * MINUTE

    /** Records per time, relative: quiet nights, a busy afternoon, a heavy batch job at night. */
    fun load(time: Long): Double {
        val hour = (time - start).toDouble() / HOUR
        var load = 0.08 + 0.3 * bump(hour, 10.0, 1.5) + 0.9 * bump(hour, 15.0, 3.5)
        if (time in batch) load += 1.5
        // Clients retrying make failures louder.
        if (time in incident) load *= 1.6
        if (time in spike) load *= 3.0
        return load
    }

    private fun bump(x: Double, center: Double, width: Double) = exp(-((x - center) / width).let { it * it })

    /** Where the service was down, the time logging starts again. */
    fun gapEnd(time: Long): Long? = if (time in restart) restart.last + 1 else null

    fun errorShare(time: Long): Double = when (time) {
        in incident -> 0.35
        in spike -> 0.4
        in batch -> 0.0
        else -> 0.003
    }

    fun warningShare(time: Long): Double {
        val dayPart = ((time - start).toDouble() / DAY).coerceIn(0.0, 1.0)
        // A leaking pool: its warnings slowly grow over the day.
        val leak = 0.005 + 0.03 * dayPart
        return when (time) {
            in buildUp -> leak + 0.25 * (time - buildUp.first) / (buildUp.last - buildUp.first)
            in incident -> 0.25
            in batch -> 0.0
            else -> leak
        }
    }
}
