package io.github.rastislavpapp.logxy.core.log

/** Canonical level scale; ordinals are ordered by severity, so "at least WARN" is a numeric compare. */
enum class Level {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN;

    companion object {
        private val BY_NAME = mapOf(
            "TRACE" to TRACE, "FINEST" to TRACE, "FINER" to TRACE, "VERBOSE" to TRACE,
            "DEBUG" to DEBUG, "FINE" to DEBUG, "DBG" to DEBUG,
            "INFO" to INFO, "CONFIG" to INFO, "INFORMATION" to INFO, "NOTICE" to INFO,
            "WARN" to WARN, "WARNING" to WARN,
            "ERROR" to ERROR, "ERR" to ERROR, "SEVERE" to ERROR,
            "FATAL" to FATAL, "CRITICAL" to FATAL, "CRIT" to FATAL, "ALERT" to FATAL, "EMERG" to FATAL,
        )

        /** Maps a level name from any common framework, ignoring case and surrounding spaces. */
        fun of(text: String): Level = BY_NAME[text.trim().uppercase()] ?: UNKNOWN
    }
}
