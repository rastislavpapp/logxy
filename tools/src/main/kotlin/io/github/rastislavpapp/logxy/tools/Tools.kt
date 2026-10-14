package io.github.rastislavpapp.logxy.tools

import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  generate <file> <size> [--seed N] [--mdc] [--json] [--rabbitmq] [--logfmt] [--access] [--errors N] [--warnings N]
                                        write a log4j-style test log, size like 500m or 2g;
                                        --mdc adds a log4j2 %X map such as {breadcrumbId=..., tenant=...};
                                        --json writes JSON lines like logstash-logback-encoder, MDC keys included;
                                        --rabbitmq writes a RabbitMQ broker's JSON log (log.file.formatter = json);
                                        --logfmt writes logfmt lines, MDC keys included;
                                        --access writes a web server's access log, common and combined lines;
                                        --errors and --warnings write exactly N such records, at random places
  scenario <file> <size> [--seed N] [--mdc] [--json] [--rabbitmq]
                                        write a day of a service's log with things worth finding: daily traffic,
                                        a night batch, slowly growing warnings, an incident with a restart gap at
                                        14:20 and an error spike at 19:40
  benchmark <file> [--iterations N]     compare line-indexing strategies on a file
  parse <file>... [--iterations N] [--forks N] [--pattern LAYOUT]
                                        measure parsing one file with the detected parser, or the given pattern
                                        layout (one file only); several files one by one and at once
  filter <file> [--iterations N] [--forks N]
                                        measure typical filters, counting patterns and times on the parsed file
  merge <file> <file>... [--iterations N] [--forks N] [--read-channels N]
                                        measure merging files and filtering the merged view
                                        parse, filter and merge time N runs (after uncounted ones) in each of
                                        --forks JVMs (default 1) and show the spread between runs, or JVMs
  simulate <file> [--rate N] [--rotate-at SIZE] [--mdc] [--json] [--rabbitmq]
                                        append lines like a running application (N per second, default 20),
                                        optionally rotating file -> file.1 -> ... at SIZE; Ctrl+C stops
  icons                                 render the logo SVG into the packaging PNG and ICO;
                                        run from the project root after changing the logo"""

fun main(args: Array<String>) {
    val flags = setOf("--mdc", "--json", "--rabbitmq", "--logfmt", "--access")
    val positional = args.filterIndexed { i, arg ->
        !arg.startsWith("--") && (i == 0 || !args[i - 1].startsWith("--") || args[i - 1] in flags)
    }
    fun flag(name: String) = "--$name" in args
    fun option(name: String): String? = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    fun runs(defaultIterations: Int): Runs {
        val iterations = option("iterations")?.toInt() ?: defaultIterations
        val forks = option("forks")?.toInt() ?: 1
        return Runs(iterations, forks)
    }

    when (positional.firstOrNull()) {
        "generate" if positional.size == 3 ->
            generateLog(
                Path.of(positional[1]), parseSize(positional[2]), option("seed")?.toLong() ?: 1L, flag("mdc"),
                option("errors")?.toInt(), option("warnings")?.toInt(), flag("json"), flag("rabbitmq"), flag("logfmt"),
                flag("access"),
            )
        "scenario" if positional.size == 3 ->
            generateScenarioLog(
                Path.of(positional[1]), parseSize(positional[2]), option("seed")?.toLong() ?: 1L,
                flag("mdc"), flag("json"), flag("rabbitmq"),
            )
        "benchmark" if positional.size == 2 ->
            benchmarkIndexing(Path.of(positional[1]), option("iterations")?.toInt() ?: 5)
        "parse" if positional.size >= 2 ->
            benchmarkParsing(positional.drop(1).map { Path.of(it) }, runs(3), option("pattern"))
        "filter" if positional.size == 2 ->
            benchmarkFiltering(Path.of(positional[1]), runs(5))
        "merge" if positional.size >= 3 ->
            benchmarkMerging(positional.drop(1).map { Path.of(it) }, runs(3), option("read-channels")?.toInt())
        "simulate" if positional.size == 2 ->
            simulateLogging(
                Path.of(positional[1]),
                option("rate")?.toInt() ?: 20,
                option("rotate-at")?.let(::parseSize),
                flag("mdc"),
                flag("json"),
                flag("rabbitmq"),
            )
        "icons" if positional.size == 1 ->
            generateIcons(Path.of("app/src/main/resources/logxy.svg"), Path.of("app/src/packaging"))
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
}

private fun parseSize(text: String): Long {
    val units = mapOf('k' to (1L shl 10), 'm' to (1L shl 20), 'g' to (1L shl 30))
    val unit = units[text.last().lowercaseChar()]
    return if (unit == null) text.toLong() else text.dropLast(1).toLong() * unit
}
