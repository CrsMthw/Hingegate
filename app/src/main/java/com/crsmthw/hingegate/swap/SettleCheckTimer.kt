package com.crsmthw.hingegate.swap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The Real wallpaper mode's ONE scheduled check (docs/SWAP-MODE.md §10.24): a check asked for while the inner screen
 * is not settled yet, a confirmation read half a second after a suspected change (§10.25), a recapture tried again
 * (§10.26), a held set (§10.27). [onDue] asks the swapper for a reconcile, which may schedule the next one. Plain
 * Kotlin (no Android types), so it is JVM-tested; main thread only, like the swapper.
 */
class SettleCheckTimer(
    private val scope: CoroutineScope,
    /** The monotonic clock, in ms. */
    private val uptime: () -> Long,
    private val onDue: () -> Unit,
) {
    private var job: Job? = null
    private var dueMs = 0L

    /**
     * One check after [delayMs]. A check still WAITING that is due no later covers it (its reconcile schedules
     * again); a check that is due is waiting for nothing and covers nothing.
     */
    fun schedule(delayMs: Long) {
        val due = uptime() + delayMs + SLACK_MS
        val pending = job
        if (pending != null && pending.isActive && dueMs <= due) return
        pending?.cancel()
        dueMs = due
        job = scope.launch {
            delay(delayMs + SLACK_MS)
            // Let go BEFORE asking for the reconcile: on the phone it runs inline, inside this very job, and whatever
            // it schedules (the end of a new settle period, a confirmation read) would otherwise count as covered by
            // this check — which is already firing — and be lost for good (SWAP-MODE §10.24).
            if (job === coroutineContext.job) job = null
            onDue()
        }
    }

    companion object {
        /** A scheduled check runs this much after the moment it waits for, so it never lands a hair early. */
        const val SLACK_MS = 20L
    }
}
