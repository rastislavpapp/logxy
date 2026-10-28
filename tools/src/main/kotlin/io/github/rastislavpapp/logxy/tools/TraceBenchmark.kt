package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.session.traces
import io.github.rastislavpapp.logxy.core.trace.Traces
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.file.Path

/** The trace id column of the log [RequestGenerator] writes. */
private const val REQUEST_ID = "MDC[requestId]"

/**
 * Building the traces of [file], a log of [RequestGenerator]'s, by its request ids, from scratch as a session first
 * does. Open so JMH can generate its subclass.
 */
@State(Scope.Benchmark)
open class TraceBenchmark {
    @Param("")
    @JvmField
    var file = ""

    private lateinit var log: ParsedLog

    @Setup
    fun setUp() {
        val path = Path.of(file)
        log = runBlocking { ParsedLog.build(path, parserFor(path).compileFor(path)) }
    }

    @TearDown
    fun tearDown() = log.close()

    @Benchmark
    fun byRequestId(): Traces = runBlocking { traces(log, REQUEST_ID) } ?: error("$file has no $REQUEST_ID column")
}

val traceRows = listOf(BenchmarkRow(TraceBenchmark::class.java, "byRequestId", "traces: by request id"))

/** Parameters of [TraceBenchmark]. */
fun traceParams(file: Path): Map<String, List<String>> = mapOf(TraceBenchmark::file.name to listOf(file.toString()))
