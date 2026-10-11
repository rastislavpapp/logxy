package io.github.rastislavpapp.logxy.tools

import io.github.rastislavpapp.logxy.core.formatByteSize
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.SplittableRandom

private val LEVELS = listOf("TRACE" to 5, "DEBUG" to 25, "INFO" to 55, "WARN" to 10, "ERROR" to 5)

private val TENANTS = listOf("acme", "globex", "initech", "umbrella")

private val TIMESTAMP_SECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private val ISO_SECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.systemDefault())

private val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("XXX").withZone(ZoneId.systemDefault())

private val LEVEL_VALUES = mapOf("TRACE" to 5000, "DEBUG" to 10000, "INFO" to 20000, "WARN" to 30000, "ERROR" to 40000)

/** Writes one record at a time, e.g. a line or a record with its stack trace. */
internal interface RecordGenerator {
    /** Time of the last record; advanced a little by each [next]. */
    var timeMillis: Long

    /** The next record, ending with a newline; of [forcedLevel] (`ERROR` or `WARN`) if given. */
    fun next(forcedLevel: String? = null): String
}

/**
 * Writes log4j-style lines (`%d [%t] %-5p %c - %m%n`, or `%d [%t] %-5p %c %X - %m%n` with [mdc])
 * with occasional stack traces until [size] bytes; with [json], JSON lines as logstash-logback-encoder
 * writes them; with [rabbitmq], a RabbitMQ broker's JSON log. With [errors] or [warnings], exactly that
 * many records of the level are written, at random places.
 */
fun generateLog(
    file: Path,
    size: Long,
    seed: Long,
    mdc: Boolean = false,
    errors: Int? = null,
    warnings: Int? = null,
    json: Boolean = false,
    rabbitmq: Boolean = false,
) {
    val random = SplittableRandom(seed)
    val placed = listOfNotNull(errors?.let { "ERROR" to it }, warnings?.let { "WARN" to it })
    // Byte offsets at which a placed record is written next, sorted.
    val places = placed.flatMap { (level, count) -> List(count) { random.nextLong(size) to level } }.sortedBy { it.first }
    var nextPlace = 0
    val excluded = placed.map { it.first }.toSet()
    val generator = if (rabbitmq) RabbitMqGenerator(random, excluded) else LogGenerator(random, mdc, excluded, json)
    val started = System.nanoTime()
    var written = 0L
    var lines = 0L
    Files.createDirectories(file.parent)
    BufferedOutputStream(Files.newOutputStream(file), 1 shl 20).use { out ->
        while (written < size) {
            val level = places.getOrNull(nextPlace)?.takeIf { it.first <= written }?.second
            if (level != null) nextPlace++
            val record = generator.next(level).toByteArray()
            out.write(record)
            written += record.size
            lines++
        }
    }
    val seconds = (System.nanoTime() - started) / 1e9
    println("Wrote ${formatByteSize(written)} ($lines records) to $file in %.1f s".format(seconds))
}

