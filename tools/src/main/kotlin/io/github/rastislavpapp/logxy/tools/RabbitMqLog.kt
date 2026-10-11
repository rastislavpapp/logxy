package io.github.rastislavpapp.logxy.tools

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.SplittableRandom

private val RABBIT_SECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private val RABBIT_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("XXX").withZone(ZoneId.systemDefault())

private val RABBIT_LEVELS = listOf("debug" to 20, "info" to 64, "notice" to 5, "warning" to 7, "error" to 4)

private val USERS = listOf("orders-svc", "billing-svc", "notifier", "inventory-svc", "monitoring")
private val VHOSTS = listOf("/", "orders", "billing")
private val QUEUES = listOf(
    "orders.created", "orders.paid", "orders.cancelled", "billing.invoices", "notifications.email",
    "inventory.reserve", "inventory.release", "dead-letter",
)
private val POLICIES = listOf("ha-all", "quorum-default", "ttl-24h", "dlx")

/**
 * A RabbitMQ broker's log with `log.file.formatter = json`: one object per line with `time`, `level`
 * and `msg`, plus Erlang metadata (`domain`, `file`, `line`, `mfa`, `pid`). Clients open, use and close
 * connections; some messages span several lines.
 */
internal class RabbitMqGenerator(
    private val random: SplittableRandom,
    /** Levels (`ERROR`, `WARN`) never picked at random. */
    private val excluded: Set<String> = emptySet(),
) : RecordGenerator {

    private class Connection(val pid: String, val peer: String, val user: String, val vhost: String) {
        val name get() = "$pid ($peer -> 10.0.0.2:5672)"
        val described get() = "$pid ($peer -> 10.0.0.2:5672, vhost: '$vhost', user: '$user')"
    }

    private val open = ArrayList<Connection>()

    override var timeMillis = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli()
    private var timeMicros = 0L
    private var cachedSecond = -1L
    private var cachedSecondText = ""
    private var cachedOffset = ""

    override fun next(forcedLevel: String?): String {
        timeMillis += random.nextInt(0, 40)
        // Microseconds within the millisecond are random, but never go backwards.
        timeMicros = maxOf(timeMillis * 1000 + random.nextInt(1000), timeMicros + 1)
        timeMillis = timeMicros / 1000
        val level = when (forcedLevel) {
            "ERROR" -> "error"
            "WARN" -> "warning"
            else -> pickLevel()
        }
        val event = when (level) {
            "debug" -> debug()
            "notice" -> notice()
            "warning" -> warning()
            // Forced errors stay errors, so their count is exact.
            "error" -> if (forcedLevel == null && random.nextInt(40) == 0) critical() else error()
            else -> info()
        }
        val sb = StringBuilder(300)
        sb.append("{\"time\":\"").append(timestamp()).append("\",\"level\":\"").append(event.level).append("\",\"msg\":")
        appendJsonString(sb, event.message)
        sb.append(",\"domain\":\"").append(event.domain).append("\",\"file\":\"").append(event.module).append(".erl\"")
        sb.append(",\"gl\":\"<0.").append(200 + random.nextInt(40)).append(".0>\",\"line\":").append(event.line)
        sb.append(",\"mfa\":[\"").append(event.module).append("\",\"").append(event.function).append("\",").append(event.arity).append(']')
        sb.append(",\"pid\":\"").append(event.pid ?: pid()).append("\"}\n")
        return sb.toString()
    }

    private class Event(
        val level: String,
        val message: String,
        val domain: String,
        val module: String,
        val function: String,
        val arity: Int,
        val line: Int,
        val pid: String? = null,
    )

    private fun pickLevel(): String {
        var roll = random.nextInt(100)
        for ((level, weight) in RABBIT_LEVELS) {
            val excludedLevel = (level == "error" && "ERROR" in excluded) || (level == "warning" && "WARN" in excluded)
            if (roll < weight) return if (excludedLevel) "info" else level
            roll -= weight
        }
        return "info"
    }

    private fun info(): Event {
        // Keep a few dozen clients connected: open more while there are few, close some while there are many.
        val opening = open.size < 5 || (open.size < 60 && random.nextInt(3) == 0)
        return when {
            opening -> {
                val connection = Connection(pid(), "10.0.${random.nextInt(1, 8)}.${random.nextInt(2, 250)}:${random.nextInt(32768, 61000)}",
                    USERS[random.nextInt(USERS.size)], VHOSTS[random.nextInt(VHOSTS.size)])
                open += connection
                Event("info", "accepting AMQP connection ${connection.name}", "rabbitmq.connection", "rabbit_reader", "start_connection", 3, 398, connection.pid)
            }
            random.nextInt(4) == 0 -> {
                val connection = open.removeAt(random.nextInt(open.size))
                Event("info", "closing AMQP connection ${connection.described}", "rabbitmq.connection", "rabbit_reader", "handle_method0", 3, 1278, connection.pid)
            }
            random.nextInt(3) == 0 -> {
                val connection = open[random.nextInt(open.size)]
                Event(
                    "info", "connection ${connection.name}: user '${connection.user}' authenticated and granted access to vhost '${connection.vhost}'",
                    "rabbitmq.connection", "rabbit_reader", "handle_method0", 3, 1414, connection.pid,
                )
            }
            random.nextInt(50) == 0 -> Event(
                "info", "memory resource limit alarm cleared on node 'rabbit@node-1'", "rabbitmq", "rabbit_alarm", "handle_clear_alarm", 2, 252,
            )
            else -> Event(
                "info", "Successfully synced ${random.nextInt(1, 5000)} messages to disk for queue '${queue()}' in vhost '${vhost()}'",
                "rabbitmq.queue", "rabbit_variable_queue", "handle_pre_hibernate", 1, 620,
            )
        }
    }

    private fun debug(): Event = when (random.nextInt(3)) {
        0 -> Event("debug", "Applying policy '${POLICIES[random.nextInt(POLICIES.size)]}' to queue '${queue()}' in vhost '${vhost()}'",
            "rabbitmq.queue", "rabbit_policy", "update_matched_objects", 3, 412)
        1 -> Event("debug", "Queue '${queue()}' in vhost '${vhost()}' has ${random.nextInt(10_000)} messages ready, ${random.nextInt(200)} unacknowledged",
            "rabbitmq.queue", "rabbit_amqqueue_process", "handle_info", 2, 1710)
        else -> Event("debug", "Writing ${random.nextInt(1, 500)} messages to segment ${random.nextInt(1, 300)}.idx of queue '${queue()}'",
            "rabbitmq.queue", "rabbit_queue_index", "flush_journal", 1, 879)
    }

    private fun notice(): Event = Event(
        "notice", "queue '${queue()}' in vhost '${vhost()}': leader saw pre_vote_rpc from {'%2F_${queue()}',rabbit@node-${random.nextInt(2, 4)}} " +
            "for term ${random.nextInt(2, 20)} abdicates term: ${random.nextInt(2, 20)}!",
        "rabbitmq.queue", "ra_server", "handle_leader", 2, 723,
    )

    private fun warning(): Event {
        if (random.nextInt(40) == 0) {
            return Event(
                "warning", "memory resource limit alarm set on node 'rabbit@node-1'.\n\n" +
                    "**********************************************************\n" +
                    "*** Publishers will be blocked until this alarm clears ***\n" +
                    "**********************************************************",
                "rabbitmq", "rabbit_alarm", "handle_set_resource_alarm", 3, 227,
            )
        }
        val connection = if (open.isEmpty()) null else open.removeAt(random.nextInt(open.size))
        return if (connection != null) {
            Event("warning", "closing AMQP connection ${connection.described}:\nclient unexpectedly closed TCP connection",
                "rabbitmq.connection", "rabbit_reader", "log_connection_exception", 3, 532, connection.pid)
        } else {
            Event("warning", "Detected an unclean shutdown of the message store, rebuilding indices for vhost '${vhost()}'",
                "rabbitmq", "rabbit_msg_store", "init", 1, 734)
        }
    }

    private fun error(): Event = when (random.nextInt(3)) {
        0 -> {
            val connection = open.getOrNull(random.nextInt(open.size + 1))
            val queue = queue()
            Event(
                "error",
                "Channel error on connection ${connection?.described ?: "${pid()} (10.0.3.17:41520 -> 10.0.0.2:5672, vhost: '/', user: 'orders-svc')"}, channel 1:\n" +
                    "operation queue.declare caused a channel exception precondition_failed: inequivalent arg 'x-queue-type' " +
                    "for queue '$queue' in vhost '${connection?.vhost ?: "/"}': received the value 'quorum' of type 'longstr' but current is none",
                "rabbitmq.channel", "rabbit_channel", "handle_exception", 2, 795, connection?.pid,
            )
        }
        1 -> Event(
            "error", "Error on AMQP connection ${pid()} (10.0.${random.nextInt(1, 8)}.${random.nextInt(2, 250)}:${random.nextInt(32768, 61000)} " +
                "-> 10.0.0.2:5672, state: starting):\nPLAIN login refused: user 'guest' - invalid credentials",
            "rabbitmq.connection", "rabbit_reader", "log_connection_exception", 3, 532,
        )
        else -> {
            val server = pid()
            Event(
                "error",
                "** Generic server $server terminating\n" +
                    "** Last message in was {'\$gen_cast',{deliver,{delivery,false,true,<0.${random.nextInt(1000, 30000)}.0>," +
                    "{basic_message,{resource,<<\"${vhost()}\">>,exchange,<<\"amq.direct\">>},[<<\"${queue()}\">>]}}}}\n" +
                    "** When Server state == {q,{amqqueue,{resource,<<\"${vhost()}\">>,queue,<<\"${queue()}\">>},true,false,none,[]}}\n" +
                    "** Reason for termination ==\n" +
                    "** {timeout,{gen_server,call,[<0.${random.nextInt(1000, 30000)}.0>,{basic_get,${random.nextInt(1, 9)},false},60000]}}",
                "rabbitmq", "gen_server", "error_info", 8, 1407, server,
            )
        }
    }

    private fun critical() = Event(
        "critical", "Partial partition detected:\n * We saw DOWN from rabbit@node-2\n * We can still see rabbit@node-3 which can see rabbit@node-2\n" +
            " * pause_minority mode enabled\nWe will therefore pause until the *entire* cluster recovers",
        "rabbitmq", "rabbit_node_monitor", "handle_info", 2, 714,
    )

    private fun queue() = QUEUES[random.nextInt(QUEUES.size)]
    private fun vhost() = VHOSTS[random.nextInt(VHOSTS.size)]
    private fun pid() = "<0.${random.nextInt(300, 60_000)}.0>"

    /** RFC 3339 with a space and microseconds, as RabbitMQ writes it by default. */
    private fun timestamp(): String {
        val second = timeMicros / 1_000_000
        if (second != cachedSecond) {
            cachedSecond = second
            val instant = Instant.ofEpochSecond(second)
            cachedSecondText = RABBIT_SECONDS.format(instant)
            cachedOffset = RABBIT_OFFSET.format(instant)
        }
        return cachedSecondText + "." + (timeMicros % 1_000_000).toString().padStart(6, '0') + cachedOffset
    }
}
