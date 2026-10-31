package com.crsmthw.hingegate.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * What the Real wallpaper mode last put on the inner screen's home wallpaper (docs/SWAP-MODE.md §3). `null` in
 * [SwapState.staged] means "nothing of ours: the user's own wallpaper is untouched".
 */
@Serializable
sealed interface Staged {
    /** The captured original, set back by Hingegate. */
    @Serializable
    @SerialName("clean")
    data object Clean : Staged

    /** The original with the crack of this seed on top. */
    @Serializable
    @SerialName("cracked")
    data class Cracked(val seed: Long) : Staged
}

/**
 * The inner panel as the swapped wallpaper is drawn for it: its NATURAL (rotation 0) size in pixels, the hinge
 * mapped into that frame, and the panel's density. Recorded while unfolded, so staging works while folded, when
 * the current display is the cover screen.
 */
@Serializable
data class SwapFrame(val width: Int, val height: Int, val hinge: HingeLine, val density: Float)

/** The user's wallpaper as captured: its system id at the time, and when. The image itself is a file. */
@Serializable
data class SwapCapture(val wallpaperId: Int, val capturedAtEpochMs: Long)

/** Everything the swapper persists, in one value so it is written in one edit. */
@Serializable
data class SwapState(
    val capture: SwapCapture? = null,
    val frame: SwapFrame? = null,
    val staged: Staged? = null,
    /** Ids the system returned (or reported right after) for Hingegate's own sets, newest last. */
    val ourIds: List<Int> = emptyList(),
    /**
     * A set made while folded whose id has not been confirmed on the unfolded screen yet: Samsung's per-panel
     * wallpaper may report a different id than the one `setBitmap` returned (docs/SWAP-MODE.md §1).
     */
    val unconfirmedSet: Boolean = false,
    /**
     * The user changed the wallpaper, but reading the new one failed for a passing reason (the phone folded during
     * the read, or the panel size was not known): the old capture is kept but must not be used — staging it would
     * put the OLD wallpaper over the new one — until the new one is read at the next chance.
     */
    val needsRecapture: Boolean = false,
    /**
     * What Hingegate set last (its id is the newest in [ourIds]). If a set is found missing and [staged] reset,
     * and the inner screen reports our id after all (a late read-back), this is what is showing.
     */
    val lastSet: Staged? = null,
    /**
     * An id that is neither ours nor the original's, seen once in a settled read (docs/SWAP-MODE.md §10.25): the
     * user may have changed the wallpaper. Nothing is staged from the old capture ([hasOriginal] is false) and
     * nothing of ours is touched until a second settled read confirms it (recapture) or shows the original or our
     * copy (cleared). Unlike [needsRecapture], [staged] is kept: the change is not proven yet.
     */
    val suspectId: Int? = null,
    /**
     * The reason the last automatic recapture failed for good-looking reasons (a live wallpaper, the permission
     * gone, an unreadable file), seen ONCE: the original is kept, marked stale, and the read is tried again in a
     * settled state; only a second such failure lets go of it (docs/SWAP-MODE.md §10.26).
     */
    val recaptureFailure: SwapFailure? = null,
) {
    val hasOriginal: Boolean
        get() = capture != null && frame != null && !needsRecapture && suspectId == null

    companion object {
        /** How many of our own ids are remembered; ids only grow, so old ones never come back. */
        const val OUR_IDS_CAPACITY = 32
    }
}

/** When a set happens, for the log: staged ahead while folded, as things happen, late (visibly), or a put-back. */
enum class SwapTiming { AHEAD, ON_TIME, LATE, RESTORE }

sealed interface SwapAction {
    data object None : SwapAction

    /** Set [target]; [spec] is the crack to draw for a [Staged.Cracked] target. */
    data class Apply(val target: Staged, val spec: CrackSpec?, val timing: SwapTiming) : SwapAction
}

/**
 * The planner's inputs. [posture], [pending] and [active] must come from ONE consistent state (the controller's,
 * published under its lock): the display's posture changes a moment before the controller has acted on it.
 */
data class SwapInput(
    val swapMode: Boolean,
    val enabled: Boolean,
    val posture: Posture,
    val pending: PendingRoll?,
    val active: ActiveCrack?,
    val staged: Staged?,
    val isSamsung: Boolean,
    val hasOriginal: Boolean,
)

