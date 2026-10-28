package io.github.rastislavpapp.logxy.tools

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.SplittableRandom

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())

private val ENDPOINTS = listOf("GET /api/orders", "POST /api/orders", "GET /api/users", "PUT /api/stock", "GET /search")

/**
 * A web service's log4j-style lines (`%d [%t] %-5p %c %X - %m%n`) where each request has an id of its own in the
 * MDC, `{requestId=..., tenant=...}`: 40 threads each handle one request at a time, in 3 to 15 records, so the
 * requests' records interleave. Ids are unique, as a trace id column's are.
 */
internal class RequestGenerator(private val random: SplittableRandom) : RecordGenerator {
    private val threads = List(40) { "http-nio-8080-exec-${it + 1}" }
    private val requests = arrayOfNulls<String>(threads.size)
    private val endpoints = arrayOfNulls<String>(threads.size)
    private val recordsLeft = IntArray(threads.size)
    private var requestCount = 0L

    override var timeMillis = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli()

    override fun next(forcedLevel: String?): String {
        timeMillis += random.nextInt(0, 3)
        val thread = random.nextInt(threads.size)
        val first = recordsLeft[thread] == 0
        if (first) start(thread)
        recordsLeft[thread]--
        val endpoint = endpoints[thread]
        val (level, message) = when {
            forcedLevel != null -> forcedLevel to "$endpoint failed: upstream timeout"
            first -> "INFO" to "Received $endpoint"
            recordsLeft[thread] == 0 -> "INFO" to "Completed $endpoint in ${random.nextInt(1, 2000)} ms"
            random.nextInt(50) == 0 -> "WARN" to "Slow query on $endpoint: ${random.nextInt(500, 5000)} ms"
            random.nextInt(200) == 0 -> "ERROR" to "Call to inventory failed for $endpoint"
            else -> "DEBUG" to "Step ${random.nextInt(1, 20)} of $endpoint took ${random.nextInt(0, 50)} ms"
        }
        val context = "{requestId=${requests[thread]}, tenant=t${random.nextInt(8)}}"
        return "${TIMESTAMP.format(Instant.ofEpochMilli(timeMillis))} [${threads[thread]}] ${level.padEnd(5)} " +
            "com.example.web.RequestHandler $context - $message\n"
    }

    /** Starts the next request on [thread], with a new id and 3 to 15 records. */
    private fun start(thread: Int) {
        requests[thread] = "req-%08x".format(++requestCount)
        endpoints[thread] = ENDPOINTS[random.nextInt(ENDPOINTS.size)]
        recordsLeft[thread] = random.nextInt(3, 16)
    }
}
