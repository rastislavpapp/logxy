package io.github.rastislavpapp.logxy.core

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The SLF4J provider of core's tests (see `META-INF/services`): keeps every line logged, at every level, in
 * [events]. Tests run side by side in one JVM, so they pick their lines by the files they name.
 */
class LogCapture : SLF4JServiceProvider {

    data class Event(val level: Level, val message: String, val thrown: Throwable?)

    private class Capturing(name: String) : LegacyAbstractLogger() {
        init {
            this.name = name
        }

        override fun isTraceEnabled() = true
        override fun isDebugEnabled() = true
        override fun isInfoEnabled() = true
        override fun isWarnEnabled() = true
        override fun isErrorEnabled() = true
        override fun getFullyQualifiedCallerName(): String? = null

        override fun handleNormalizedLoggingCall(
            level: Level,
            marker: Marker?,
            messagePattern: String?,
            arguments: Array<out Any?>?,
            throwable: Throwable?,
        ) {
            events += Event(level, MessageFormatter.basicArrayFormat(messagePattern, arguments), throwable)
        }
    }

    private val loggers = ILoggerFactory { Capturing(it) }
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggers
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion() = "2.0.99"
    override fun initialize() {}

    companion object {
        val events = ConcurrentLinkedQueue<Event>()

        /** The lines logged so far at [level] that mention [text], e.g. a file's name. */
        fun logged(level: Level, text: String): List<Event> =
            events.filter { it.level == level && text in it.message }
    }
}