/**
 * Decides what the inner screen's wallpaper should be now (docs/SWAP-MODE.md §3). Pure; the Android side is
 * `WallpaperSwapper`. A state already staged is never set again.
 *
 * - Off, or another mode: a staged crack is replaced by the clean original once; otherwise nothing is touched.
 * - Unfolded: the active crack, else clean.
 * - Folded, Samsung: what the pending roll decided for the next unfold (staged while the inner panel is dark).
 * - Folded, anyone else: clean (one wallpaper for both panels; the crack goes on at the unfold).
 * - Clean while the user's own wallpaper is untouched needs no set at all.
 */
object SwapPlanner {

    fun plan(input: SwapInput): SwapAction {
        if (!input.hasOriginal) return SwapAction.None
        if (!input.swapMode || !input.enabled) {
            return if (input.staged is Staged.Cracked) {
                SwapAction.Apply(Staged.Clean, null, SwapTiming.RESTORE)
            } else {
                SwapAction.None
            }
        }
        val spec: CrackSpec? = when (input.posture) {
            // A pending roll while unfolded means the unfold has not been decided yet: wait for it.
            Posture.UNFOLDED -> if (input.pending != null) return SwapAction.None else input.active?.spec
            Posture.FOLDED -> if (input.isSamsung) {
                val pending = input.pending ?: return SwapAction.None
                pending.spec.takeIf { pending.hit }
            } else {
                null
            }
            Posture.UNKNOWN -> return SwapAction.None
        }
        val target: Staged = spec?.let { Staged.Cracked(it.seed) } ?: Staged.Clean
        if (target == input.staged) return SwapAction.None
        if (target == Staged.Clean && input.staged == null) return SwapAction.None
        val timing = when {
            input.posture == Posture.FOLDED -> if (input.isSamsung) SwapTiming.AHEAD else SwapTiming.ON_TIME
            // On Samsung an unfold's crack should have been staged while folded; setting it now is visible.
            input.isSamsung && target is Staged.Cracked && input.active?.cause == CrackCause.UNFOLD -> SwapTiming.LATE
            else -> SwapTiming.ON_TIME
        }
        return SwapAction.Apply(target, spec, timing)
    }

    /**
     * The last look right before the binder set (docs/SWAP-MODE.md §10.29): whether [planned] is still what the
     * planner wants now that the controller says [posture], [pending] and [active] (the rest of [input] as it was
     * planned). The controller may have moved on while the image was found or made; false drops the set, and the
     * next reconcile plans afresh.
     */
    fun stillWanted(input: SwapInput, planned: Staged, posture: Posture, pending: PendingRoll?, active: ActiveCrack?): Boolean {
        val replanned = plan(input.copy(posture = posture, pending = pending, active = active))
        return replanned is SwapAction.Apply && replanned.target == planned
    }

    /** "Put my wallpaper back" is due once the crack it clears is gone (the clear runs first). */
    fun restoreDue(restoreRequested: Boolean, active: ActiveCrack?): Boolean = restoreRequested && active == null

    /** The timing a set is logged with: a clean set right after "Put my wallpaper back" reads as the put-back. */
    fun timingFor(action: SwapAction.Apply, restoreDue: Boolean): SwapTiming =
        if (restoreDue && action.target == Staged.Clean) SwapTiming.RESTORE else action.timing

    /**
     * The timing a FINISHED set is logged with (docs/SWAP-MODE.md §10.29): a set staged for the next unfold that
     * only returned once the controller saw the phone unfolded again may have been seen changing, so it is logged
     * as late rather than as staged ahead.
     */
    fun loggedTiming(planned: SwapTiming, postureWhenDone: Posture): SwapTiming =
        if (planned == SwapTiming.AHEAD && postureWhenDone == Posture.UNFOLDED) SwapTiming.LATE else planned
}

/** What the system's current wallpaper id says about the inner screen's wallpaper. */
enum class IdCheck {
    /** No capture, or the system gave no id: nothing can be concluded. */
    NONE,

    /** One of Hingegate's own images is showing. */
    OURS,

    /** The captured original is showing. */
    ORIGINAL,

    /** An unknown id right after a set made while folded: that set's id as the unfolded screen reports it. */
    ADOPT,

    /** The user set a different wallpaper since the capture. */
    CHANGED,
}

