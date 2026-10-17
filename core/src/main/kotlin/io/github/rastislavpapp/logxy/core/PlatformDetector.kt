package io.github.rastislavpapp.logxy.core

object PlatformDetector {

    fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

}