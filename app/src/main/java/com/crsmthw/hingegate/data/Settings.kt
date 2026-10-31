package com.crsmthw.hingegate.data

import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.Posture

/**
 * Where the crack is drawn: over every app (overlay window), on Hingegate's own live wallpaper, or baked into a
 * copy of the user's own static wallpaper that is swapped in and out (docs/SWAP-MODE.md).
 */
enum class CrackMode { OVERLAY, WALLPAPER, SWAP }

/** The user's choices. Defaults are what a fresh install shows. */
data class Settings(
    val enabled: Boolean = true,
    val mode: CrackMode = CrackMode.OVERLAY,
    val chancePercent: Int = CHANCE_DEFAULT,
    val guaranteeAfter: Int = GUARANTEE_DEFAULT,
    val crackNextUnfold: Boolean = false,
) {
    companion object {
        const val CHANCE_MIN = 5
        const val CHANCE_MAX = 100
        const val CHANCE_STEP = 5
        const val CHANCE_DEFAULT = 35
        const val GUARANTEE_MIN = 0
        const val GUARANTEE_MAX = 10
        const val GUARANTEE_DEFAULT = 4

        /** Snaps to the slider's 5 % steps inside 5..100, whatever was stored or dragged. */
        fun clampChance(percent: Int): Int {
            val bounded = percent.coerceIn(CHANCE_MIN, CHANCE_MAX)
            val snapped = ((bounded + CHANCE_STEP / 2) / CHANCE_STEP) * CHANCE_STEP
            return snapped.coerceIn(CHANCE_MIN, CHANCE_MAX)
        }

        fun clampGuarantee(count: Int): Int = count.coerceIn(GUARANTEE_MIN, GUARANTEE_MAX)
    }
}

/** Pure decisions for the display surfaces, shared by the foreground service, the wallpaper engine and the UI. */
object SurfaceRules {

    /**
     * The foreground service should run at all (docs/SWAP-MODE.md §4): switched on, and either the overlay mode
     * with "Display over other apps" or the Real wallpaper mode with All files access. In the Real wallpaper mode
     * it only keeps the process alive; nothing else is bound to it there.
     */
    fun serviceShouldRun(settings: Settings, canDrawOverlays: Boolean, allFilesAccess: Boolean): Boolean =
        settings.enabled && when (settings.mode) {
            CrackMode.OVERLAY -> canDrawOverlays
            CrackMode.SWAP -> allFilesAccess
            CrackMode.WALLPAPER -> false
        }

    /** A surface in [mode] draws the crack: its mode is selected, the app is on, a crack is active, unfolded. */
    fun drawsCrack(mode: CrackMode, settings: Settings, active: ActiveCrack?, posture: Posture): Boolean =
        settings.enabled && settings.mode == mode && active != null && posture == Posture.UNFOLDED
}
