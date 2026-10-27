package io.github.rastislavpapp.logxy.core.session

import java.nio.file.Path

/** How many bookmarks a session holds at most. */
const val MAX_BOOKMARKS = 100

/** What [LogSession.toggleBookmarks] did. */
enum class BookmarkToggle {
    /** Bookmarked all of the records. */
    ADDED,

    /** Removed the bookmarks of the records, as every one had one. */
    REMOVED,

    /** Bookmarked the records up to [MAX_BOOKMARKS]; the others have none. */
    LIMITED,
}

/**
 * A session's bookmarks, kept by where each record starts, so they outlive a load, and found as record numbers in
 * the current load by [recordAt]; [startOf] gives where a record of the current load starts.
 */
internal class Bookmarks(
    private val startOf: (record: Int) -> RecordStart<Path>?,
    private val recordAt: (start: RecordStart<Path>) -> Int,
) {
    // Where each bookmarked record starts, in the order of [records].
    private var starts = emptyList<RecordStart<Path>>()

    // The record numbers of [starts] in the load and record count [foundIn] names, sorted.
    private var records = IntArray(0)
    private var foundIn: Any? = null

    /**
     * The bookmarked records, sorted, in the records [load] stands for. [load] is any value that changes whenever
     * record numbers may change, such as the loaded log paired with its record count: appended records of a merge
     * can come between earlier ones. When [load] changes, the records are found again from where they start, and a
     * bookmark whose record no longer starts there, e.g. in a file truncated since, is dropped. While [load] is null,
     * as nothing is loaded, this is empty and the bookmarks wait for the next load.
     */
    fun records(load: Any?): IntArray {
        if (load == null) return IntArray(0)
        if (load != foundIn) find(load)
        return records
    }

    /** Where each bookmarked record of [load] starts; while nothing is loaded, those waiting for a load. */
    fun starts(load: Any?): List<RecordStart<Path>> {
        if (load != null) records(load)
        return starts
    }

    /** Puts [starts] in place of the bookmarks, e.g. those of a recent session; they are found at the next load. */
    fun restore(starts: List<RecordStart<Path>>) {
        this.starts = starts.take(MAX_BOOKMARKS)
        records = IntArray(0)
        foundIn = null
    }

    /**
     * Removes the bookmarks of [records] of [load] if every one has one, else bookmarks them all, up to
     * [MAX_BOOKMARKS]. Reads only as many of [records] as it needs, so a selection of millions of rows costs no more
     * than a few.
     */
    fun toggle(load: Any, records: Sequence<Int>): BookmarkToggle {
        val bookmarked = records(load)
        // More records than bookmarks cannot all have one.
        val leading = records.take(bookmarked.size + 1).toList()
        if (leading.size <= bookmarked.size && leading.all { bookmarked.binarySearch(it) >= 0 }) {
            val removed = leading.toHashSet()
            val left = bookmarked.indices.filter { bookmarked[it] !in removed }
            set(load, left.map { bookmarked[it] to starts[it] })
            return BookmarkToggle.REMOVED
        }
        val kept = bookmarked.indices.mapTo(ArrayList()) { bookmarked[it] to starts[it] }
        val had = bookmarked.toHashSet()
        var outcome = BookmarkToggle.ADDED
        for (record in records) {
            if (record in had) continue
            if (kept.size >= MAX_BOOKMARKS) {
                outcome = BookmarkToggle.LIMITED
                break
            }
            val start = startOf(record) ?: continue
            kept += record to start
            had += record
        }
        set(load, kept)
        return outcome
    }

    fun clear() = restore(emptyList())

    private fun find(load: Any) {
        val found = starts.mapNotNull { start ->
            val record = recordAt(start)
            // [recordAt] gives the record starting before the offset too.
            if (record >= 0 && startOf(record)?.offset == start.offset) record to start else null
        }
        set(load, found)
    }

    private fun set(load: Any, bookmarks: List<Pair<Int, RecordStart<Path>>>) {
        val sorted = bookmarks.distinctBy { it.first }.sortedBy { it.first }
        records = IntArray(sorted.size) { sorted[it].first }
        starts = sorted.map { it.second }
        foundIn = load
    }
}
