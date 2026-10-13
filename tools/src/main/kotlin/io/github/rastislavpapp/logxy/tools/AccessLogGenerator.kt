package io.github.rastislavpapp.logxy.tools

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.SplittableRandom

private val ACCESS_SECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH).withZone(ZoneId.systemDefault())

private val METHODS = listOf("GET" to 70, "POST" to 18, "PUT" to 6, "DELETE" to 3, "HEAD" to 2, "OPTIONS" to 1)
private val PATHS = listOf("/", "/api/v1/orders", "/api/v1/orders/{id}", "/api/v1/users/{id}", "/static/app.js", "/static/app.css", "/login", "/search?q={id}")
private val BROWSERS = listOf("Firefox", "Chrome", "Safari", "Edg")
private val SYSTEMS = listOf("Windows NT 10.0; Win64; x64", "X11; Linux x86_64", "Macintosh; Intel Mac OS X 14_5", "iPhone; CPU iPhone OS 17_5 like Mac OS X")
private val REFERERS = listOf("https://www.example.com/", "https://www.google.com/", "https://shop.example.com/cart")

/**
 * A web server's access log, mostly in the combined layout, a fifth in the common one. Many distinct clients
 * and user agents; now and then a scanner's malformed request. Forced errors are 5xx responses, forced
 * warnings 4xx ones.
 */
internal class AccessLogGenerator(private val random: SplittableRandom) : RecordGenerator {

    override var timeMillis = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli()
    private var cachedSecond = -1L
    private var cachedSecondText = ""

    override fun next(forcedLevel: String?): String {
        timeMillis += random.nextInt(0, 6)
        val malformed = random.nextInt(500) == 0
        val request = if (!malformed) {
            request()
        } else if (random.nextBoolean()) {
            "-"
        } else {
            "\\x16\\x03\\x01\\x02\\x00\\x01"
        }
        val status = when {
            forcedLevel == "ERROR" -> listOf(500, 502, 503, 504)[random.nextInt(4)]
            forcedLevel == "WARN" -> listOf(400, 401, 403, 404)[random.nextInt(4)]
            malformed -> 400
            else -> when (random.nextInt(100)) {
                in 0..79 -> 200
                in 80..87 -> 304
                in 88..91 -> 302
                in 92..97 -> 404
                else -> 500
            }
        }
        val sb = StringBuilder(250)
        sb.append(client()).append(" - ").append(if (random.nextInt(10) == 0) "user${random.nextInt(500)}" else "-")
        sb.append(" [").append(timestamp()).append("] \"").append(request).append("\" ").append(status).append(' ')
        sb.append(if (status == 304) "-" else random.nextInt(20, 200_000).toString())
        if (random.nextInt(5) != 0) {
            sb.append(" \"").append(if (random.nextBoolean()) "-" else REFERERS[random.nextInt(REFERERS.size)])
            sb.append("\" \"").append(userAgent()).append('"')
        }
        return sb.append('\n').toString()
    }

    private fun client() = "10.${random.nextInt(4)}.${random.nextInt(256)}.${random.nextInt(256)}"

    private fun request(): String {
        var roll = random.nextInt(100)
        val method = METHODS.first { (_, weight) -> (roll < weight).also { roll -= weight } }.first
        val path = PATHS[random.nextInt(PATHS.size)].replace("{id}", random.nextInt(1, 1_000_000).toString())
        return "$method $path HTTP/${if (random.nextInt(3) == 0) "2.0" else "1.1"}"
    }

    private fun userAgent(): String {
        if (random.nextInt(8) == 0) return "curl/8.${random.nextInt(12)}.${random.nextInt(3)}"
        val browser = BROWSERS[random.nextInt(BROWSERS.size)]
        val version = "${random.nextInt(100, 131)}.0.${random.nextInt(7000)}.${random.nextInt(200)}"
        return "Mozilla/5.0 (${SYSTEMS[random.nextInt(SYSTEMS.size)]}) AppleWebKit/537.36 (KHTML, like Gecko) $browser/$version"
    }

    private fun timestamp(): String {
        val second = timeMillis / 1000
        if (second != cachedSecond) {
            cachedSecond = second
            cachedSecondText = ACCESS_SECONDS.format(Instant.ofEpochSecond(second))
        }
        return cachedSecondText
    }
}
