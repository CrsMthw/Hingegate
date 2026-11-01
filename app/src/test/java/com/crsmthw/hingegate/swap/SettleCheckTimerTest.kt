package com.crsmthw.hingegate.swap

import com.crsmthw.hingegate.domain.SwapSettle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The swapper's scheduled check. On the phone a check that fires asks for a reconcile, and that reconcile runs
 * INLINE, inside the firing check's own job (the delay resumes undispatched on the main looper, the kick resumes
 * the reconcile collector in the same unconfined loop, and nothing on the way suspends once DataStore's cache is
 * warm). So whatever that reconcile schedules is scheduled while the firing check still looks active: [onDue]
 * below schedules synchronously, exactly like that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettleCheckTimerTest {

    private val slack = SettleCheckTimer.SLACK_MS

    /** A timer whose checks are recorded (at the test clock's time) and then handed to [reconcile]. */
    private fun TestScope.timer(fired: MutableList<Long>, reconcile: (SettleCheckTimer) -> Unit): SettleCheckTimer {
        lateinit var timer: SettleCheckTimer
        timer = SettleCheckTimer(this, { testScheduler.currentTime }) {
            fired += testScheduler.currentTime
            reconcile(timer)
        }
        return timer
    }

    @Test
    fun `a check asked for by a firing check is kept - fold and unfold within one settle period`() = runTest {
        // The swapper's reconcile while not settled: one check at the end of the settle period (deferCheck).
        var innerInUseSince: Long? = 0L
        val fired = mutableListOf<Long>()
        fun reconcile(timer: SettleCheckTimer) {
            val since = innerInUseSince ?: return // folded: nothing is scheduled, the next unfold asks again
            val remaining = SwapSettle.SETTLE_MS - (testScheduler.currentTime - since)
            if (remaining > 0) timer.schedule(remaining)
        }
        val timer = timer(fired, ::reconcile)

        reconcile(timer) // unfold at 0: a check at 1.52 s
        advanceTimeBy(1_000)
        innerInUseSince = null // fold at 1.0 s
        reconcile(timer)
        advanceTimeBy(300)
        innerInUseSince = testScheduler.currentTime // unfold at 1.3 s: its 2.82 s check is covered by the 1.52 s one
        reconcile(timer)
        advanceUntilIdle()

        // The 1.52 s check is not settled yet (only 0.22 s since the unfold) and asks for the end of the new settle
        // period from inside its own job: that check must run, or nothing ever settles this unfold.
        val settledAt = 1_300L + SwapSettle.SETTLE_MS + slack
        assertEquals(listOf(SwapSettle.SETTLE_MS + slack, settledAt), fired)
    }

    @Test
    fun `a confirmation asked for by a firing check gets its check`() = runTest {
        // The deferred check is the first settled sighting of a changed id: SUSPECT asks for a read 500 ms later
        // (the same holds for a recapture retried or kept stale, and a held set).
        val fired = mutableListOf<Long>()
        val timer = timer(fired) { if (fired.size == 1) it.schedule(SwapSettle.CONFIRM_MS) }
        timer.schedule(SwapSettle.SETTLE_MS)
        advanceUntilIdle()
        val first = SwapSettle.SETTLE_MS + slack
        assertEquals(listOf(first, first + SwapSettle.CONFIRM_MS + slack), fired)
    }

    @Test
    fun `a pending check due no later covers a new one`() = runTest {
        val fired = mutableListOf<Long>()
        val timer = timer(fired) {}
        timer.schedule(SwapSettle.SETTLE_MS)
        advanceTimeBy(300)
        timer.schedule(SwapSettle.SETTLE_MS) // due later: the pending one covers it (its reconcile schedules again)
        advanceUntilIdle()
        assertEquals(listOf(SwapSettle.SETTLE_MS + slack), fired)
    }

    @Test
    fun `a sooner check replaces a later one`() = runTest {
        val fired = mutableListOf<Long>()
        val timer = timer(fired) {}
        timer.schedule(SwapSettle.SETTLE_MS)
        timer.schedule(SwapSettle.CONFIRM_MS)
        advanceUntilIdle()
        assertEquals(listOf(SwapSettle.CONFIRM_MS + slack), fired)
    }

    @Test
    fun `checks keep running after one has fired`() = runTest {
        val fired = mutableListOf<Long>()
        val timer = timer(fired) {}
        timer.schedule(SwapSettle.CONFIRM_MS)
        advanceUntilIdle()
        timer.schedule(SwapSettle.CONFIRM_MS)
        advanceUntilIdle()
        val first = SwapSettle.CONFIRM_MS + slack
        assertEquals(listOf(first, 2 * first), fired)
    }
}
