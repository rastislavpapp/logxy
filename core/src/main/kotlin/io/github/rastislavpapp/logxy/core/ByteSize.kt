package io.github.rastislavpapp.logxy.core

import java.util.Locale

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

/**
 * Formats a byte count for display using binary (1024-based) units,
 * e.g. `0 B`, `512 B`, `1.5 KB`, `2.34 GB`.
 */
fun formatByteSize(bytes: Long): String {
    require(bytes >= 0) { "bytes must not be negative: $bytes" }
    if (bytes < 1024) return "$bytes B"
    var unit = 0
    var value = bytes.toDouble()
    while (value >= 1024 && unit < UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = when {
        value >= 100 -> "%.0f %s"
        value >= 10 -> "%.1f %s"
        else -> "%.2f %s"
    }
    return pattern.format(Locale.ROOT, value, UNITS[unit])
}
