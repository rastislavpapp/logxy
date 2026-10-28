package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Schema

/** A [Parser] compiled for reading: the [schema] it produces and matchers for its lines. Thread-safe. */
abstract class CompiledParser {

    abstract val schema: Schema

    /** A new matcher, for one thread; matchers are stateful, the compiled parser is not. */
    internal abstract fun newMatcher(): LineMatcher

    /** Display text of a TIMESTAMP field's value, in the log's own layout. */
    abstract fun formatTimestamp(field: Int, nanos: Long): String

    /** Whether lines that do not start a record continue the one before (stack traces), or are records of their own. */
    open val multiLine: Boolean get() = true

    /**
     * Lines at the start of the file that are no records, such as a CSV header row. Blank lines (see
     * [io.github.rastislavpapp.logxy.core.Bytes.isBlank]) before and among them are skipped without counting, so
     * inferring a header must take the first lines that are not blank.
     */
    open val headerLines: Int get() = 0
}

/** Recognizes the first line of a record. Stateful; one per thread. */
internal interface LineMatcher {

    /** Whether `bytes[from, to)`, a line without terminator, starts a record. */
    fun match(bytes: ByteArray, from: Int, to: Int): Boolean

    /** Writes the fields of the last matched line into the record just begun in [out]. */
    fun write(out: Columns)
}
