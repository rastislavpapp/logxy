package io.github.rastislavpapp.logxy.tools

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.openjdk.jmh.runner.options.VerboseMode
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Uncounted runs before the measured ones in each JVM, enough for the JIT to compile the hot loops. */
const val WARMUPS = 3

/** How a row is measured: [warmups] uncounted then [iterations] measured runs in each of [forks] JVMs. */
class Runs(val iterations: Int, val forks: Int, val warmups: Int = WARMUPS)

/**
 * A JMH benchmark method, [method] of [type], and the name of its row; [name] gets the parameters, so a method
 * measured once per value of a parameter makes one row per value.
 */
class Row(type: Class<*>, method: String, val name: (BenchmarkParams) -> String) {
    val id = "${type.name}.$method"
}

/**
 * Measures [rows] with JMH, one row after another, and gives each to [report] as it is done. One run is one call
 * (single shot): what is measured is a whole parse or filter, long enough to time alone. Every row runs in JVMs of
 * its own, forked with this one's `java.home`, classpath and JVM arguments. [params] go to every benchmark that
 * declares them, by name.
 */
fun measure(
    rows: List<Row>,
    params: Map<String, List<String>>,
    runs: Runs,
    report: (String, RowTimes) -> Unit = { _, _ -> },
): Map<String, RowTimes> {
    val times = LinkedHashMap<String, RowTimes>()
    for (row in rows) {
        val options = OptionsBuilder()
            .include("^${Pattern.quote(row.id)}$")
            .mode(Mode.SingleShotTime)
            .timeUnit(TimeUnit.SECONDS)
            .warmupIterations(runs.warmups)
            .measurementIterations(runs.iterations)
            .forks(runs.forks)
            .shouldFailOnError(true)
            .verbosity(VerboseMode.SILENT)
        for ((name, values) in params) options.param(name, *values.toTypedArray())
        // JMH orders the runs by parameter text; the rows keep the order of the given values.
        val results = Runner(options.build()).run().sortedBy { result ->
            params.entries.fold(0) { order, (name, values) ->
                order * (values.size + 1) + values.indexOf(result.params.getParam(name)) + 1
            }
        }
        for (result in results) {
            val jvms = result.benchmarkResults.map { fork ->
                fork.iterationResults.map { it.primaryResult.score }.toDoubleArray()
            }
            val name = row.name(result.params)
            times[name] = RowTimes(jvms)
            report(name, times.getValue(name))
        }
    }
    return times
}

/** What [printRow]'s columns mean. */
fun printHeader(runs: Runs) {
    if (runs.forks == 1) {
        println("Best and median of ${runs.iterations} runs after ${runs.warmups} uncounted; spread: slowest run")
    } else {
        println(
            "${runs.forks} JVMs, ${runs.iterations} runs each after ${runs.warmups} uncounted. Best and median: " +
                "the median of each JVM's; spread: the slowest JVM's best",
        )
    }
}

fun printRow(name: String, times: RowTimes) {
    val spread = times.spread * 100
    println("%-58s best %7.3f s  median %7.3f s  spread %+5.0f%%".format(name, times.best, times.median, spread))
}

/**
 * Times of one row in seconds, the measured runs of each JVM. A JVM can settle into a slower state for its whole
 * run, so its runs agree with each other but not with another JVM's: the statistics take each JVM's own first.
 */
class RowTimes(val jvms: List<DoubleArray>) {
    /** The median of each JVM's best run: what the check compares. */
    val best = median(jvms.map { it.min() })

    /** The median of each JVM's median run. */
    val median = median(jvms.map { median(it.toList()) })

    /**
     * How much slower the slowest is than the fastest, as a fraction: of the JVMs' best runs, or with one JVM, of
     * its runs.
     */
    val spread = if (jvms.size > 1) {
        jvms.maxOf { it.min() } / jvms.minOf { it.min() } - 1
    } else {
        jvms.single().max() / jvms.single().min() - 1
    }
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
