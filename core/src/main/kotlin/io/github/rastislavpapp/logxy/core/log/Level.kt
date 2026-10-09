package io.github.rastislavpapp.logxy.core.log

/** Canonical level scale; ordinals are ordered by severity, so "at least WARN" is a numeric compare. */
enum class Level {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN;

    companion object {
        /** Level names of common logging frameworks; the starting point of [LevelMapping.DEFAULT]. */
        val BUILT_IN_NAMES: Map<String, Level> = mapOf(
            "TRACE" to TRACE, "FINEST" to TRACE, "FINER" to TRACE, "VERBOSE" to TRACE,
            "DEBUG" to DEBUG, "FINE" to DEBUG, "DBG" to DEBUG,
            "INFO" to INFO, "CONFIG" to INFO, "INFORMATION" to INFO, "NOTICE" to INFO,
            "WARN" to WARN, "WARNING" to WARN,
            "ERROR" to ERROR, "ERR" to ERROR, "SEVERE" to ERROR,
            "FATAL" to FATAL, "CRITICAL" to FATAL, "CRIT" to FATAL, "ALERT" to FATAL, "EMERG" to FATAL,
        )
    }
}
