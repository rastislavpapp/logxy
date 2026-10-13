package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.PatternCondition
import io.github.rastislavpapp.logxy.core.filter.TextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.merge.MergedFilter
import io.github.rastislavpapp.logxy.core.merge.MergedLog
import io.github.rastislavpapp.logxy.core.parse.LogFormat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable

private val BASELINE_FILE = Path.of("tools/benchmark-baseline.properties")
/** Recorded on first run on a machine that does not match [BASELINE_FILE]; gitignored. */
private val LOCAL_BASELINE_FILE = Path.of("tools/benchmark-baseline.local.properties")
private val DATA_DIR = Path.of("build/benchmark")

// Changing any of these changes what is measured, so the saved results no longer apply.
private const val MAIN_SIZE = 1L shl 30
private const val MERGE_FILES = 8
private const val MERGE_FILE_SIZE = 64L shl 20
private const val RUNS = 5
private const val SETUP = "main=1g seed=1; merge=8x64m seeds=2..9; best of 5"

/** Results below this are mostly noise, so they may vary by this much on top of the tolerance. */
private const val NOISE_SECONDS = 0.01

/**
 * Measures parsing, typical filters and merging on fixed-seed generated files and returns the best
 * time in seconds of each. The files are generated into `build/benchmark` once.
 */
private fun measureAll(): Map<String, Double> {
    val results = LinkedHashMap<String, Double>()
    fun record(name: String, seconds: Double) {
        results[name] = seconds
        println("%-40s %7.3f s".format(name, seconds))
    }

    val main = dataFile("main.log", MAIN_SIZE, 1)
    val format = LogFormat.detect(main).compile() ?: error("No format detected for $main")
    var log: ParsedLog? = null
    record("parse 1 GB file", best {
        log?.close()
        log = null
        System.gc()
        log = runBlocking { ParsedLog.build(main, format) }
    })
    log!!.use { parsed ->
        val error = LevelCondition.atLeast("Level", Level.ERROR)
        val filters = listOf(
            "filter level >= WARN" to LevelCondition.atLeast("Level", Level.WARN),
            "filter thread = main" to TextCondition("Thread", TextOp.EQUALS, "main"),
            "filter message contains 'timeout'" to TextCondition("Message", TextOp.EQUALS, "*timeout*"),
            "filter ERROR and logger prefix" to
                Filter.And(listOf(error, TextCondition("Logger", TextOp.EQUALS, "com.example.order*"))),
            "filter message regex" to TextCondition("Message", TextOp.MATCHES, "order \\d{6}:"),
        )
        for ((name, filter) in filters) {
            // A new engine each time, as it caches what it computed.
            record(name, best { runBlocking { FilterEngine(parsed).prepare(filter).run() } })
        }
        // New engines find the templates anew; a used one only counts.
        record("patterns: count all", best { runBlocking { FilterEngine(parsed).countPatterns(null, parsed.recordCount) } })
        val engine = FilterEngine(parsed)
        val top = runBlocking { engine.countPatterns(null, parsed.recordCount) }.templates.first()!!
        record("patterns: count all again", best { runBlocking { engine.countPatterns(null, parsed.recordCount) } })
        val hide = Filter.Not(PatternCondition("Message", setOf(top)))
        record("filter hide most common pattern", best { runBlocking { FilterEngine(parsed).prepare(hide).run() } })
    }

    val parts = ArrayList<LogData>()
    try {
        val files = (0 until MERGE_FILES).map { dataFile("merge-$it.log", MERGE_FILE_SIZE, 2L + it) }
        record("merge: parse 8 files one by one", best {
            parts.forEach { it.close() }
            parts.clear()
            System.gc()
            for (file in files) {
                val compiled = LogFormat.detect(file).compile() ?: error("No format detected for $file")
                parts += runBlocking { ParsedLog.build(file, compiled) }
            }
        })
        // As a session opens a merge: several files at once, at most one per core.
        record("merge: parse 8 files at once", best {
            parts.forEach { it.close() }
            parts.clear()
            System.gc()
            val permits = Semaphore(Runtime.getRuntime().availableProcessors())
            parts += runBlocking {
                files.map { file ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            val compiled = LogFormat.detect(file).compile() ?: error("No format detected for $file")
                            ParsedLog.build(file, compiled)
                        }
                    }
                }.awaitAll()
            }
        })
        record("merge: merge 8 files", best { runBlocking { MergedLog.build(parts) } })
        val merged = runBlocking { MergedLog.build(parts) }
        val timeout = TextCondition("Message", TextOp.EQUALS, "*timeout*")
        record("merge: filter message contains 'timeout'", best { runBlocking { MergedFilter(merged).prepare(timeout, 0).run() } })
        record("merge: count patterns", best { runBlocking { MergedFilter(merged).countPatterns(null, merged.recordCount) } })
    } finally {
        parts.forEach { it.close() }
    }
    return results
}

