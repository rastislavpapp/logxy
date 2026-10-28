package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.PlatformDetector.isMac
import io.github.rastislavpapp.logxy.core.PlatformDetector.isWindows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val BASELINE_FILE = Path.of("tools/benchmark-baseline.properties")

/** Recorded on first run on a machine that does not match [BASELINE_FILE]; gitignored. */
private val LOCAL_BASELINE_FILE = Path.of("tools/benchmark-baseline.local.properties")
private val DATA_DIR = Path.of("build/benchmark")

// Changing any of these changes what is measured, so the saved results no longer apply.
private const val MAIN_SIZE = 1L shl 30
private const val MERGE_FILES = 8
private const val MERGE_FILE_SIZE = 64L shl 20
private const val APPEND_SIZE = 64L shl 10
private const val RUNS = 3
private const val SETUP = "main=1g seed=1; merge=8x64m seeds=2..9; JMH single shot, $WARMUPS warm-ups and $RUNS runs " +
    "per JVM; median of the JVMs' bests"

/**
 * JVMs per row, set by `-PbenchmarkForks`: a JVM can settle into a slower state for its whole run, and the median
 * of 3 outvotes one. Each JVM costs about a second of start-up and setup, so more make the check slow.
 */
private val FORKS = System.getProperty("benchmark.forks")?.toInt() ?: 3

/**
 * Results may vary by this much on top of the tolerance. Measured: in 5 checks back to back on unchanged code on
 * the baseline machine (2026-10-14), every row stayed within 18% of the baseline except `follow: filter appended
 * records`, 3 ms, which came out up to 92% (1.3 ms past the tolerance) slower.
 */
private const val NOISE_SECONDS = 0.0015

enum class Verdict { SLOWER, OK, FASTER }

/** Compares [seconds] with [baseline]: a change is one by more than [tolerance] (a fraction) plus [noise] seconds. */
fun verdict(seconds: Double, baseline: Double, tolerance: Double, noise: Double): Verdict = when {
    seconds > baseline * (1 + tolerance) + noise -> Verdict.SLOWER
    seconds < baseline * (1 - tolerance) - noise -> Verdict.FASTER
    else -> Verdict.OK
}

/**
 * Set by `-PbenchmarkOnly=<row>`: the name of the one row to measure, as the check prints it, e.g. a row just added;
 * null for all of them.
 */
private val ONLY: String? = System.getProperty("benchmark.only")

/**
 * Measures indexing, parsing, typical filters, following and merging on fixed-seed generated files, the rows of the
 * `tools` commands, or only the row [ONLY] names. The files are generated into `build/benchmark` once.
 */
private fun measureAll(): Map<String, Timings> {
    val main = dataFile("main.log", MAIN_SIZE, 1)
    val appended = dataFile("appended.log", APPEND_SIZE, 10)
    val parts = (0 until MERGE_FILES).map { dataFile("merge-$it.log", MERGE_FILE_SIZE, 2L + it) }
    val groups = listOf(
        indexRows to indexParams(main),
        parseFileRows to parseFileParams(main),
        parseFilesRows to parseFilesParams(parts),
        filterRows to filterParams(main),
        followRows to followParams(main, appended),
        mergeRows to mergeParams(parts),
    )
    val runs = Runs(RUNS, FORKS)
    printHeader(runs)
    val results = LinkedHashMap<String, Timings>()
    for ((rows, params) in groups) results += measure(rows, params, runs, ::printRow, ONLY)
    if (ONLY != null && results.isEmpty()) {
        val names = groups.flatMap { (rows, params) -> rowNames(rows, params) }
        error(noSuchRow(ONLY, names))
    }
    return results
}

/**
 * A generated log with a fixed [seed], kept between runs; written aside first so an interrupted run leaves no
 * partial file.
 */
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
    val name = runCatching {
        when {
            isWindows() -> System.getenv("PROCESSOR_IDENTIFIER")
            isMac() -> ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start()
                .inputStream.bufferedReader().readText()
            else -> Files.readAllLines(Path.of("/proc/cpuinfo"))
                .firstOrNull { it.startsWith("model name") }?.substringAfter(':')
        }
    }.getOrNull()?.trim()
    return if (name.isNullOrEmpty()) System.getProperty("os.arch") else name
}

private fun load(file: Path): Properties? =
    if (Files.exists(file)) Properties().apply { Files.newBufferedReader(file).use { load(it) } } else null

private fun Properties.matches(machine: Map<String, String>) =
    machine.all { (key, value) -> getProperty(key) in setOf(null, value) }

/**
 * Whether the baseline was recorded with [FORKS] JVMs per row. A check may still compare with one recorded
 * differently, e.g. a quick `-PbenchmarkForks=1`, but its rows are noisier then.
 */
private fun Properties.sameForks() = getProperty("forks") == FORKS.toString()

