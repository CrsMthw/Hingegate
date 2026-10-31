package com.crsmthw.hingegate.domain

import kotlinx.serialization.Serializable

@Serializable
enum class LogKind {
    UNFOLD_HIT, UNFOLD_MISS, UNFOLD_DISABLED, FOLD_CLEARED, FOLD, MANUAL_SHOW, MANUAL_CLEAR,

    // The Real wallpaper mode (docs/SWAP-MODE.md §6): every set, capture and failure, with the flags passed and
    // the id the system returned, so a checklist can prove how the phone handles them.
    SWAP_STAGED, SWAP_SET, SWAP_SET_LATE, SWAP_RESTORED, SWAP_CAPTURED, SWAP_RECAPTURED, SWAP_ID_ADOPTED,
    SWAP_STAGE_MISSED, SWAP_FAILED,
}

/** Why the Real wallpaper mode could not capture or set a wallpaper. */
@Serializable
enum class SwapFailure {
    /** A live wallpaper is set: there is no image to read. */
    LIVE_WALLPAPER,

    /** All files access is not granted. */
    NO_PERMISSION,

    /** The inner screen is not the one in use (the current wallpaper would be the cover screen's). */
    NOT_UNFOLDED,

    /** The current wallpaper is already one Hingegate set. */
    ALREADY_OURS,

    /** The inner panel's size is not known yet. */
    NO_PANEL_SIZE,

    /** The wallpaper file could not be read or decoded. */
    READ_FAILED,

    /** The system refused the new wallpaper. */
    SET_FAILED,

    /**
     * The wallpaper's id moved while it was being read (or the posture changed under the read): the copy is thrown
     * away and the wallpaper read again; nothing is lost.
     */
    CHANGED_DURING_READ,
}

/**
 * One line of the detection log (docs/DESIGN.md §9.3). Plain folds are logged too, so detection can be checked
 * on a phone where cracks are rare.
 *
 * [missStreak]: for a miss, the clean unfolds in a row INCLUDING this one; for a guaranteed hit, the clean
 * unfolds that earned it. The `swap*` fields belong to the Real wallpaper mode's entries: whether the image set
 * was the cracked one, the `which` flags passed, the id the system returned, and why something failed.
 */
@Serializable
data class LogEntry(
    val timeEpochMs: Long,
    val kind: LogKind,
    val reason: HitReason? = null,
    val roll: Int? = null,
    val chancePercent: Int? = null,
    val missStreak: Int? = null,
    val authority: PostureAuthority = PostureAuthority.NONE,
    val hingeSource: HingeSource? = null,
    val swapCracked: Boolean? = null,
    val swapWhich: Int? = null,
    val swapId: Int? = null,
    val swapFailure: SwapFailure? = null,
) {
    companion object {
        /** Newest first, at most this many. */
        const val CAPACITY = 40
    }
}

/** The sentences a Real wallpaper set can read as; the UI maps each to a string resource. */
enum class SwapSetWording { STAGED_CRACKED, STAGED_CLEAN, SET_CRACKED, SET_CLEAN, LATE_CRACKED, RESTORED }

/**
 * What a log line should say, decided without string resources so it can be unit-tested; the UI turns each
 * phrase into a localised sentence.
 */
sealed interface LogPhrase {
    data class CrackedRolled(val roll: Int, val chancePercent: Int) : LogPhrase
    data class CrackedGuaranteed(val afterClean: Int) : LogPhrase
    data object CrackedForced : LogPhrase
    data class Clean(val roll: Int, val inARow: Int) : LogPhrase
    data object UnfoldedWhileOff : LogPhrase
    data object FoldCleared : LogPhrase
    data object Folded : LogPhrase
    data object ShownByHand : LogPhrase
    data object ClearedByHand : LogPhrase

    /** A Real wallpaper set: when it happened, which image, the flags passed and the id that came back. */
    data class SwapSet(val timing: SwapTiming, val cracked: Boolean, val which: Int, val id: Int) : LogPhrase {
        /**
         * The sentence it reads as. Only an unfold's crack can be late; a clean copy set while unfolded (after
         * "Clear crack", or a late set of a clean outcome) is an ordinary set.
         */
        val wording: SwapSetWording
            get() = when (timing) {
                SwapTiming.AHEAD -> if (cracked) SwapSetWording.STAGED_CRACKED else SwapSetWording.STAGED_CLEAN
                SwapTiming.ON_TIME -> if (cracked) SwapSetWording.SET_CRACKED else SwapSetWording.SET_CLEAN
                SwapTiming.LATE -> if (cracked) SwapSetWording.LATE_CRACKED else SwapSetWording.SET_CLEAN
                SwapTiming.RESTORE -> SwapSetWording.RESTORED
            }
    }
    data class SwapCaptured(val id: Int) : LogPhrase
    data class SwapRecaptured(val id: Int) : LogPhrase
    data class SwapIdAdopted(val id: Int) : LogPhrase
    data object SwapStageMissed : LogPhrase
    data class SwapFailed(val failure: SwapFailure) : LogPhrase

    companion object {
        fun of(entry: LogEntry): LogPhrase = when (entry.kind) {
            LogKind.UNFOLD_HIT -> when (entry.reason) {
                HitReason.GUARANTEED -> CrackedGuaranteed(entry.missStreak ?: 0)
                HitReason.ROLLED -> CrackedRolled(entry.roll ?: 0, entry.chancePercent ?: 0)
                else -> CrackedForced
            }
            LogKind.UNFOLD_MISS -> Clean(entry.roll ?: 0, entry.missStreak ?: 0)
            LogKind.UNFOLD_DISABLED -> UnfoldedWhileOff
            LogKind.FOLD_CLEARED -> FoldCleared
            LogKind.FOLD -> Folded
            LogKind.MANUAL_SHOW -> ShownByHand
            LogKind.MANUAL_CLEAR -> ClearedByHand
            LogKind.SWAP_STAGED -> swapSet(entry, SwapTiming.AHEAD)
            LogKind.SWAP_SET -> swapSet(entry, SwapTiming.ON_TIME)
            LogKind.SWAP_SET_LATE -> swapSet(entry, SwapTiming.LATE)
            LogKind.SWAP_RESTORED -> swapSet(entry, SwapTiming.RESTORE)
            LogKind.SWAP_CAPTURED -> SwapCaptured(entry.swapId ?: 0)
            LogKind.SWAP_RECAPTURED -> SwapRecaptured(entry.swapId ?: 0)
            LogKind.SWAP_ID_ADOPTED -> SwapIdAdopted(entry.swapId ?: 0)
            LogKind.SWAP_STAGE_MISSED -> SwapStageMissed
            LogKind.SWAP_FAILED -> SwapFailed(entry.swapFailure ?: SwapFailure.SET_FAILED)
        }

        private fun swapSet(entry: LogEntry, timing: SwapTiming) =
            SwapSet(timing, entry.swapCracked ?: false, entry.swapWhich ?: 0, entry.swapId ?: 0)

        /** The log kind for a set made with [timing]. */
        fun kindOf(timing: SwapTiming): LogKind = when (timing) {
            SwapTiming.AHEAD -> LogKind.SWAP_STAGED
            SwapTiming.ON_TIME -> LogKind.SWAP_SET
            SwapTiming.LATE -> LogKind.SWAP_SET_LATE
            SwapTiming.RESTORE -> LogKind.SWAP_RESTORED
        }
    }
}