private fun best(block: () -> Unit): Double = (1..RUNS).minOf {
    val started = System.nanoTime()
    block()
    (System.nanoTime() - started) / 1e9
}

/** A generated log with a fixed [seed], kept between runs; written aside first so an interrupted run leaves no partial file. */
private fun dataFile(name: String, size: Long, seed: Long): Path {
    val file = DATA_DIR.resolve(name)
    if (Files.exists(file) && Files.size(file) >= size) return file
    val partial = DATA_DIR.resolve("$name.partial")
    generateLog(partial, size, seed)
    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    return file
}

/** What the times depend on; a baseline applies to this machine if every key it records matches. */
private fun machine(): Map<String, String> = linkedMapOf(
    "cpu" to cpuName(),
    "cores" to Runtime.getRuntime().availableProcessors().toString(),
)

private fun cpuName(): String {
    val os = System.getProperty("os.name").lowercase()
    val name = runCatching {
        when {
            os.startsWith("windows") -> System.getenv("PROCESSOR_IDENTIFIER")
            os.startsWith("mac") -> ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start()
                .inputStream.bufferedReader().readText()
            else -> Files.readAllLines(Path.of("/proc/cpuinfo"))
                .firstOrNull { it.startsWith("model name") }?.substringAfter(':')
        }
    }.getOrNull()?.trim()
    return if (name.isNullOrEmpty()) System.getProperty("os.arch") else name
}

private fun load(file: Path): Properties? =
    if (Files.exists(file)) Properties().apply { Files.newBufferedReader(file).use { load(it) } } else null

private fun Properties.matches(machine: Map<String, String>) = machine.all { (key, value) -> getProperty(key) in setOf(null, value) }

private fun save(file: Path, machine: Map<String, String>, results: Map<String, Double>, kept: Properties? = null) {
    val props = Properties()
    kept?.let { props.putAll(it) }
    props["setup"] = SETUP
    props.putAll(machine)
    for ((name, seconds) in results) props.putIfAbsent(name, "%.4f".format(Locale.ROOT, seconds))
    Files.newBufferedWriter(file).use {
        props.store(it, "Benchmark baseline of this machine, best times in seconds; delete it to record again.")
    }
}

/** Allowed slowdown in percent. */
private val TOLERANCE = System.getProperty("baseline.tolerance")?.toDouble() ?: 25.0

/**
 * Speed regression check; slow and machine-specific, so it runs only through `./gradlew :tools:benchmark`.
 * Skipped on CI, whose runners are too noisy and differ from the saved machine.
 */
@Tag("benchmark")
@DisabledIfEnvironmentVariable(named = "CI", matches = ".+")
class BaselineTest {

    @Test
    fun `is not slower than the saved baseline`() {
        val machine = machine()
        val committed = load(BASELINE_FILE)
        val local = load(LOCAL_BASELINE_FILE)
        val (file, saved) = when {
            committed != null && committed.matches(machine) -> BASELINE_FILE to committed
            local != null && local.getProperty("setup") == SETUP && local.matches(machine) -> LOCAL_BASELINE_FILE to local
            else -> {
                println("No baseline for this machine ($machine), recording one in $LOCAL_BASELINE_FILE.")
                save(LOCAL_BASELINE_FILE, machine, measureAll())
                return
            }
        }
        assertEquals(SETUP, saved.getProperty("setup"), "The baseline in $file was saved for a different benchmark setup.")
        println("Comparing with $file")
        val tolerance = TOLERANCE / 100
        val results = measureAll()
        println()
        val slower = ArrayList<String>()
        for ((name, seconds) in results) {
            val before = saved.getProperty(name)?.toDouble()
            if (before == null) {
                println("%-40s no baseline".format(name))
                continue
            }
            val limit = before * (1 + tolerance) + NOISE_SECONDS
            val change = (seconds / before - 1) * 100
            val verdict = when {
                seconds > limit -> "SLOWER".also { slower += "%s: %.3f s, baseline %.3f s (%+.1f%%)".format(name, seconds, before, change) }
                seconds < before * (1 - tolerance) - NOISE_SECONDS -> "faster"
                else -> "ok"
            }
            println("%-40s %7.3f s  baseline %7.3f s  %+6.1f%%  %s".format(name, seconds, before, change, verdict))
        }
        // Measurements added since the local baseline was recorded join it, so they are checked from now on.
        if (file == LOCAL_BASELINE_FILE && results.keys.any { saved.getProperty(it) == null }) save(file, machine, results, saved)
        assertTrue(slower.isEmpty(), "Slower than the baseline by more than %.0f%%:\n%s".format(TOLERANCE, slower.joinToString("\n")))
    }
}