private fun save(file: Path, machine: Map<String, String>, results: Map<String, Timings>, kept: Properties? = null) {
    val props = Properties()
    kept?.let { props.putAll(it) }
    props["setup"] = SETUP
    props["forks"] = FORKS.toString()
    props.putAll(machine)
    for ((name, times) in results) props.putIfAbsent(name, "%.4f".format(Locale.ROOT, times.best))
    val comment = if (file == BASELINE_FILE) {
        "Benchmark baseline, the median of each JVM's best time in seconds. Other machines record their own in " +
            "$LOCAL_BASELINE_FILE."
    } else {
        "Benchmark baseline of this machine, the median of each JVM's best time in seconds; delete it to record again."
    }
    Files.newBufferedWriter(file).use { props.store(it, comment) }
}

/**
 * Set by `-PbaselineRecord`: measures and writes the rows the baseline lacks into it (all of them if its setup or
 * its JVMs per row changed) instead of comparing. It writes the committed baseline when that was saved on this
 * machine or is missing, the local one otherwise.
 */
private val RECORD = System.getProperty("baseline.record") != null

private fun recordBaseline(committed: Properties?, local: Properties?, machine: Map<String, String>) {
    val file = if (committed == null || committed.matches(machine)) BASELINE_FILE else LOCAL_BASELINE_FILE
    val saved = if (file == BASELINE_FILE) committed else local
    val kept = saved?.takeIf { it.getProperty("setup") == SETUP && it.sameForks() && it.matches(machine) }
    // One row alone would leave every other row of a baseline recorded anew without a time.
    check(ONLY == null || kept != null) { "$file must be recorded again with all rows: drop -PbenchmarkOnly." }
    val results = measureAll()
    val added = results.keys.filter { kept?.getProperty(it) == null }
    save(file, machine, results, kept)
    println("\nRecorded in $file: ${added.joinToString().ifEmpty { "nothing, every row has a baseline" }}")
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
        if (RECORD) {
            recordBaseline(committed, local, machine)
            return
        }
        val (file, saved) = when {
            committed != null && committed.matches(machine) -> BASELINE_FILE to committed
            local != null && local.getProperty("setup") == SETUP && local.matches(machine) ->
                LOCAL_BASELINE_FILE to local
            else -> {
                check(ONLY == null) { "No baseline for this machine ($machine): drop -PbenchmarkOnly to record one." }
                println("No baseline for this machine ($machine), recording one in $LOCAL_BASELINE_FILE.")
                save(LOCAL_BASELINE_FILE, machine, measureAll())
                return
            }
        }
        val message = "The baseline in $file was saved for a different benchmark setup; record it again with " +
            "`./gradlew :tools:benchmark -PbaselineRecord`."
        assertEquals(SETUP, saved.getProperty("setup"), message)
        println("Comparing with $file")
        if (!saved.sameForks()) {
            println("The baseline was recorded with ${saved.getProperty("forks")} JVMs per row, this run uses $FORKS.")
        }
        val tolerance = TOLERANCE / 100
        val results = measureAll()
        println()
        val slower = ArrayList<String>()
        val missing = ArrayList<String>()
        for ((name, times) in results) {
            val before = saved.getProperty(name)?.toDouble()
            if (before == null) {
                println("%-58s no baseline".format(name))
                missing += name
                continue
            }
            val seconds = times.best
            val change = (seconds / before - 1) * 100
            val verdict = verdict(seconds, before, tolerance, NOISE_SECONDS)
            if (verdict == Verdict.SLOWER) {
                slower += "%s: %.3f s, baseline %.3f s (%+.1f%%)".format(name, seconds, before, change)
            }
            // A row whose JVMs disagree by more than the tolerance can pass or fail by chance.
            val noisy = if (times.spread > tolerance) ", noisy" else ""
            val label = when (verdict) {
                Verdict.SLOWER -> "SLOWER"
                Verdict.OK -> "ok"
                Verdict.FASTER -> "faster"
            }
            println(
                "%-58s %7.3f s  baseline %7.3f s  %+6.1f%%  spread %+4.0f%%  %s%s"
                    .format(name, seconds, before, change, times.spread * 100, label, noisy),
            )
        }
        // Measurements added since the local baseline was recorded join it, so they are checked from now on.
        // The committed one changes only on purpose: a row it lacks would otherwise go unchecked unnoticed.
        if (file == LOCAL_BASELINE_FILE && missing.isNotEmpty()) save(file, machine, results, saved)
        val report = "Slower than the baseline by more than %.0f%%:\n%s".format(TOLERANCE, slower.joinToString("\n"))
        assertTrue(slower.isEmpty(), report)
        val unrecorded = "No baseline in $file for: ${missing.joinToString()}; " +
            "record them with `./gradlew :tools:benchmark -PbaselineRecord`."
        assertTrue(file == LOCAL_BASELINE_FILE || missing.isEmpty(), unrecorded)
    }
}
