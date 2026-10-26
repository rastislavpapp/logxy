package io.github.rastislavpapp.logxy.core.session

import io.github.rastislavpapp.logxy.core.filter.PreparedFilter
import io.github.rastislavpapp.logxy.core.filter.RecordFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The evaluation of one query over the records of a load, kept up to date as records are appended: a run over all
 * of them, then catch-ups over the records from [RecordFilter.changedFrom] on, whose result replaces what the
 * result held from there. The filter and the search each keep one. Use it on the session's thread.
 *
 * The job that runs is the caller's: it calls [cover] once its result is in place and [finished] when it is done,
 * after which a [catchUp] may start the next one. A job replaced by [start] is cancelled.
 */
internal class LoadEvaluation(private val scope: CoroutineScope) {

    private var job: Job? = null

    /** How many records the result covers, from the first one on. */
    var coveredRecords = 0
        private set

    /** How many bytes of the files the result covers: the last record may grow without new ones. */
    var coveredBytes = 0L
        private set

    /** Whether a run or a catch-up is running. */
    val isRunning: Boolean get() = job?.isActive == true

    /** Runs [block] as the evaluation, cancelling the one running. */
    fun start(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch(block = block)
    }

    /**
     * Catches up on what was appended since the result unless an evaluation is running or the result covers
     * [indexedSize] bytes: [prepare] gets the first record whose result may have changed and prepares the evaluation
     * from there, such as a [PreparedFilter], and [block] runs it. Never cancels a running evaluation: whichever
     * finishes calls this again, so nothing appended is missed.
     */
    fun <T> catchUp(
        engine: RecordFilter,
        indexedSize: Long,
        prepare: (from: Int) -> T,
        block: suspend CoroutineScope.(from: Int, evaluation: T) -> Unit,
    ) {
        if (isRunning || coveredBytes >= indexedSize) return
        // Records covered then may have grown since (the last one of each file), so they are evaluated again.
        val from = engine.changedFrom(coveredRecords)
        val evaluation = prepare(from)
        job = scope.launch { block(from, evaluation) }
    }

    /** The result now covers the first [records] records and [bytes] bytes of the files. */
    fun cover(records: Int, bytes: Long) {
        coveredRecords = records
        coveredBytes = bytes
    }

    /** The running job is done, though still running the code that called this. */
    fun finished() {
        job = null
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Cancels the running job and forgets what the result covered, e.g. as the load ends. */
    fun clear() {
        cancel()
        cover(0, 0L)
    }
}
