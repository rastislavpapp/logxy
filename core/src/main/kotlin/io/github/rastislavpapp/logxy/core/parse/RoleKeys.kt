package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.Role

/** Which keys of a record (JSON keys, logfmt keys) fill which role; of each list, the first key a record has wins. */
data class RoleKeys(
    // Besides logstash and ECS: Docker's json-file driver (log, time), Serilog's compact JSON (@t, @l, @m, @mt, @x)
    // and GELF (short_message, full_message).
    val timestamp: List<String> = listOf("@timestamp", "timestamp", "time", "ts", "@t"),
    val level: List<String> = listOf("level", "severity", "log.level", "levelname", "@l"),
    val message: List<String> = listOf("message", "msg", "log", "@m", "@mt", "short_message"),
    val logger: List<String> = listOf("logger", "logger_name", "log.logger"),
    val thread: List<String> = listOf("thread", "thread_name", "process.thread.name"),
    val exception: List<String> = listOf("stack_trace", "exception", "error.stack_trace", "@x", "full_message"),
) {
    internal val byRole: List<Pair<Role, List<String>>>
        get() = listOf(
            Role.TIMESTAMP to timestamp, Role.LEVEL to level, Role.THREAD to thread,
            Role.LOGGER to logger, Role.MESSAGE to message, Role.EXCEPTION to exception,
        )

    /** The keys as settings properties under [prefix], e.g. `keys.level=level,severity`. */
    fun properties(prefix: String): Map<String, String> =
        ROLES.associate { (role, keys) -> prefix + role.name.lowercase() to keys(this).joinToString(",") }

    companion object {
        /** Each role and its keys, in the order of the constructor. */
        val ROLES: List<Pair<Role, (RoleKeys) -> List<String>>> = listOf(
            Role.TIMESTAMP to RoleKeys::timestamp, Role.LEVEL to RoleKeys::level, Role.MESSAGE to RoleKeys::message,
            Role.LOGGER to RoleKeys::logger, Role.THREAD to RoleKeys::thread, Role.EXCEPTION to RoleKeys::exception,
        )

        /** Keys per role, in the order of [ROLES]. */
        fun of(keys: List<List<String>>) = RoleKeys(keys[0], keys[1], keys[2], keys[3], keys[4], keys[5])

        /** Reads keys written by [properties]; roles missing from them keep those of [defaults]. */
        internal fun read(property: (String) -> String?, prefix: String, defaults: RoleKeys): RoleKeys = of(
            ROLES.map { (role, keys) ->
                property(prefix + role.name.lowercase())?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: keys(defaults)
            },
        )
    }
}
