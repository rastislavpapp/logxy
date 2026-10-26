package io.github.rastislavpapp.logxy.core.session

/**
 * Where a record starts: in [file] (a `Path` of an open session, or the text a stored session keeps), at the
 * byte [offset] of its first line. Unlike a record number, it stays valid when the files load again.
 */
data class RecordStart<F>(val file: F, val offset: Long) {
    fun <G> withFile(file: G) = RecordStart(file, offset)
}

/**
 * Where a session's shown log was, to go back there when the session opens again: the [lead] record of the
 * selection and the record in the [top] visible row, each null if there was none.
 */
data class SessionPosition<F>(val lead: RecordStart<F>?, val top: RecordStart<F>?) {

    /** This position with each file turned into another form by [file]; a start whose file it maps to null is lost. */
    fun <G : Any> mapFiles(file: (F) -> G?): SessionPosition<G> {
        fun map(start: RecordStart<F>?): RecordStart<G>? {
            if (start == null) return null
            val mapped = file(start.file) ?: return null
            return start.withFile(mapped)
        }
        return SessionPosition(map(lead), map(top))
    }
}
