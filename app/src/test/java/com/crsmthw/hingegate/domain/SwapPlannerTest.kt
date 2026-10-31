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
    fun `the id is read for the inner screen only`() {
        assertTrue(SwapIds.readable(innerInUse = true, isSamsung = true, controllerPosture = Posture.UNFOLDED))
        // Samsung answers for the panel in use: folded, that is the cover's wallpaper.
        assertFalse(SwapIds.readable(innerInUse = false, isSamsung = true, controllerPosture = Posture.FOLDED))
        // One wallpaper for both panels: folded is fine too, unknown is not.
        assertTrue(SwapIds.readable(innerInUse = false, isSamsung = false, controllerPosture = Posture.FOLDED))
        assertFalse(SwapIds.readable(innerInUse = false, isSamsung = false, controllerPosture = Posture.UNKNOWN))
    }

    @Test
    fun `the id is checked only with a capture and something to protect`() {
        assertFalse(SwapIds.shouldCheck(SwapState(), swapOn = true, readable = true))
        assertTrue(SwapIds.shouldCheck(captured, swapOn = true, readable = true))
        assertFalse(SwapIds.shouldCheck(captured, swapOn = true, readable = false))
        // Out of the mode, only while something of ours may still be showing.
        assertFalse(SwapIds.shouldCheck(captured, swapOn = false, readable = true))
        assertTrue(SwapIds.shouldCheck(captured.copy(staged = Staged.Clean), swapOn = false, readable = true))
    }

    @Test
    fun `our copy confirmed clears the unconfirmed mark, otherwise nothing changes`() {
        assertEquals(IdDecision(IdAction.KEEP), SwapIds.decide(21, captured, swapOn = true, innerInUse = true))
        val unconfirmed = captured.copy(staged = Staged.Cracked(1), unconfirmedSet = true)
        assertEquals(IdDecision(IdAction.CONFIRM), SwapIds.decide(21, unconfirmed, swapOn = true, innerInUse = true))
        assertEquals(unconfirmed.copy(unconfirmedSet = false), SwapIds.settle(IdAction.CONFIRM, unconfirmed, 21))
    }

    @Test
    fun `the original showing after an unconfirmed set is a miss, logged and reset`() {
        val staged = captured.copy(staged = Staged.Cracked(1), unconfirmedSet = true)
        val decision = SwapIds.decide(10, staged, swapOn = true, innerInUse = true)
        assertEquals(IdDecision(IdAction.RESET, missed = true), decision)
        assertEquals(captured, SwapIds.settle(IdAction.RESET, staged, 10))
    }

    @Test
    fun `our copy reported after a miss was reset takes the staged state back`() {
        // The read-back lagged: the original's id showed first, then our copy's.
        val reset = captured.copy(staged = null, lastSet = Staged.Cracked(1))
        assertEquals(IdDecision(IdAction.CONFIRM), SwapIds.decide(21, reset, swapOn = true, innerInUse = true))
        assertEquals(Staged.Cracked(1), SwapIds.settle(IdAction.CONFIRM, reset, 21).staged)
        // A staged state is never overwritten by it.
        val staged = captured.copy(staged = Staged.Clean, lastSet = Staged.Cracked(1), unconfirmedSet = true)
        assertEquals(Staged.Clean, SwapIds.settle(IdAction.CONFIRM, staged, 21).staged)
    }

    @Test
    fun `the original showing with a confirmed stage resets without a miss`() {
        val staged = captured.copy(staged = Staged.Clean)
        assertEquals(IdDecision(IdAction.RESET, missed = false), SwapIds.decide(10, staged, swapOn = true, innerInUse = true))
        assertEquals(IdDecision(IdAction.KEEP), SwapIds.decide(10, captured, swapOn = true, innerInUse = true))
    }

    @Test
    fun `an unknown id after an unconfirmed set is adopted as ours`() {
        val unconfirmed = captured.copy(staged = Staged.Cracked(1), unconfirmedSet = true)
        assertEquals(IdDecision(IdAction.ADOPT), SwapIds.decide(30, unconfirmed, swapOn = true, innerInUse = true))
        val adopted = SwapIds.settle(IdAction.ADOPT, unconfirmed, 30)
        assertEquals(listOf(20, 21, 30), adopted.ourIds)
        assertFalse(adopted.unconfirmedSet)
        assertEquals(Staged.Cracked(1), adopted.staged)
    }

    @Test
    fun `a new wallpaper is recaptured only in the mode with the inner screen in use`() {
        val staged = captured.copy(staged = Staged.Cracked(1))
        assertEquals(IdDecision(IdAction.RECAPTURE), SwapIds.decide(30, staged, swapOn = true, innerInUse = true))
        // Folded on a one-wallpaper phone: readable, but recapturing waits for the unfold.
        assertEquals(IdDecision(IdAction.FORGET), SwapIds.decide(30, staged, swapOn = true, innerInUse = false))
        assertEquals(IdDecision(IdAction.FORGET), SwapIds.decide(30, staged, swapOn = false, innerInUse = true))
        assertEquals(captured, SwapIds.settle(IdAction.FORGET, staged, 30))
        assertEquals(staged, SwapIds.settle(IdAction.RECAPTURE, staged, 30))
    }

    @Test
    fun `a stale capture is cleared by the original or our copy showing, and recaptured on a new id`() {
        val stale = captured.copy(needsRecapture = true)
        assertFalse(stale.hasOriginal)
        assertEquals(IdDecision(IdAction.RESET), SwapIds.decide(10, stale, swapOn = true, innerInUse = true))
        assertEquals(captured, SwapIds.settle(IdAction.RESET, stale, 10))
        assertEquals(IdDecision(IdAction.CONFIRM), SwapIds.decide(21, stale, swapOn = true, innerInUse = true))
        assertFalse(SwapIds.settle(IdAction.CONFIRM, stale, 21).needsRecapture)
        assertEquals(IdDecision(IdAction.RECAPTURE), SwapIds.decide(30, stale, swapOn = true, innerInUse = true))
        // Unreadable now: the mark stays until the new wallpaper can be read.
        assertTrue(SwapIds.settle(IdAction.FORGET, stale, 30).needsRecapture)
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
    fun `samsung sets stay unconfirmed until the next check, others only when not confirmed`() {
        assertTrue(SwapIds.unconfirmedAfterSet(isSamsung = true, confirmed = true))
        assertTrue(SwapIds.unconfirmedAfterSet(isSamsung = true, confirmed = false))
        assertFalse(SwapIds.unconfirmedAfterSet(isSamsung = false, confirmed = true))
        assertTrue(SwapIds.unconfirmedAfterSet(isSamsung = false, confirmed = false))
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
        )
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(state, json.decodeFromString(SwapState.serializer(), json.encodeToString(SwapState.serializer(), state)))
        val clean = state.copy(staged = Staged.Clean)
        assertEquals(clean, json.decodeFromString(SwapState.serializer(), json.encodeToString(SwapState.serializer(), clean)))
        assertEquals(SwapState(), json.decodeFromString(SwapState.serializer(), "{}"))
    }

    @Test
    fun `the original counts only with a capture and a frame`() {
        assertFalse(SwapState().hasOriginal)
        assertFalse(SwapState(capture = SwapCapture(1, 0L)).hasOriginal)
        assertTrue(SwapState(capture = SwapCapture(1, 0L), frame = SwapFrame(1, 1, wmHinge, 1f)).hasOriginal)
        assertFalse(SwapState(capture = SwapCapture(1, 0L), frame = SwapFrame(1, 1, wmHinge, 1f), needsRecapture = true).hasOriginal)
    }
}
