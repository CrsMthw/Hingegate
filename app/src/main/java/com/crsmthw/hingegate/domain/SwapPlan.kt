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
) {
    val hasOriginal: Boolean
        get() = capture != null && frame != null && !needsRecapture

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

    /** "Put my wallpaper back" is due once the crack it clears is gone (the clear runs first). */
    fun restoreDue(restoreRequested: Boolean, active: ActiveCrack?): Boolean = restoreRequested && active == null

    /** The timing a set is logged with: a clean set right after "Put my wallpaper back" reads as the put-back. */
    fun timingFor(action: SwapAction.Apply, restoreDue: Boolean): SwapTiming =
        if (restoreDue && action.target == Staged.Clean) SwapTiming.RESTORE else action.timing
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

    /** The user changed the wallpaper and the inner screen is in use: read the new one. */
    RECAPTURE,

    /** The user changed the wallpaper, but it cannot be read now (or the mode is off): stop claiming the screen. */
    FORGET,
}

/** [action], and whether a set of ours was found not to have reached the inner screen (the log's proof line). */
data class IdDecision(val action: IdAction, val missed: Boolean = false)

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
     * Whether the system's id means the INNER screen's wallpaper right now: while the inner screen is in use; on
     * phones with one wallpaper for both panels also while folded (on Samsung the folded answer is the cover's).
     */
    fun readable(innerInUse: Boolean, isSamsung: Boolean, controllerPosture: Posture): Boolean =
        innerInUse || (!isSamsung && controllerPosture == Posture.FOLDED)

    /** Whether the id is worth reading: there is a capture, the mode is on or something of ours is staged, and it is [readable]. */
    fun shouldCheck(state: SwapState, swapOn: Boolean, readable: Boolean): Boolean =
        state.capture != null && (swapOn || state.staged != null) && readable

    /** What to do about the inner screen's current [id] (read only when [shouldCheck] said so). */
    fun decide(id: Int, state: SwapState, swapOn: Boolean, innerInUse: Boolean): IdDecision = when (check(id, state)) {
        IdCheck.NONE -> IdDecision(IdAction.KEEP)
        IdCheck.OURS -> IdDecision(
            if (state.unconfirmedSet || state.needsRecapture || (state.staged == null && state.lastSet != null)) {
                IdAction.CONFIRM
            } else {
                IdAction.KEEP
            },
        )
        IdCheck.ORIGINAL -> IdDecision(
            action = if (state.staged != null || state.unconfirmedSet || state.needsRecapture) IdAction.RESET else IdAction.KEEP,
            // A set of ours was waiting for confirmation and the original shows instead: it never got there.
            missed = state.staged != null && state.unconfirmedSet,
        )
        IdCheck.ADOPT -> IdDecision(IdAction.ADOPT)
        IdCheck.CHANGED -> IdDecision(if (swapOn && innerInUse) IdAction.RECAPTURE else IdAction.FORGET)
    }

    /** The state after [action] for [id]; [IdAction.RECAPTURE] is the swapper's (it reads the wallpaper). */
    fun settle(action: IdAction, state: SwapState, id: Int): SwapState = when (action) {
        IdAction.KEEP, IdAction.RECAPTURE -> state
        IdAction.CONFIRM -> state.copy(staged = state.staged ?: state.lastSet, unconfirmedSet = false, needsRecapture = false)
        IdAction.RESET -> state.copy(staged = null, unconfirmedSet = false, needsRecapture = false)
        IdAction.ADOPT -> state.copy(ourIds = remember(state.ourIds, id), unconfirmedSet = false)
        IdAction.FORGET -> state.copy(staged = null, unconfirmedSet = false)
    }

    /**
     * Whether the id read back right after a set names the new image: it can be read for the inner screen before
     * ([before]) and after ([after]) the set, it changed, and it is not the captured original's. Anything else
     * leaves the set unconfirmed, for the next check to settle (an id that never changed, or the original's,
     * would otherwise be remembered as ours and the original taken for Hingegate's copy).
     */
    fun confirmsSet(before: Int?, after: Int?, captureId: Int?): Boolean =
        before != null && after != null && after > 0 && after != before && after != captureId

    /**
     * Whether a set stays unconfirmed: on Samsung always (the reported id may still change to the per-panel one,
     * docs/SWAP-MODE.md §10.4), elsewhere only when the read-back did not confirm it.
     */
    fun unconfirmedAfterSet(isSamsung: Boolean, confirmed: Boolean): Boolean = isSamsung || !confirmed

    /** [ids] added to [ourIds] (no duplicates), keeping the newest [SwapState.OUR_IDS_CAPACITY]. */
    fun remember(ourIds: List<Int>, vararg ids: Int?): List<Int> {
        val added = ids.filterNotNull().filter { it > 0 }
        return (ourIds.filter { it !in added } + added.distinct()).takeLast(SwapState.OUR_IDS_CAPACITY)
    }
}

/** The `which` flags for `WallpaperManager.setBitmap` (docs/SWAP-MODE.md §1, §3). */
object SwapFlags {

    /**
     * Samsung's `WallpaperManager.FLAG_DISPLAY_PHONE` (the inner, "main" panel), read from the Fold 8's
     * framework.jar (docs/SWAP-MODE.md §1; `FLAG_DISPLAY_SUB` = 16 is the cover, `FLAG_MODE_MASK` = 60). Without a
     * panel bit Samsung picks the panel in use, so this bit is what lets the inner wallpaper be set while folded.
     */
    const val FLAG_DISPLAY_PHONE = 4

    fun isSamsung(manufacturer: String): Boolean = manufacturer.equals(SAMSUNG, ignoreCase = true)

    /** [flagSystem] (`WallpaperManager.FLAG_SYSTEM`), plus the inner-panel bit on Samsung. */
    fun which(isSamsung: Boolean, flagSystem: Int): Int = if (isSamsung) flagSystem or FLAG_DISPLAY_PHONE else flagSystem

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