/** What the swapper does about the id it read (docs/SWAP-MODE.md §3, §10.4–10.5). */
enum class IdAction {
    /** Nothing to change. */
    KEEP,

    /**
     * Our copy is confirmed on the inner screen: the unconfirmed (and any stale-capture) mark goes, and a staged
     * state reset by an early miss is taken back from [SwapState.lastSet].
     */
    CONFIRM,

    /** The captured original is showing: nothing of ours is staged any more. */
    RESET,

    /** The id is remembered as ours (a set's id as the inner screen reports it). */
    ADOPT,

    /**
     * An unknown id, seen for the first time (or not yet confirmed a few hundred ms later in the same settled
     * unfold): hold everything ([SwapState.suspectId]) and read again (docs/SWAP-MODE.md §10.25).
     */
    SUSPECT,

    /** The user changed the wallpaper (two settled reads agree) and the mode is on: read the new one. */
    RECAPTURE,

    /** The user changed the wallpaper (two settled reads agree) and the mode is off: stop claiming the screen. */
    FORGET,
}

/** [action], and whether a set of ours was found not to have reached the inner screen (the log's proof line). */
data class IdDecision(val action: IdAction, val missed: Boolean = false)

/**
 * The first settled sighting of an unknown id, kept in memory: [atMs] on the monotonic clock, [epoch] the
 * controller's transition count then. A second read confirms it only within the same unfold (same epoch).
 */
data class Sighting(val id: Int, val atMs: Long, val epoch: Long)

/** What a failed automatic recapture leads to (docs/SWAP-MODE.md §10.26). */
enum class RecaptureOutcome {
    /** A passing reason (the read raced a fold, the panel size, the id moving): the capture is kept, marked stale. */
    KEEP_STALE,

    /** A final-looking reason seen for the first time: keep everything, read again in a settled state. */
    RETRY,

    /** The same kind of failure confirmed by a second settled read: the original is deleted, the mode lets go. */
    LET_GO,
}

object SwapIds {

    /**
     * The ORIGINAL test comes first: the captured id can never be one of ours (a capture of our own image is
     * refused, and our sets get new ids), so this also heals a state where it was wrongly remembered as ours.
     */
    fun check(currentId: Int, state: SwapState): IdCheck {
        val capture = state.capture ?: return IdCheck.NONE
        return when {
            currentId <= 0 -> IdCheck.NONE
            currentId == capture.wallpaperId -> IdCheck.ORIGINAL
            currentId in state.ourIds -> IdCheck.OURS
            state.unconfirmedSet -> IdCheck.ADOPT
            else -> IdCheck.CHANGED
        }
    }

    /**
     * Whether the id is worth reading: there is a capture, and the mode is on or something of ours is staged.
     * WHEN it may be read is [SwapSettle]'s: only in a settled inner state (docs/SWAP-MODE.md §10.24).
     */
    fun shouldCheck(state: SwapState, swapOn: Boolean): Boolean = state.capture != null && (swapOn || state.staged != null)

    /**
     * What to do about the inner screen's current [id], read in a settled state at [nowMs] (monotonic) during the
     * unfold numbered [epoch]. An unknown id is acted on only when [sighting] saw the same id at least
     * [SwapSettle.CONFIRM_MS] earlier in the same unfold, and it was held as [SwapState.suspectId] since; until
     * then it is only SUSPECTED.
     */
    fun decide(id: Int, state: SwapState, swapOn: Boolean, sighting: Sighting?, epoch: Long, nowMs: Long): IdDecision =
        when (check(id, state)) {
            IdCheck.NONE -> IdDecision(IdAction.KEEP)
            IdCheck.OURS -> IdDecision(
                if (state.unconfirmedSet || state.needsRecapture || state.suspectId != null ||
                    (state.staged == null && state.lastSet != null)
                ) {
                    IdAction.CONFIRM
                } else {
                    IdAction.KEEP
                },
            )
            IdCheck.ORIGINAL -> IdDecision(
                action = if (state.staged != null || state.unconfirmedSet || state.needsRecapture || state.suspectId != null) {
                    IdAction.RESET
                } else {
                    IdAction.KEEP
                },
                // A set of ours was waiting for confirmation and the original shows instead: it never got there.
                missed = state.staged != null && state.unconfirmedSet,
            )
            IdCheck.ADOPT -> IdDecision(IdAction.ADOPT)
            IdCheck.CHANGED -> IdDecision(
                when {
                    !confirmsChange(id, state, sighting, epoch, nowMs) -> IdAction.SUSPECT
                    swapOn -> IdAction.RECAPTURE
                    else -> IdAction.FORGET
                },
            )
        }

