package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.config.Settings
import io.github.rastislavpapp.logxy.core.open.OpenSession
import io.github.rastislavpapp.logxy.core.open.RecentSessions
import io.github.rastislavpapp.logxy.core.open.Reopen
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * The recent sessions, kept in `recent.properties` next to `ui.properties` while the user has
 * [Settings.rememberRecent] on; with it off, nothing is recorded. Used on the EDT only.
 */
object RecentStore {

    private val log = LoggerFactory.getLogger(RecentStore::class.java)
    private val path = Settings.directory().resolve("recent.properties")

    /** The stored sessions; read when first needed. */
    var sessions: RecentSessions = RecentSessions()
        get() {
            if (!loaded) {
                loaded = true
                field = try {
                    RecentSessions.load(path)
                } catch (e: Exception) {
                    log.warn("Cannot read {}", path, e)
                    RecentSessions()
                }
            }
            return field
        }
        private set
    private var loaded = false

    private val remembering get() = AppSettings.current.rememberRecent

    /** Puts [session], whose tab just closed, at the top of the recent sessions. */
    fun recordClosed(session: OpenSession) {
        if (!remembering) return
        update(sessions.withClosed(session))
    }

    /**
     * Records the sessions open at exit: as [open], with the one at [selected] selected, for the next start; and each
     * as closed, in order with the selected one last, so it ends on top of the recent sessions.
     */
    fun recordExit(open: List<OpenSession>, selected: Int) {
        if (!remembering) return
        val closingOrder = open.filterIndexed { i, _ -> i != selected } + listOfNotNull(open.getOrNull(selected))
        val recent = closingOrder.fold(sessions) { sessions, session -> sessions.withClosed(session) }
        update(recent.copy(atExit = Reopen(open, selected).takeIf { open.isNotEmpty() }))
    }

    /** Empties the recent sessions, keeping the sessions open at exit. */
    fun clear() = update(sessions.copy(recent = emptyList()))

    /** Forgets all sessions and deletes the file, as when the user stops remembering them. */
    fun forget() {
        sessions = RecentSessions()
        loaded = true
        try {
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            log.warn("Cannot delete {}", path, e)
        }
    }

    private fun update(updated: RecentSessions) {
        // As stored: without the recent sessions whose files are gone.
        sessions = updated.copy(recent = updated.recent.filterNot(RecentSessions::gone))
        try {
            RecentSessions.save(path, sessions)
        } catch (e: Exception) {
            log.warn("Cannot write {}", path, e)
        }
    }
}
