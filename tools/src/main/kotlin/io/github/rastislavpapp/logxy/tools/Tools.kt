package io.github.rastislavpapp.logxy.tools

import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  generate <file> <size> [--seed N]     write a log4j-style test log, size like 500m or 2g
  benchmark <file> [--iterations N]     compare line-indexing strategies on a file
  parse <file> [--iterations N]         measure parsing with the detected format
  filter <file>                         measure typical filters on the parsed file
  simulate <file> [--rate N] [--rotate-at SIZE]
                                        append lines like a running application (N per second, default 20),
                                        optionally rotating file -> file.1 -> ... at SIZE; Ctrl+C stops"""

fun main(args: Array<String>) {
    val positional = args.filterIndexed { i, arg -> !arg.startsWith("--") && (i == 0 || !args[i - 1].startsWith("--")) }
    fun option(name: String): String? = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    when (positional.firstOrNull()) {
        "generate" if positional.size == 3 ->
            generateLog(Path.of(positional[1]), parseSize(positional[2]), option("seed")?.toLong() ?: 1L)
        "benchmark" if positional.size == 2 ->
            benchmarkIndexing(Path.of(positional[1]), option("iterations")?.toInt() ?: 5)
        "parse" if positional.size == 2 ->
            benchmarkParsing(Path.of(positional[1]), option("iterations")?.toInt() ?: 3)
        "filter" if positional.size == 2 -> benchmarkFiltering(Path.of(positional[1]))
        "simulate" if positional.size == 2 ->
            simulateLogging(Path.of(positional[1]), option("rate")?.toInt() ?: 20, option("rotate-at")?.let(::parseSize))
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