    /** Whether a second settled read of the unknown [id] confirms the change [sighting] first saw. */
    fun confirmsChange(id: Int, state: SwapState, sighting: Sighting?, epoch: Long, nowMs: Long): Boolean =
        state.suspectId == id &&
            sighting != null &&
            sighting.id == id &&
            sighting.epoch == epoch &&
            nowMs - sighting.atMs >= SwapSettle.CONFIRM_MS

    /**
     * The sighting kept after [decision]: the FIRST sighting of a suspected id in this unfold (a later read must
     * not push the confirmation back), a new one for another id or unfold, none once the id is resolved.
     */
    fun nextSighting(previous: Sighting?, decision: IdAction, id: Int, epoch: Long, nowMs: Long): Sighting? = when {
        decision != IdAction.SUSPECT -> null
        previous != null && previous.id == id && previous.epoch == epoch -> previous
        else -> Sighting(id, nowMs, epoch)
    }

    /** How long until [sighting] can be confirmed (the recheck to schedule after a SUSPECT). */
    fun confirmDelayMs(sighting: Sighting, nowMs: Long): Long =
        (SwapSettle.CONFIRM_MS - (nowMs - sighting.atMs)).coerceAtLeast(0L)

    /** The state after [action] for [id]; [IdAction.RECAPTURE] is the swapper's (it reads the wallpaper). */
    fun settle(action: IdAction, state: SwapState, id: Int): SwapState = when (action) {
        IdAction.KEEP, IdAction.RECAPTURE -> state
        IdAction.SUSPECT -> state.copy(suspectId = id)
        IdAction.CONFIRM -> state.copy(
            staged = state.staged ?: state.lastSet,
            unconfirmedSet = false,
            needsRecapture = false,
            suspectId = null,
            recaptureFailure = null,
        )
        IdAction.RESET -> state.copy(
            staged = null,
            unconfirmedSet = false,
            needsRecapture = false,
            suspectId = null,
            recaptureFailure = null,
        )
        // What shows is what was set last: the same as what is staged after a set, and the set the process died
        // in when only [beforeSet]'s mark was saved.
        IdAction.ADOPT -> state.copy(
            staged = state.lastSet ?: state.staged,
            ourIds = remember(state.ourIds, id),
            unconfirmedSet = false,
            suspectId = null,
        )
        IdAction.FORGET -> state.copy(staged = null, unconfirmedSet = false, suspectId = null)
    }

    /**
     * A failed automatic recapture (the change itself was confirmed by two settled reads, so the user's new
     * wallpaper is what shows: nothing of ours is staged any more). Passing reasons keep the old capture, marked
     * stale. A final-looking reason (live wallpaper, permission, unreadable) is believed only when a second settled
     * attempt fails for such a reason too; the first one keeps the capture and asks for that attempt.
     */
    fun afterRecaptureFailure(state: SwapState, failure: SwapFailure): Pair<RecaptureOutcome, SwapState> {
        val stale = state.copy(staged = null, unconfirmedSet = false, needsRecapture = true, suspectId = null)
        return when {
            !isFinal(failure) -> RecaptureOutcome.KEEP_STALE to stale.copy(recaptureFailure = null)
            state.recaptureFailure == null -> RecaptureOutcome.RETRY to stale.copy(recaptureFailure = failure)
            else -> RecaptureOutcome.LET_GO to SwapState(frame = state.frame, ourIds = state.ourIds)
        }
    }

    /**
     * Whether a capture (automatic or by hand) may keep what it read (docs/SWAP-MODE.md §10.26), checked in this
     * order: the posture key moved during the read ([stillSettled] false: [SwapFailure.NOT_UNFOLDED]); the id the
     * read saw ([readId]) is not the one the check saw ([expectedId]), or the id moved during the read ([idAfter]:
     * [SwapFailure.CHANGED_DURING_READ]); it is one of Hingegate's own copies ([SwapFailure.ALREADY_OURS]) — an
     * automatic recapture must never take Hingegate's copy for the original. Null: keep it.
     */
    fun captureVerdict(stillSettled: Boolean, expectedId: Int, readId: Int, idAfter: Int, ourIds: List<Int>): SwapFailure? = when {
        !stillSettled -> SwapFailure.NOT_UNFOLDED
        readId != expectedId || idAfter != readId -> SwapFailure.CHANGED_DURING_READ
        readId in ourIds -> SwapFailure.ALREADY_OURS
        else -> null
    }

