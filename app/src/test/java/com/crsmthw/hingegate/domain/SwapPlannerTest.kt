package com.crsmthw.hingegate.domain

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwapPlannerTest {

    private val spec = CrackSpecGenerator.generate(Random(1))
    private val otherSpec = CrackSpecGenerator.generate(Random(2))
    private val unfoldCrack = ActiveCrack(spec, 0L, CrackCause.UNFOLD)
    private val manualCrack = ActiveCrack(spec, 0L, CrackCause.MANUAL)
    private val pendingHit = PendingRoll(true, HitReason.ROLLED, 10, 35, 4, 0, otherSpec)
    private val pendingMiss = PendingRoll(false, HitReason.MISSED, 80, 35, 4, 0, null)

    private fun input(
        swapMode: Boolean = true,
        enabled: Boolean = true,
        posture: Posture = Posture.UNFOLDED,
        pending: PendingRoll? = null,
        active: ActiveCrack? = null,
        staged: Staged? = null,
        isSamsung: Boolean = true,
        hasOriginal: Boolean = true,
    ) = SwapInput(swapMode, enabled, posture, pending, active, staged, isSamsung, hasOriginal)

    private fun apply(target: Staged, spec: CrackSpec?, timing: SwapTiming) = SwapAction.Apply(target, spec, timing)

    /** The app's own JSON settings (AppContainer). */
    private val lenient = Json { ignoreUnknownKeys = true }

    // ── Nothing to work with ─────────────────────────────────────────────────

    @Test
    fun `nothing happens without a captured original`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(active = unfoldCrack, hasOriginal = false)))
        assertEquals(SwapAction.None, SwapPlanner.plan(input(swapMode = false, staged = Staged.Cracked(1), hasOriginal = false)))
    }

    @Test
    fun `nothing happens while the posture is unknown`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(posture = Posture.UNKNOWN, active = unfoldCrack)))
    }

    // ── Unfolded ─────────────────────────────────────────────────────────────

    @Test
    fun `an active crack is set when it is not staged yet`() {
        val action = SwapPlanner.plan(input(active = manualCrack, staged = Staged.Clean))
        assertEquals(apply(Staged.Cracked(spec.seed), spec, SwapTiming.ON_TIME), action)
    }

    @Test
    fun `the staged crack is never set again`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(active = unfoldCrack, staged = Staged.Cracked(spec.seed))))
    }

    @Test
    fun `an unfold's crack set while unfolded is late on samsung, on time elsewhere`() {
        assertEquals(apply(Staged.Cracked(spec.seed), spec, SwapTiming.LATE), SwapPlanner.plan(input(active = unfoldCrack)))
        assertEquals(
            apply(Staged.Cracked(spec.seed), spec, SwapTiming.ON_TIME),
            SwapPlanner.plan(input(active = unfoldCrack, isSamsung = false)),
        )
    }

    @Test
    fun `a crack shown by hand is not late`() {
        assertEquals(apply(Staged.Cracked(spec.seed), spec, SwapTiming.ON_TIME), SwapPlanner.plan(input(active = manualCrack)))
    }

    @Test
    fun `a cleared crack puts the clean original back`() {
        assertEquals(apply(Staged.Clean, null, SwapTiming.ON_TIME), SwapPlanner.plan(input(staged = Staged.Cracked(spec.seed))))
    }

    @Test
    fun `clean over the untouched original needs no set`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(staged = null)))
        assertEquals(SwapAction.None, SwapPlanner.plan(input(staged = Staged.Clean)))
    }

    @Test
    fun `an unfold not decided yet is waited for`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(pending = pendingHit, staged = Staged.Cracked(otherSpec.seed))))
    }

    // ── Folded ───────────────────────────────────────────────────────────────

    @Test
    fun `samsung stages the pending crack while folded`() {
        val action = SwapPlanner.plan(input(posture = Posture.FOLDED, pending = pendingHit, staged = Staged.Clean))
        assertEquals(apply(Staged.Cracked(otherSpec.seed), otherSpec, SwapTiming.AHEAD), action)
    }

    @Test
    fun `samsung stages clean for a pending miss after a crack`() {
        val action = SwapPlanner.plan(input(posture = Posture.FOLDED, pending = pendingMiss, staged = Staged.Cracked(spec.seed)))
        assertEquals(apply(Staged.Clean, null, SwapTiming.AHEAD), action)
    }

    @Test
    fun `samsung leaves the untouched original for a pending miss`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(posture = Posture.FOLDED, pending = pendingMiss, staged = null)))
    }

    @Test
    fun `samsung waits while folded without a pending roll`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(posture = Posture.FOLDED, staged = Staged.Cracked(spec.seed))))
    }

    @Test
    fun `samsung does not stage what is already staged`() {
        val staged = Staged.Cracked(otherSpec.seed)
        assertEquals(SwapAction.None, SwapPlanner.plan(input(posture = Posture.FOLDED, pending = pendingHit, staged = staged)))
    }

    @Test
    fun `other phones put the clean original back at the fold and ignore the pending roll`() {
        val folded = input(posture = Posture.FOLDED, pending = pendingHit, staged = Staged.Cracked(spec.seed), isSamsung = false)
        assertEquals(apply(Staged.Clean, null, SwapTiming.ON_TIME), SwapPlanner.plan(folded))
        assertEquals(SwapAction.None, SwapPlanner.plan(folded.copy(staged = Staged.Clean)))
    }

    @Test
    fun `other phones set the crack at the unfold`() {
        val unfolded = input(active = unfoldCrack, staged = Staged.Clean, isSamsung = false)
        assertEquals(apply(Staged.Cracked(spec.seed), spec, SwapTiming.ON_TIME), SwapPlanner.plan(unfolded))
    }

    // ── Leaving the mode ─────────────────────────────────────────────────────

    @Test
    fun `leaving the mode or switching off puts a staged crack back once`() {
        val cracked = Staged.Cracked(spec.seed)
        listOf(
            input(swapMode = false, staged = cracked),
            input(enabled = false, staged = cracked),
            input(swapMode = false, posture = Posture.FOLDED, staged = cracked, pending = pendingHit),
            input(enabled = false, posture = Posture.UNKNOWN, staged = cracked),
        ).forEach { assertEquals(apply(Staged.Clean, null, SwapTiming.RESTORE), SwapPlanner.plan(it)) }
    }

    @Test
    fun `leaving the mode never touches a clean or untouched wallpaper`() {
        assertEquals(SwapAction.None, SwapPlanner.plan(input(swapMode = false, staged = Staged.Clean, active = unfoldCrack)))
        assertEquals(SwapAction.None, SwapPlanner.plan(input(enabled = false, staged = null)))
    }

    // ── Ids ──────────────────────────────────────────────────────────────────

    private val captured = SwapState(capture = SwapCapture(wallpaperId = 10, capturedAtEpochMs = 0L), ourIds = listOf(20, 21))

    /** A settled read at [now] in the unfold numbered [epoch], with no unknown id seen before unless [sighting]. */
    private fun decide(id: Int, state: SwapState, swapOn: Boolean = true, sighting: Sighting? = null, epoch: Long = 7, now: Long = 10_000) =
        SwapIds.decide(id, state, swapOn, sighting, epoch, now)

    @Test
    fun `ids tell ours, the original and a new wallpaper apart`() {
        assertEquals(IdCheck.OURS, SwapIds.check(21, captured))
        assertEquals(IdCheck.ORIGINAL, SwapIds.check(10, captured))
        assertEquals(IdCheck.CHANGED, SwapIds.check(30, captured))
    }

    @Test
    fun `an unknown id after a set made while folded is adopted, not recaptured`() {
        assertEquals(IdCheck.ADOPT, SwapIds.check(30, captured.copy(unconfirmedSet = true)))
        assertEquals(IdCheck.OURS, SwapIds.check(21, captured.copy(unconfirmedSet = true)))
        assertEquals(IdCheck.ORIGINAL, SwapIds.check(10, captured.copy(unconfirmedSet = true)))
    }

    @Test
    fun `the original is recognised even if its id was wrongly remembered as ours`() {
        // An earlier build could add the original's id to ourIds after a set that never reached the inner screen.
        assertEquals(IdCheck.ORIGINAL, SwapIds.check(10, captured.copy(ourIds = listOf(10, 20))))
    }

    @Test
    fun `the id is checked only with a capture and something to protect`() {
        assertFalse(SwapIds.shouldCheck(SwapState(), swapOn = true))
        assertTrue(SwapIds.shouldCheck(captured, swapOn = true))
        // Out of the mode, only while something of ours may still be showing.
        assertFalse(SwapIds.shouldCheck(captured, swapOn = false))
        assertTrue(SwapIds.shouldCheck(captured.copy(staged = Staged.Clean), swapOn = false))
    }

    @Test
    fun `our copy confirmed clears the unconfirmed mark, otherwise nothing changes`() {
        assertEquals(IdDecision(IdAction.KEEP), decide(21, captured))
        val unconfirmed = captured.copy(staged = Staged.Cracked(1), unconfirmedSet = true)
        assertEquals(IdDecision(IdAction.CONFIRM), decide(21, unconfirmed))
        assertEquals(unconfirmed.copy(unconfirmedSet = false), SwapIds.settle(IdAction.CONFIRM, unconfirmed, 21))
    }

    @Test
    fun `the original showing after an unconfirmed set is a miss, logged and reset`() {
        val staged = captured.copy(staged = Staged.Cracked(1), unconfirmedSet = true)
        val decision = decide(10, staged)
        assertEquals(IdDecision(IdAction.RESET, missed = true), decision)
        assertEquals(captured, SwapIds.settle(IdAction.RESET, staged, 10))
    }

    @Test
    fun `our copy reported after a miss was reset takes the staged state back`() {
        // The read-back lagged: the original's id showed first, then our copy's.
        val reset = captured.copy(staged = null, lastSet = Staged.Cracked(1))
        assertEquals(IdDecision(IdAction.CONFIRM), decide(21, reset))
        assertEquals(Staged.Cracked(1), SwapIds.settle(IdAction.CONFIRM, reset, 21).staged)
        // A staged state is never overwritten by it.
        val staged = captured.copy(staged = Staged.Clean, lastSet = Staged.Cracked(1), unconfirmedSet = true)
        assertEquals(Staged.Clean, SwapIds.settle(IdAction.CONFIRM, staged, 21).staged)
    }

    @Test
    fun `the original showing with a confirmed stage resets without a miss`() {
        val staged = captured.copy(staged = Staged.Clean)
        assertEquals(IdDecision(IdAction.RESET, missed = false), decide(10, staged))
        assertEquals(IdDecision(IdAction.KEEP), decide(10, captured))
    }

    @Test
    fun `an unknown id after an unconfirmed set is adopted as ours`() {
        val unconfirmed = captured.copy(staged = Staged.Cracked(1), unconfirmedSet = true)
        assertEquals(IdDecision(IdAction.ADOPT), decide(30, unconfirmed))
        val adopted = SwapIds.settle(IdAction.ADOPT, unconfirmed, 30)
        assertEquals(listOf(20, 21, 30), adopted.ourIds)
        assertFalse(adopted.unconfirmedSet)
        assertEquals(Staged.Cracked(1), adopted.staged)
    }

    @Test
    fun `a set cut short by the process dying is adopted on restart, never recaptured`() {
        // Clean was staged and confirmed; the fold's set of crack 2 reached the system, then the process died (an
        // install, a kill) before the set's own save. Only the mark saved BEFORE the set is left.
        val confirmed = captured.copy(staged = Staged.Clean, lastSet = Staged.Clean)
        val marked = SwapIds.beforeSet(confirmed, Staged.Cracked(2))
        assertEquals(confirmed.copy(unconfirmedSet = true, lastSet = Staged.Cracked(2)), marked)
        // Without the mark the copy's unknown id reads as the user's new wallpaper: suspected, then recaptured — the
        // cracked copy would become the "original".
        assertEquals(IdDecision(IdAction.SUSPECT), decide(30, confirmed))
        // With it the id is adopted as ours, and what shows is what was being set.
        assertEquals(IdDecision(IdAction.ADOPT), decide(30, marked))
        val adopted = SwapIds.settle(IdAction.ADOPT, marked, 30)
        assertEquals(Staged.Cracked(2), adopted.staged)
        assertEquals(listOf(20, 21, 30), adopted.ourIds)
        assertFalse(adopted.unconfirmedSet)
        // The set never reached the system: the old copy's id keeps what was staged.
        assertEquals(IdDecision(IdAction.CONFIRM), decide(21, marked))
        assertEquals(Staged.Clean, SwapIds.settle(IdAction.CONFIRM, marked, 21).staged)
        // Nothing of ours was staged and the original shows: reset, with no miss to log.
        val untouched = SwapIds.beforeSet(captured, Staged.Cracked(2))
        assertEquals(IdDecision(IdAction.RESET, missed = false), decide(10, untouched))
        // Nothing set over it is held back by the mark: the unknown id is the set's own.
        assertFalse(SwapIds.blocksSet(30, marked))
    }

    @Test
    fun `a new id is only suspected at first, holding everything but keeping what is staged`() {
        val staged = captured.copy(staged = Staged.Cracked(1))
        assertEquals(IdDecision(IdAction.SUSPECT), decide(30, staged))
        val suspected = SwapIds.settle(IdAction.SUSPECT, staged, 30)
        assertEquals(30, suspected.suspectId)
        assertEquals(Staged.Cracked(1), suspected.staged)
        assertEquals(captured.capture, suspected.capture)
        // Nothing is staged from the old image (or put back) while the change is suspected.
        assertFalse(suspected.copy(frame = SwapFrame(1, 1, wmHinge, 1f)).hasOriginal)
    }

    @Test
    fun `a second settled read of the same id in the same unfold confirms the change`() {
        val suspected = captured.copy(staged = Staged.Cracked(1), suspectId = 30)
        val seen = Sighting(id = 30, atMs = 10_000, epoch = 7)
        val later = 10_000 + SwapSettle.CONFIRM_MS
        assertEquals(IdDecision(IdAction.RECAPTURE), decide(30, suspected, sighting = seen, now = later))
        // Outside the mode, the confirmed change only means nothing of ours shows any more.
        assertEquals(IdDecision(IdAction.FORGET), decide(30, suspected, swapOn = false, sighting = seen, now = later))
        assertEquals(captured, SwapIds.settle(IdAction.FORGET, suspected, 30))
        assertEquals(suspected, SwapIds.settle(IdAction.RECAPTURE, suspected, 30))
    }

    @Test
    fun `a change is not confirmed too soon, in another unfold, for another id or without the hold`() {
        val suspected = captured.copy(suspectId = 30)
        val seen = Sighting(id = 30, atMs = 10_000, epoch = 7)
        val later = 10_000 + SwapSettle.CONFIRM_MS
        assertEquals(IdAction.SUSPECT, decide(30, suspected, sighting = seen, now = later - 1).action)
        assertEquals(IdAction.SUSPECT, decide(30, suspected, sighting = seen, epoch = 9, now = later).action)
        assertEquals(IdAction.SUSPECT, decide(31, suspected, sighting = seen, now = later).action)
        // The first sighting was lost (a process restart): seen again from scratch.
        assertEquals(IdAction.SUSPECT, decide(30, suspected, sighting = null, now = later).action)
        // The hold was cleared meanwhile: not confirmed by an old sighting.
        assertEquals(IdAction.SUSPECT, decide(30, captured, sighting = seen, now = later).action)
    }

    @Test
    fun `the first sighting is kept until the id is resolved`() {
        val first = Sighting(30, 1_000, 7)
        assertEquals(Sighting(30, 1_000, 7), SwapIds.nextSighting(null, IdAction.SUSPECT, 30, 7, 1_000))
        assertEquals(first, SwapIds.nextSighting(first, IdAction.SUSPECT, 30, 7, 1_300))
        assertEquals(Sighting(31, 1_300, 7), SwapIds.nextSighting(first, IdAction.SUSPECT, 31, 7, 1_300))
        assertEquals(Sighting(30, 1_300, 8), SwapIds.nextSighting(first, IdAction.SUSPECT, 30, 8, 1_300))
        IdAction.entries.filter { it != IdAction.SUSPECT }.forEach {
            assertNull(SwapIds.nextSighting(first, it, 30, 7, 1_300))
        }
        assertEquals(SwapSettle.CONFIRM_MS - 200, SwapIds.confirmDelayMs(first, 1_200))
        assertEquals(0L, SwapIds.confirmDelayMs(first, 5_000))
    }

    @Test
    fun `a suspicion is cleared by the original or our copy showing`() {
        val suspected = captured.copy(staged = Staged.Cracked(1), suspectId = 30)
        assertEquals(IdDecision(IdAction.RESET), decide(10, suspected))
        assertNull(SwapIds.settle(IdAction.RESET, suspected, 10).suspectId)
        assertEquals(IdDecision(IdAction.CONFIRM), decide(21, suspected))
        val confirmed = SwapIds.settle(IdAction.CONFIRM, suspected, 21)
        assertNull(confirmed.suspectId)
        assertEquals(Staged.Cracked(1), confirmed.staged)
    }

    @Test
    fun `a stale capture is cleared by the original or our copy showing, and a new id is still confirmed first`() {
        val stale = captured.copy(needsRecapture = true, recaptureFailure = SwapFailure.LIVE_WALLPAPER)
        assertFalse(stale.hasOriginal)
        assertEquals(IdDecision(IdAction.RESET), decide(10, stale))
        assertEquals(captured, SwapIds.settle(IdAction.RESET, stale, 10))
        assertEquals(IdDecision(IdAction.CONFIRM), decide(21, stale))
        assertEquals(captured, SwapIds.settle(IdAction.CONFIRM, stale, 21))
        assertEquals(IdDecision(IdAction.SUSPECT), decide(30, stale))
        val seen = Sighting(30, 10_000, 7)
        assertEquals(IdDecision(IdAction.RECAPTURE), decide(30, stale.copy(suspectId = 30), sighting = seen, now = 11_000))
        // Out of the mode: the mark stays until the new wallpaper can be read.
        assertTrue(SwapIds.settle(IdAction.FORGET, stale, 30).needsRecapture)
    }

    // ── A failed recapture ───────────────────────────────────────────────────

    @Test
    fun `a recapture failing for a passing reason keeps the capture, marked stale`() {
        val suspected = captured.copy(staged = Staged.Cracked(1), unconfirmedSet = true, suspectId = 30)
        listOf(SwapFailure.NOT_UNFOLDED, SwapFailure.NO_PANEL_SIZE, SwapFailure.CHANGED_DURING_READ, SwapFailure.ALREADY_OURS).forEach {
            val (outcome, next) = SwapIds.afterRecaptureFailure(suspected, it)
            assertEquals(RecaptureOutcome.KEEP_STALE, outcome)
            assertEquals(captured.capture, next.capture)
            assertTrue(next.needsRecapture)
            assertNull(next.staged)
            assertFalse(next.unconfirmedSet)
            assertNull(next.suspectId)
            assertNull(next.recaptureFailure)
        }
    }

    @Test
    fun `a final-looking failure is believed only the second time`() {
        val frame = SwapFrame(2448, 1848, wmHinge, 2.625f)
        val suspected = captured.copy(frame = frame, staged = Staged.Cracked(1), suspectId = 30)
        listOf(SwapFailure.LIVE_WALLPAPER, SwapFailure.NO_PERMISSION, SwapFailure.READ_FAILED).forEach { failure ->
            val (first, kept) = SwapIds.afterRecaptureFailure(suspected, failure)
            assertEquals(RecaptureOutcome.RETRY, first)
            // Never "Not captured yet" on one failed read: the capture and its file stay.
            assertEquals(captured.capture, kept.capture)
            assertEquals(failure, kept.recaptureFailure)
            assertTrue(kept.needsRecapture)
            val (second, gone) = SwapIds.afterRecaptureFailure(kept, SwapFailure.LIVE_WALLPAPER)
            assertEquals(RecaptureOutcome.LET_GO, second)
            assertEquals(SwapState(frame = frame, ourIds = captured.ourIds), gone)
        }
    }

    @Test
    fun `a passing failure in between restarts the count`() {
        val (_, once) = SwapIds.afterRecaptureFailure(captured, SwapFailure.LIVE_WALLPAPER)
        val (_, passing) = SwapIds.afterRecaptureFailure(once, SwapFailure.NOT_UNFOLDED)
        assertEquals(RecaptureOutcome.RETRY, SwapIds.afterRecaptureFailure(passing, SwapFailure.LIVE_WALLPAPER).first)
    }

    @Test
    fun `every failure is either passing or final`() {
        val final = SwapFailure.entries.filter { SwapIds.isFinal(it) }.toSet()
        assertEquals(setOf(SwapFailure.LIVE_WALLPAPER, SwapFailure.NO_PERMISSION, SwapFailure.READ_FAILED, SwapFailure.SET_FAILED), final)
    }

    // ── The guard before a set ───────────────────────────────────────────────

    @Test
    fun `nothing is set over a wallpaper no settled check has seen`() {
        assertTrue(SwapIds.blocksSet(30, captured))
        assertFalse(SwapIds.blocksSet(10, captured))
        assertFalse(SwapIds.blocksSet(21, captured))
        // Possibly our unconfirmed set's own id: the settled check adopts or confirms it, the set is not held.
        assertFalse(SwapIds.blocksSet(30, captured.copy(unconfirmedSet = true)))
        // No id, or nothing captured: nothing to protect from.
        assertFalse(SwapIds.blocksSet(-1, captured))
        assertFalse(SwapIds.blocksSet(null, captured))
        assertFalse(SwapIds.blocksSet(30, SwapState()))
    }

    @Test
    fun `the last look before a set drops a target the controller has moved past`() {
        // Staged while folded on Samsung: the pending crack.
        val folded = input(posture = Posture.FOLDED, pending = pendingHit, staged = Staged.Clean)
        val target = Staged.Cracked(otherSpec.seed)
        assertEquals(target, (SwapPlanner.plan(folded) as SwapAction.Apply).target)
        // Nothing moved: still wanted.
        assertTrue(SwapPlanner.stillWanted(folded, target, Posture.FOLDED, pendingHit, null))
        // The unfold consumed the roll and shows that crack: the same target, still wanted (logged late).
        assertTrue(SwapPlanner.stillWanted(folded, target, Posture.UNFOLDED, null, ActiveCrack(otherSpec, 0L, CrackCause.UNFOLD)))
        // The odds changed while folded and the re-roll missed: the crack is no longer wanted.
        assertFalse(SwapPlanner.stillWanted(folded, target, Posture.FOLDED, pendingMiss, null))
        // Unfolded with no crack (the roll missed after all), or a different crack: dropped.
        assertFalse(SwapPlanner.stillWanted(folded, target, Posture.UNFOLDED, null, null))
        assertFalse(SwapPlanner.stillWanted(folded, target, Posture.UNFOLDED, null, unfoldCrack))
        // The unfold is not decided yet, or the posture is unknown: nothing is wanted.
        assertFalse(SwapPlanner.stillWanted(folded, target, Posture.UNFOLDED, pendingHit, null))
        assertFalse(SwapPlanner.stillWanted(folded, target, Posture.UNKNOWN, null, null))
        // A put-back stays wanted while the crack is gone, not once a crack shows again.
        val unfolded = input(staged = Staged.Cracked(spec.seed))
        assertTrue(SwapPlanner.stillWanted(unfolded, Staged.Clean, Posture.UNFOLDED, null, null))
        assertFalse(SwapPlanner.stillWanted(unfolded, Staged.Clean, Posture.UNFOLDED, null, unfoldCrack))
    }

    @Test
    fun `a capture keeps only the id the check saw, unmoved and not ours`() {
        val ours = listOf(20, 21)
        assertNull(SwapIds.captureVerdict(stillSettled = true, expectedId = 30, readId = 30, idAfter = 30, ourIds = ours))
        // The posture moved during the read: whatever was read may be the other panel's.
        assertEquals(SwapFailure.NOT_UNFOLDED, SwapIds.captureVerdict(false, 30, 30, 30, ours))
        assertEquals(SwapFailure.NOT_UNFOLDED, SwapIds.captureVerdict(false, 30, 21, 31, ours))
        // Not the id the check confirmed, or the id moved between the id read and the file read.
        assertEquals(SwapFailure.CHANGED_DURING_READ, SwapIds.captureVerdict(true, 30, 31, 31, ours))
        assertEquals(SwapFailure.CHANGED_DURING_READ, SwapIds.captureVerdict(true, 30, 30, 31, ours))
        // One of Hingegate's own copies, even when the check expected it: never the original.
        assertEquals(SwapFailure.ALREADY_OURS, SwapIds.captureVerdict(true, 21, 21, 21, ours))
        // In that order: a moved id is reported as such before it is looked up among ours.
        assertEquals(SwapFailure.CHANGED_DURING_READ, SwapIds.captureVerdict(true, 30, 21, 21, ours))
    }

    @Test
    fun `a set is confirmed only by a new id that is not the original's`() {
        assertTrue(SwapIds.confirmsSet(before = 21, after = 22, captureId = 10))
        // The id did not change: the set did not show (yet).
        assertFalse(SwapIds.confirmsSet(before = 21, after = 21, captureId = 10))
        // The original still shows: the set did not reach the inner screen.
        assertFalse(SwapIds.confirmsSet(before = 10, after = 10, captureId = 10))
        assertFalse(SwapIds.confirmsSet(before = 21, after = 10, captureId = 10))
        // Not readable before or after (folded on Samsung), or no id.
        assertFalse(SwapIds.confirmsSet(before = null, after = 22, captureId = 10))
        assertFalse(SwapIds.confirmsSet(before = 21, after = null, captureId = 10))
        assertFalse(SwapIds.confirmsSet(before = 21, after = -1, captureId = 10))
    }

    @Test
    fun `a confirmed set is settled at once when the reads name the panel`() {
        // Samsung reading FLAG_SYSTEM | 4: the read-back is the inner panel's own id, folded or not.
        assertFalse(SwapIds.unconfirmedAfterSet(isSamsung = true, namesPanel = true, confirmed = true))
        assertTrue(SwapIds.unconfirmedAfterSet(isSamsung = true, namesPanel = true, confirmed = false))
        // Samsung with panel-less reads (build 1): the id may still change to the per-panel one.
        assertTrue(SwapIds.unconfirmedAfterSet(isSamsung = true, namesPanel = false, confirmed = true))
        assertTrue(SwapIds.unconfirmedAfterSet(isSamsung = true, namesPanel = false, confirmed = false))
        // One wallpaper for both panels.
        assertFalse(SwapIds.unconfirmedAfterSet(isSamsung = false, namesPanel = false, confirmed = true))
        assertTrue(SwapIds.unconfirmedAfterSet(isSamsung = false, namesPanel = false, confirmed = false))
    }

    @Test
    fun `a read-back counts while folded only when the reads name the panel`() {
        assertTrue(SwapIds.readBackCounts(namesPanel = true, settledBefore = false, settledAfter = false))
        assertTrue(SwapIds.readBackCounts(namesPanel = false, settledBefore = true, settledAfter = true))
        assertFalse(SwapIds.readBackCounts(namesPanel = false, settledBefore = true, settledAfter = false))
        assertFalse(SwapIds.readBackCounts(namesPanel = false, settledBefore = false, settledAfter = true))
    }

    @Test
    fun `only samsung reads name the inner panel`() {
        assertTrue(SwapFlags.namesPanel(SwapFlags.which(isSamsung = true, flagSystem = 1)))
        assertFalse(SwapFlags.namesPanel(SwapFlags.which(isSamsung = false, flagSystem = 1)))
    }

    @Test
    fun `a wallpaper set from the cover after a confirmed folded stage is suspected, not adopted`() {
        // The stage while folded was confirmed by a panel-named read-back: our id 31, nothing unconfirmed.
        val staged = SwapState(
            capture = SwapCapture(10, 0L),
            staged = Staged.Cracked(1),
            ourIds = listOf(31),
            unconfirmedSet = SwapIds.unconfirmedAfterSet(isSamsung = true, namesPanel = true, confirmed = true),
            lastSet = Staged.Cracked(1),
        )
        // The user then sets a new inner wallpaper (id 40) from the cover screen: an unknown id is a change.
        assertEquals(IdCheck.CHANGED, SwapIds.check(40, staged))
        // Had the stage stayed unconfirmed (build 1's rule on Samsung), the same id would have been adopted as ours.
        assertEquals(IdCheck.ADOPT, SwapIds.check(40, staged.copy(unconfirmedSet = true)))
    }

    @Test
    fun `a put-back is labelled once the crack is gone`() {
        val clean = apply(Staged.Clean, null, SwapTiming.ON_TIME)
        val cracked = apply(Staged.Cracked(1), spec, SwapTiming.LATE)
        assertTrue(SwapPlanner.restoreDue(restoreRequested = true, active = null))
        assertFalse(SwapPlanner.restoreDue(restoreRequested = true, active = manualCrack))
        assertFalse(SwapPlanner.restoreDue(restoreRequested = false, active = null))
        assertEquals(SwapTiming.RESTORE, SwapPlanner.timingFor(clean, restoreDue = true))
        assertEquals(SwapTiming.ON_TIME, SwapPlanner.timingFor(clean, restoreDue = false))
        assertEquals(SwapTiming.LATE, SwapPlanner.timingFor(cracked, restoreDue = true))
    }

    @Test
    fun `a stage that finished after the unfold is logged as late`() {
        assertEquals(SwapTiming.LATE, SwapPlanner.loggedTiming(SwapTiming.AHEAD, Posture.UNFOLDED))
        assertEquals(SwapTiming.AHEAD, SwapPlanner.loggedTiming(SwapTiming.AHEAD, Posture.FOLDED))
        SwapTiming.entries.filter { it != SwapTiming.AHEAD }.forEach {
            assertEquals(it, SwapPlanner.loggedTiming(it, Posture.UNFOLDED))
        }
    }

    @Test
    fun `nothing is staged from a stale capture`() {
        val folded = input(posture = Posture.FOLDED, pending = pendingHit, staged = null, hasOriginal = false)
        assertEquals(SwapAction.None, SwapPlanner.plan(folded))
    }

    @Test
    fun `no conclusion without a capture or an id`() {
        assertEquals(IdCheck.NONE, SwapIds.check(30, SwapState()))
        assertEquals(IdCheck.NONE, SwapIds.check(-1, captured))
        assertEquals(IdCheck.NONE, SwapIds.check(0, captured))
    }

    @Test
    fun `remembered ids are distinct, positive and capped`() {
        assertEquals(listOf(1, 2, 3), SwapIds.remember(listOf(1, 2), 3, null, -1))
        assertEquals(listOf(1, 3, 2), SwapIds.remember(listOf(1, 2, 3), 2))
        assertEquals(listOf(1, 2, 5), SwapIds.remember(listOf(1, 2), 5, 5))
        val many = SwapIds.remember((1..40).toList(), 41)
        assertEquals(SwapState.OUR_IDS_CAPACITY, many.size)
        assertEquals(41, many.last())
        assertFalse(1 in many)
    }

    // ── Flags ────────────────────────────────────────────────────────────────

    @Test
    fun `samsung gets the inner panel bit, everyone else plain FLAG_SYSTEM`() {
        val flagSystem = 1
        assertEquals(5, SwapFlags.which(isSamsung = true, flagSystem = flagSystem))
        assertEquals(1, SwapFlags.which(isSamsung = false, flagSystem = flagSystem))
        assertEquals(4, SwapFlags.FLAG_DISPLAY_PHONE)
    }

    @Test
    fun `a colours change on samsung's cover panel is ignored`() {
        // Samsung's flags carry the panel bit of the wallpaper that changed (SWAP-MODE §1): 5/7 inner, 17/18 cover.
        assertTrue(SwapFlags.concernsInner(isSamsung = true, which = 5))
        assertTrue(SwapFlags.concernsInner(isSamsung = true, which = 7))
        assertFalse(SwapFlags.concernsInner(isSamsung = true, which = 17))
        assertFalse(SwapFlags.concernsInner(isSamsung = true, which = 18))
        assertTrue(SwapFlags.concernsInner(isSamsung = true, which = 1))
        // Elsewhere there is no panel bit to look at.
        assertTrue(SwapFlags.concernsInner(isSamsung = false, which = 17))
        assertEquals(16, SwapFlags.FLAG_DISPLAY_SUB)
    }

    @Test
    fun `samsung is recognised whatever the case`() {
        assertTrue(SwapFlags.isSamsung("samsung"))
        assertTrue(SwapFlags.isSamsung("SAMSUNG"))
        assertFalse(SwapFlags.isSamsung("Google"))
        assertFalse(SwapFlags.isSamsung(""))
    }

    // ── Frames ───────────────────────────────────────────────────────────────

    private val wmHinge = HingeLine(HingeOrientation.VERTICAL, 1224f, 0f, HingeSource.WINDOW_MANAGER)

    @Test
    fun `the frame is the natural panel with the hinge mapped into it`() {
        // Fold 8 at rotation 0: natural 2448 x 1848, vertical hinge at 1224.
        val frame = SwapFrames.fromDisplay(2448, 1848, 0, wmHinge, 2.625f, 2448, 1848)
        assertEquals(SwapFrame(2448, 1848, wmHinge, 2.625f), frame)
    }

    @Test
    fun `a rotated display still gives the natural frame`() {
        // The same panel held at rotation 1: the screen is 1848 x 2448 and the hinge is horizontal on it.
        val screenHinge = HingeLine(HingeOrientation.HORIZONTAL, 1224f, 0f, HingeSource.WINDOW_MANAGER)
        val frame = SwapFrames.fromDisplay(1848, 2448, 1, screenHinge, 2.625f, 2448, 1848)
        assertEquals(2448, frame?.width)
        assertEquals(1848, frame?.height)
        assertEquals(HingeOrientation.VERTICAL, frame?.hinge?.orientation)
        assertEquals(1224f, frame?.hinge?.positionPx)
    }

    @Test
    fun `a size that does not have the inner panel's shape is not a frame`() {
        // A logical size still trailing the rotation: 2448 x 1848 reported at rotation 1.
        assertNull(SwapFrames.fromDisplay(2448, 1848, 1, wmHinge, 2.625f, 2448, 1848))
        // The cover screen's size while the posture already says unfolded.
        assertNull(SwapFrames.fromDisplay(1248, 1972, 0, wmHinge, 2.625f, 2448, 1848))
        assertNull(SwapFrames.fromDisplay(0, 1848, 0, wmHinge, 2.625f, 2448, 1848))
    }

    @Test
    fun `without a learned inner panel any size is accepted`() {
        assertEquals(1856, SwapFrames.fromDisplay(1856, 2160, 0, wmHinge, 2.4f, null, null)?.width)
    }

    @Test
    fun `window manager's hinge replaces an estimate, never the other way round`() {
        val estimated = SwapFrame(2448, 1848, wmHinge.copy(positionPx = 1200f, source = HingeSource.ESTIMATED), 2.625f)
        val measured = SwapFrame(2448, 1848, wmHinge, 2.625f)
        assertEquals(estimated, SwapFrames.better(null, estimated))
        assertEquals(measured, SwapFrames.better(estimated, measured))
        assertEquals(measured, SwapFrames.better(measured, estimated))
    }

    @Test
    fun `a newer estimate replaces an older estimate`() {
        // A frame estimated before the cover screen was known may have the wrong axis; it must be able to heal.
        val wrong = SwapFrame(1856, 2160, HingeLine(HingeOrientation.HORIZONTAL, 1080f, 0f, HingeSource.ESTIMATED), 2.6f)
        val right = wrong.copy(hinge = HingeLine(HingeOrientation.VERTICAL, 928f, 0f, HingeSource.ESTIMATED))
        assertEquals(right, SwapFrames.better(wrong, right))
    }

    @Test
    fun `a new panel size or density replaces the frame`() {
        val old = SwapFrame(2448, 1848, wmHinge, 2.625f)
        val estimate = old.copy(hinge = wmHinge.copy(source = HingeSource.ESTIMATED))
        assertEquals(estimate.copy(density = 3f), SwapFrames.better(old, estimate.copy(density = 3f)))
        assertEquals(estimate.copy(width = 2000), SwapFrames.better(old, estimate.copy(width = 2000)))
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    fun `the swap state round trips as json`() {
        val state = SwapState(
            capture = SwapCapture(10, 123L),
            frame = SwapFrame(2448, 1848, wmHinge, 2.625f),
            staged = Staged.Cracked(spec.seed),
            ourIds = listOf(20, 21),
            unconfirmedSet = true,
            needsRecapture = true,
            lastSet = Staged.Clean,
            suspectId = 30,
            recaptureFailure = SwapFailure.LIVE_WALLPAPER,
        )
        val json = lenient
        assertEquals(state, json.decodeFromString(SwapState.serializer(), json.encodeToString(SwapState.serializer(), state)))
        val clean = state.copy(staged = Staged.Clean)
        assertEquals(clean, json.decodeFromString(SwapState.serializer(), json.encodeToString(SwapState.serializer(), clean)))
        assertEquals(SwapState(), json.decodeFromString(SwapState.serializer(), "{}"))
    }

    @Test
    fun `a state saved by build 1 still loads, with nothing suspected`() {
        val build1 = """{"capture":{"wallpaperId":79,"capturedAtEpochMs":5},""" +
            """"frame":{"width":2448,"height":1848,"hinge":{"orientation":"VERTICAL","positionPx":1224.0,""" +
            """"thicknessPx":0.0,"source":"WINDOW_MANAGER"},"density":2.625},""" +
            """"staged":{"type":"cracked","seed":42},"ourIds":[80,81],"unconfirmedSet":true,"lastSet":{"type":"cracked","seed":42}}"""
        val state = lenient.decodeFromString(SwapState.serializer(), build1)
        assertEquals(SwapCapture(79, 5L), state.capture)
        assertEquals(Staged.Cracked(42), state.staged)
        assertNull(state.suspectId)
        assertNull(state.recaptureFailure)
        assertTrue(state.hasOriginal)
    }

    @Test
    fun `the original counts only with a capture and a frame`() {
        assertFalse(SwapState().hasOriginal)
        assertFalse(SwapState(capture = SwapCapture(1, 0L)).hasOriginal)
        assertTrue(SwapState(capture = SwapCapture(1, 0L), frame = SwapFrame(1, 1, wmHinge, 1f)).hasOriginal)
        assertFalse(SwapState(capture = SwapCapture(1, 0L), frame = SwapFrame(1, 1, wmHinge, 1f), needsRecapture = true).hasOriginal)
        assertFalse(SwapState(capture = SwapCapture(1, 0L), frame = SwapFrame(1, 1, wmHinge, 1f), suspectId = 2).hasOriginal)
    }
}