internal class LogGenerator(
    private val random: SplittableRandom,
    private val mdc: Boolean = false,
    /** Levels never picked at random. */
    private val excluded: Set<String> = emptySet(),
    /** JSON lines with the MDC as top-level keys (always, as an encoder writes it) instead of text. */
    private val json: Boolean = false,
) : RecordGenerator {

    private val threads = List(40) { i ->
        when (i % 4) {
            0 -> "http-nio-8080-exec-${i / 4 + 1}"
            1 -> "pool-2-thread-${i / 4 + 1}"
            2 -> "scheduler-${i / 4 + 1}"
            else -> "kafka-consumer-${i / 4 + 1}"
        }
    } + "main"

    private val loggers = run {
        val modules = listOf("order", "billing", "auth", "inventory", "shipping", "search", "notification", "report")
        val layers = listOf("api", "service", "repository", "client", "config", "job")
        val nouns = listOf("Order", "Invoice", "User", "Session", "Stock", "Parcel", "Query", "Email", "Cache", "Token")
        val roles = listOf("Controller", "Service", "Repository", "Client", "Handler", "Mapper", "Validator", "Scheduler")
        List(300) {
            "com.example.${modules[random.nextInt(modules.size)]}.${layers[random.nextInt(layers.size)]}." +
                "${nouns[random.nextInt(nouns.size)]}${roles[random.nextInt(roles.size)]}"
        }.distinct()
    }

    // A thread keeps its MDC for a run of records, like a request or a consumed message would.
    private val contexts = arrayOfNulls<List<Pair<String, String>>>(threads.size)
    private val contextRecordsLeft = IntArray(threads.size)
    private var breadcrumbs = 0L

    override var timeMillis = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli()
    private var cachedSecond = -1L
    private var cachedSecondText = ""
    private var cachedIsoSecond = -1L
    private var cachedIsoSecondText = ""
    private var cachedIsoOffset = ""

    override fun next(forcedLevel: String?): String {
        timeMillis += random.nextInt(0, 6)
        val level = forcedLevel ?: pickLevel()
        val thread = random.nextInt(threads.size)
        val logger = loggers[random.nextInt(loggers.size)]
        val context = if (mdc || json) context(thread) else null
        val message = message(level)
        val withTrace = (level == "ERROR" && random.nextInt(10) < 3) || (level == "WARN" && random.nextInt(20) == 0)
        val trace = if (withTrace) StringBuilder().also(::appendStackTrace) else null
        if (json) return jsonRecord(level, threads[thread], logger, context!!, message, trace)

        val sb = StringBuilder(200)
        sb.append(timestamp()).append(" [").append(threads[thread]).append("] ")
        sb.append(level.padEnd(5)).append(' ').append(logger)
        if (context != null) sb.append(' ').append(context.joinToString(", ", "{", "}") { (key, value) -> "$key=$value" })
        sb.append(" - ")
        sb.append(message).append('\n')
        if (trace != null) sb.append(trace)
        return sb.toString()
    }

    private fun jsonRecord(
        level: String,
        thread: String,
        logger: String,
        context: List<Pair<String, String>>,
        message: String,
        trace: CharSequence?,
    ): String {
        val sb = StringBuilder(300)
        sb.append("{\"@timestamp\":\"").append(isoTimestamp()).append("\",\"@version\":\"1\",\"message\":")
        appendJsonString(sb, message)
        sb.append(",\"logger_name\":\"").append(logger).append("\",\"thread_name\":\"").append(thread)
        sb.append("\",\"level\":\"").append(level).append("\",\"level_value\":").append(LEVEL_VALUES.getValue(level))
        if (trace != null) {
            sb.append(",\"stack_trace\":")
            appendJsonString(sb, trace)
        }
        for ((key, value) in context) {
            sb.append(",\"").append(key).append("\":")
            appendJsonString(sb, value)
        }
        return sb.append("}\n").toString()
    }

    private fun pickLevel(): String {
        var roll = random.nextInt(100)
        for ((level, weight) in LEVELS) {
            if (roll < weight) return if (level in excluded) "INFO" else level
            roll -= weight
        }
        return "INFO"
    }

    private fun context(thread: Int): List<Pair<String, String>> {
        if (contextRecordsLeft[thread] == 0) {
            contextRecordsLeft[thread] = random.nextInt(1, 40)
            contexts[thread] = newContext(threads[thread])
        }
        contextRecordsLeft[thread]--
        return contexts[thread]!!
    }

    /** MDC entries sorted by key, as log4j2's `%X` writes them. The set of keys varies by thread. */
    private fun newContext(thread: String): List<Pair<String, String>> {
        val entries = ArrayList<Pair<String, String>>(4)
        val inRequest = thread.startsWith("http") || thread.startsWith("kafka")
        if (inRequest || random.nextInt(3) == 0) {
            entries += "breadcrumbId" to "ID-app-node-${1 + random.nextInt(3)}-1759305600000-0-${++breadcrumbs}"
        }
        if (thread.startsWith("kafka")) entries += "partition" to "${random.nextInt(12)}"
        if (inRequest && random.nextInt(4) != 0) entries += "tenant" to TENANTS[random.nextInt(TENANTS.size)]
        if (thread.startsWith("http") && random.nextBoolean()) entries += "userId" to "${id()}"
        return entries
    }

    private fun timestamp(): String {
        val second = timeMillis / 1000
        if (second != cachedSecond) {
            cachedSecond = second
            cachedSecondText = TIMESTAMP_SECONDS.format(Instant.ofEpochSecond(second))
        }
        return cachedSecondText + "." + (timeMillis % 1000).toString().padStart(3, '0')
    }

    private fun isoTimestamp(): String {
        val second = timeMillis / 1000
        if (second != cachedIsoSecond) {
            cachedIsoSecond = second
            val instant = Instant.ofEpochSecond(second)
            cachedIsoSecondText = ISO_SECONDS.format(instant)
            cachedIsoOffset = ISO_OFFSET.format(instant)
        }
        return cachedIsoSecondText + "." + (timeMillis % 1000).toString().padStart(3, '0') + cachedIsoOffset
    }

    private fun id() = random.nextInt(1, 1_000_000)
    private fun ms() = random.nextInt(1, 5000)

    private fun message(level: String): String = when (level) {
        "ERROR" -> when (random.nextInt(3)) {
            0 -> "Failed to process message ${uuid()}: timeout after ${ms()} ms"
            1 -> "Payment declined for order ${id()}: insufficient funds"
            else -> "Unexpected error while handling request GET /api/v1/orders/${id()}"
        }
        "WARN" -> when (random.nextInt(3)) {
            0 -> "Retrying operation sync-${id()} (attempt ${random.nextInt(1, 6)}/5)"
            1 -> "Slow query detected: ${ms()} ms for findByCustomerId(${id()})"
            else -> "Connection pool nearly exhausted: active=${random.nextInt(40, 50)}, idle=0, waiting=${random.nextInt(1, 20)}"
        }
        else -> when (random.nextInt(8)) {
            0 -> "User ${id()} logged in from 10.${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(256)}"
            1 -> "Processed ${random.nextInt(1, 10_000)} records in ${ms()} ms"
            2 -> "Cache miss for key order:${id()}"
            3 -> "Request GET /api/v1/orders/${id()} completed with status 200 in ${ms()} ms"
            4 -> "Connection pool stats: active=${random.nextInt(50)}, idle=${random.nextInt(50)}, waiting=0"
            5 -> "Zákazník ${id()} změnil doručovací adresu"
            6 -> "Payload: " + payload()
            else -> "Scheduled job report-${random.nextInt(20)} finished"
        }
    }

    private fun uuid() = "%08x-%04x-%04x-%04x-%012x".format(
        random.nextInt(), random.nextInt(0x10000), random.nextInt(0x10000), random.nextInt(0x10000),
        random.nextLong(0x1000000000000L),
    )

    private fun payload(): String {
        val items = random.nextInt(1, 40)
        return (0..<items).joinToString(",", "{\"orderId\":${id()},\"items\":[", "]}") {
            "{\"sku\":\"SKU-${id()}\",\"qty\":${random.nextInt(1, 10)},\"price\":${random.nextInt(100, 100_000) / 100.0}}"
        }
    }

    private fun appendStackTrace(sb: StringBuilder) {
        val exceptions = listOf(
            "java.lang.IllegalStateException: Order is not in a payable state",
            "java.net.SocketTimeoutException: Read timed out",
            "java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available",
            "java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"name\" is null",
        )
        sb.append(exceptions[random.nextInt(exceptions.size)]).append('\n')
        appendFrames(sb, random.nextInt(8, 40))
        if (random.nextBoolean()) {
            sb.append("Caused by: ").append(exceptions[random.nextInt(exceptions.size)]).append('\n')
            appendFrames(sb, random.nextInt(3, 10))
            sb.append("\t... ").append(random.nextInt(5, 30)).append(" more\n")
        }
    }

    private fun appendFrames(sb: StringBuilder, count: Int) {
        repeat(count) {
            val logger = loggers[random.nextInt(loggers.size)]
            val simpleName = logger.substringAfterLast('.')
            sb.append("\tat ").append(logger).append(".handle(").append(simpleName).append(".java:")
                .append(random.nextInt(20, 900)).append(")\n")
        }
    }
}

/** Appends [text] as a JSON string, quoted and escaped. */
internal fun appendJsonString(sb: StringBuilder, text: CharSequence) {
    sb.append('"')
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    sb.append('"')
}