    /** Failures that say the wallpaper cannot be read at all, as opposed to "not right now". */
    fun isFinal(failure: SwapFailure): Boolean = when (failure) {
        SwapFailure.LIVE_WALLPAPER, SwapFailure.NO_PERMISSION, SwapFailure.READ_FAILED, SwapFailure.SET_FAILED -> true
        SwapFailure.NOT_UNFOLDED, SwapFailure.ALREADY_OURS, SwapFailure.NO_PANEL_SIZE, SwapFailure.CHANGED_DURING_READ -> false
    }

    /**
     * The state saved right BEFORE the binder set of [target] (docs/SWAP-MODE.md §10.26): the set marked unconfirmed,
     * [target] as the last set. If the process dies after the system applied the image but before the set's own save
     * (an install, a kill), the next settled check finds an id Hingegate never recorded; with this mark it is ADOPTED
     * as the copy being set ([IdAction.ADOPT] takes [SwapState.lastSet] as staged) instead of being taken for the
     * user's new wallpaper and recaptured — which would make Hingegate's own (cracked) copy the "original". If the
     * set never got there, the old id confirms what was staged, or the original's resets it, as after any set. A set
     * that fails saves the state from before the mark again.
     */
    fun beforeSet(state: SwapState, target: Staged): SwapState = state.copy(unconfirmedSet = true, lastSet = target)

    /**
     * Whether a set must wait (docs/SWAP-MODE.md §10.27): the inner screen shows an id that is neither ours, nor
     * the original's, nor possibly our unconfirmed set's — a wallpaper the user set that no settled check has seen
     * yet. Setting over it would replace the user's new wallpaper with a copy of the old one. Reading [beforeId]
     * for this needs no settled state (the read only ever HOLDS a set back; it never changes the state).
     */
    fun blocksSet(beforeId: Int?, state: SwapState): Boolean =
        beforeId != null && check(beforeId, state) == IdCheck.CHANGED

    /**
     * Whether the id read back right after a set names the new image: it can be read for the inner screen before
     * ([before]) and after ([after]) the set, it changed, and it is not the captured original's. Anything else
     * leaves the set unconfirmed, for the next check to settle (an id that never changed, or the original's,
     * would otherwise be remembered as ours and the original taken for Hingegate's copy).
     */
    fun confirmsSet(before: Int?, after: Int?, captureId: Int?): Boolean =
        before != null && after != null && after > 0 && after != before && after != captureId

    /**
     * Whether the ids read right before and after a set say anything about the inner screen (docs/SWAP-MODE.md
     * §10.33): always when the reads name the inner panel ([namesPanel], Samsung's `FLAG_SYSTEM | 4`, answered for
     * the inner panel whatever the fold state), else only when both were taken in a settled inner state.
     */
    fun readBackCounts(namesPanel: Boolean, settledBefore: Boolean, settledAfter: Boolean): Boolean =
        namesPanel || (settledBefore && settledAfter)

    /**
     * Whether a set stays unconfirmed: when the read-back did not confirm it, and on Samsung also whenever the reads
     * do not name the panel (the panel-less id may still change to the per-panel one, docs/SWAP-MODE.md §10.4).
     * With reads that name the inner panel, a confirmed set is settled at once — even one staged while folded — so a
     * wallpaper the user then sets is suspected and captured, never adopted as Hingegate's copy (§10.33).
     */
    fun unconfirmedAfterSet(isSamsung: Boolean, namesPanel: Boolean, confirmed: Boolean): Boolean =
        !confirmed || (isSamsung && !namesPanel)

    /** [ids] added to [ourIds] (no duplicates), keeping the newest [SwapState.OUR_IDS_CAPACITY]. */
    fun remember(ourIds: List<Int>, vararg ids: Int?): List<Int> {
        val added = ids.filterNotNull().filter { it > 0 }
        return (ourIds.filter { it !in added } + added.distinct()).takeLast(SwapState.OUR_IDS_CAPACITY)
    }
}

