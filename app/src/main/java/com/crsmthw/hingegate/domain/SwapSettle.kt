package com.crsmthw.hingegate.domain

/**
 * The posture as the Real wallpaper mode's settle gate sees it: the controller's posture and transition count
 * ([epoch], so a fold and unfold in quick succession is a change even if both ends look alike) and the display's
 * posture, which changes a moment before the controller acts on it.
 */
data class SettleKey(val controller: Posture, val epoch: Long, val display: Posture) {
    /** Both the controller and the display say the inner screen is the one in use. */
    val innerInUse: Boolean
        get() = controller == Posture.UNFOLDED && display == Posture.UNFOLDED
}

/** [key] has held since [sinceMs] (monotonic clock). */
data class SettleMark(val key: SettleKey, val sinceMs: Long)

/**
 * When the Real wallpaper mode may act on what it reads about the wallpaper (docs/SWAP-MODE.md §10.24): only once
 * the inner screen has been in use, with no posture change at all, for [SETTLE_MS]. Every decision taken from a
 * read — confirming or adopting our copy, noticing a missed stage, recapturing a changed wallpaper, capturing,
 * letting go after a failed read — waits for it; a trigger that arrives earlier asks for one check at the end of the
 * settle period instead of reading now.
 *
 * Why 1.5 s: on Samsung the wallpaper server's own fold state (`SubDisplayMode.mLidState`) and the app's display
 * callbacks are fed by two separate device-state listeners, and the logical display swap the app sees can be held
 * back up to 3 s after the device state commits (SWAP-MODE §1). That hold-back comes BEFORE the app's own posture
 * change, so it is not part of the window after it; what is left after the app's change is the other listener's
 * handler hop (milliseconds), the activity being recreated on the other panel, Window Manager's first layout and
 * the logical size catching up — all well inside a second on the Fold 6 and Fold 8. 1.5 s covers that with
 * margin and is still shorter than a person takes to look at the inner screen and reach for anything: no
 * read-based decision is needed sooner (the unfold's own image was staged while folded; sets are not delayed).
 */
object SwapSettle {

    /** How long the inner screen must have been in use, with no posture change, before a read counts. */
    const val SETTLE_MS = 1_500L

    /**
     * How far apart two settled reads of an unknown id must be to confirm the user changed the wallpaper (and the
     * delay before re-trying a recapture that failed for a final-looking reason). A few hundred ms: long enough
     * that a value read during some passing server state is not read twice, short enough to feel immediate.
     */
    const val CONFIRM_MS = 500L

    /** The mark after observing [key] at [nowMs]: kept while the key is the same, restarted when it changes. */
    fun observe(mark: SettleMark?, key: SettleKey, nowMs: Long): SettleMark =
        if (mark != null && mark.key == key) mark else SettleMark(key, nowMs)

    /** Whether a read-based decision may be taken now. */
    fun settled(mark: SettleMark, nowMs: Long): Boolean = mark.key.innerInUse && nowMs - mark.sinceMs >= SETTLE_MS

    /**
     * How long until [mark] is settled (0 when it is), or null while the inner screen is not in use: then nothing
     * is scheduled, and the next unfold asks again.
     */
    fun remainingMs(mark: SettleMark, nowMs: Long): Long? =
        if (!mark.key.innerInUse) null else (SETTLE_MS - (nowMs - mark.sinceMs)).coerceAtLeast(0L)
}
