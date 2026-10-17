package io.github.rastislavpapp.logxy.core

object PlatformDetector {

    fun isWindows(): Boolean = osName().startsWith("windows", ignoreCase = true)
    fun isMac(): Boolean = osName().startsWith("mac", ignoreCase = true)

    private fun osName(): String = System.getProperty("os.name") ?: "unknown"
}
