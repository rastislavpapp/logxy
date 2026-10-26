package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.filter.Filter
import io.github.rastislavpapp.logxy.core.filter.FilterEngine
import io.github.rastislavpapp.logxy.core.filter.FilterResult
import io.github.rastislavpapp.logxy.core.filter.LevelCondition
import io.github.rastislavpapp.logxy.core.filter.RecordTextCondition
import io.github.rastislavpapp.logxy.core.filter.TextOp
import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.log.ParsedLog
import io.github.rastislavpapp.logxy.core.session.evaluateRowHighlights
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.openjdk.jmh.annotations.Level as JmhLevel

/**
 * A followed log: a filter and a search, or the row highlights, catch up on the records of [appended] written to a
 * copy of [file] before each run. Open so JMH can generate its subclass.
 */
@State(Scope.Benchmark)
open class FollowBenchmark {
    @Param("")
    @JvmField
    var file = ""

    @Param("")
    @JvmField
    var appended = ""

    private val search = RecordTextCondition(TextOp.EQUALS, "*timeout*", ignoreCase = true)
    private val filter = Filter.And(listOf(LevelCondition.atLeast("Level", Level.WARN), search))
    private lateinit var followed: Path
    private lateinit var bytes: ByteArray
    private lateinit var log: ParsedLog
    private lateinit var engine: FilterEngine
    private var covered = 0

    @Setup
    fun setUp() {
        val source = Path.of(file)
        // Next to the source, as the copy is as large; each JVM gets its own.
        followed = Files.createTempFile(source.toAbsolutePath().parent, "followed-", ".log")
        Files.copy(source, followed, StandardCopyOption.REPLACE_EXISTING)
        bytes = Files.readAllBytes(Path.of(appended))
        val parser = parserFor(source).compileFor(source)
        log = runBlocking { ParsedLog.build(followed, parser) }
        engine = FilterEngine(log)
        runBlocking {
            engine.prepare(filter).run()
            evaluateRowHighlights(engine, log.schema, benchmarkHighlights)
        }
    }

    @Setup(JmhLevel.Iteration)
    fun append() {
        covered = log.recordCount
        Files.write(followed, bytes, StandardOpenOption.APPEND)
        log.append(log.readAppended(Files.size(followed)))
    }

    @TearDown
    fun tearDown() {
        log.close()
        Files.deleteIfExists(followed)
    }

    @Benchmark
    fun filterAppended(): FilterResult = runBlocking { engine.prepare(filter, engine.changedFrom(covered)).run() }

    /** [benchmarkHighlights] caught up on the appended records. */
    @Benchmark
    fun highlightAppended(): Int = runBlocking {
        evaluateRowHighlights(engine, log.schema, benchmarkHighlights, engine.changedFrom(covered))
    }
}

/** The `follow` rows; they have no `tools` command. */
val followRows = listOf(
    BenchmarkRow(FollowBenchmark::class.java, "filterAppended", "follow: filter appended records"),
    BenchmarkRow(FollowBenchmark::class.java, "highlightAppended", "follow: row highlights of appended records"),
)

/** Parameters of [FollowBenchmark]. */
fun followParams(file: Path, appended: Path): Map<String, List<String>> = mapOf(
    FollowBenchmark::file.name to listOf(file.toString()),
    FollowBenchmark::appended.name to listOf(appended.toString()),
)
