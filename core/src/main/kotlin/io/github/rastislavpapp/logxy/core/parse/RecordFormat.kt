package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.Columns
import io.github.rastislavpapp.logxy.core.log.Schema

/** A compiled log layout: the [schema] it produces and parsers for it. Thread-safe. */
abstract class RecordFormat {

    abstract val schema: Schema

    internal abstract fun newParser(): RecordParser

    /** Display text of a TIMESTAMP field's value, in the log's own layout. */
    abstract fun formatTimestamp(field: Int, nanos: Long): String
}

/** Recognizes the first line of a record. Stateful; one per thread. */
internal interface RecordParser {

    /** Whether `bytes[from, to)`, a line without terminator, starts a record. */
    fun match(bytes: ByteArray, from: Int, to: Int): Boolean

    /** Writes the fields of the last matched line into the record just begun in [out]. */
    fun write(out: Columns)
}
