package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.log.LogData
import io.github.rastislavpapp.logxy.core.log.Schema
import io.github.rastislavpapp.logxy.core.trace.TraceIndex
import io.github.rastislavpapp.logxy.core.trace.Traces

/**
 * The traces of all records of [log] by the trace ids in the field named [column], read in one pass as a session
 * first reads them; for measuring that. Null if the trace ids cannot come from that field. Call on the thread that
 * owns [log].
 */
suspend fun traces(log: LogData, column: String): Traces? {
    val field = traceField(log.schema, column)
    if (field < 0) return null
    return TraceIndex(column, emptyList()).update(SingleLoad(log).traceSource(field))
}

/** The index of the field named [column] in [schema]; -1 if there is none or trace ids cannot come from it. */
internal fun traceField(schema: Schema, column: String): Int {
    val field = schema.fields.indexOfFirst { it.name == column }
    return if (field >= 0 && Traces.canHoldTraceIds(schema.fields[field])) field else -1
}