/** The `which` flags for `WallpaperManager`'s sets AND reads (docs/SWAP-MODE.md §1, §3). */
object SwapFlags {

    /**
     * Samsung's `WallpaperManager.FLAG_DISPLAY_PHONE` (the inner, "main" panel), read from the Fold 8's
     * framework.jar (docs/SWAP-MODE.md §1; `FLAG_DISPLAY_SUB` = 16 is the cover, `FLAG_MODE_MASK` = 60). Without a
     * panel bit Samsung picks the panel in use, so this bit is what lets the inner wallpaper be set while folded —
     * and, for `getWallpaperId` / `getWallpaperFile` / `getWallpaperInfo(int)`, read for the inner panel whatever
     * the fold state (proven from the phones' services.jar, §1).
     */
    const val FLAG_DISPLAY_PHONE = 4

    /** Samsung's `WallpaperManager.FLAG_DISPLAY_SUB`: the cover panel. */
    const val FLAG_DISPLAY_SUB = 16

    fun isSamsung(manufacturer: String): Boolean = manufacturer.equals(SAMSUNG, ignoreCase = true)

    /**
     * [flagSystem] (`WallpaperManager.FLAG_SYSTEM`), plus the inner-panel bit on Samsung. NEVER the bit elsewhere:
     * AOSP throws for anything but exactly `FLAG_SYSTEM` or `FLAG_LOCK` on reads.
     */
    fun which(isSamsung: Boolean, flagSystem: Int): Int = if (isSamsung) flagSystem or FLAG_DISPLAY_PHONE else flagSystem

    /** Whether reads with [which] name the inner panel (Samsung's bit), so their answer never follows the fold state. */
    fun namesPanel(which: Int): Boolean = which and FLAG_DISPLAY_PHONE != 0

    /**
     * Whether a wallpaper-colours change reported with [which] can concern the inner screen's wallpaper: on
     * Samsung the flags carry the panel bit of the wallpaper that changed (docs/SWAP-MODE.md §1), and a change on
     * the cover panel is none of this mode's business.
     */
    fun concernsInner(isSamsung: Boolean, which: Int): Boolean = !isSamsung || (which and FLAG_DISPLAY_SUB) == 0

    private const val SAMSUNG = "samsung"
}

/** Builds and keeps the [SwapFrame] (docs/SWAP-MODE.md §3, §9). */
object SwapFrames {

    /** Natural sizes this close in aspect (relative) count as the same panel. */
    const val ASPECT_TOLERANCE = 0.02f

    /**
     * The frame from the unfolded display: [displayW] × [displayH] at [rotation], the hinge in those screen
     * coordinates, mapped into the natural frame. Null when the size does not have the learned inner panel's
     * shape ([innerW] × [innerH], natural): the logical size can trail a rotation or a panel swap for a moment.
     */
    fun fromDisplay(
        displayW: Int,
        displayH: Int,
        rotation: Int,
        hingeInScreen: HingeLine,
        density: Float,
        innerW: Int?,
        innerH: Int?,
    ): SwapFrame? {
        if (displayW <= 0 || displayH <= 0 || density <= 0f) return null
        val (naturalW, naturalH) = PanelTransform.naturalSize(rotation, displayW.toFloat(), displayH.toFloat())
        if (innerW != null && innerH != null && innerW > 0 && innerH > 0) {
            val inner = innerW.toFloat() / innerH
            if (abs(naturalW / naturalH - inner) / inner > ASPECT_TOLERANCE) return null
        }
        val hinge = PanelTransform.hingeToNatural(rotation, displayW.toFloat(), displayH.toFloat(), hingeInScreen)
        return SwapFrame(naturalW.toInt(), naturalH.toInt(), hinge, density)
    }

    /**
     * Which frame to keep: the first one, then a newer one when the panel's size or density changed, when it has
     * Window Manager's hinge, or when the kept one is only an estimate too (a newer estimate may know the cover
     * screen by now). An estimate never replaces the phone's own hinge position.
     */
    fun better(old: SwapFrame?, new: SwapFrame): SwapFrame = when {
        old == null -> new
        old.width != new.width || old.height != new.height || old.density != new.density -> new
        new.hinge.source == HingeSource.WINDOW_MANAGER -> new
        old.hinge.source == HingeSource.ESTIMATED -> new
        else -> old
    }
}
