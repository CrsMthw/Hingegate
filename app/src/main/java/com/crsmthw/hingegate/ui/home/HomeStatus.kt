package com.crsmthw.hingegate.ui.home

import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.domain.SwapFailure
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The one-line status under the app name. Decided here, worded by the screen from string resources. A crack is
 * only "showing" when the selected mode can show it ([surfaceReady]); otherwise the status says it is hidden and
 * points at the setup.
 */
enum class HomeStatus { OFF, LEARNING, FOLDED, UNFOLDED_CRACKED, UNFOLDED_CRACK_HIDDEN, UNFOLDED_CLEAN;

    companion object {
        fun of(settings: Settings, active: ActiveCrack?, posture: Posture, surfaceReady: Boolean): HomeStatus = when {
            !settings.enabled -> OFF
            posture == Posture.UNKNOWN -> LEARNING
            posture == Posture.FOLDED -> FOLDED
            active != null -> if (surfaceReady) UNFOLDED_CRACKED else UNFOLDED_CRACK_HIDDEN
            else -> UNFOLDED_CLEAN
        }

        /**
         * Whether the selected mode can draw a crack at all: Over everything needs "Display over other apps"; Live
         * wallpaper needs Hingegate set as the (inner screen's) wallpaper; Real wallpaper needs All files access
         * and a captured original.
         */
        fun surfaceReady(
            mode: CrackMode,
            canDrawOverlays: Boolean,
            wallpaperSet: Boolean,
            allFilesAccess: Boolean,
            originalCaptured: Boolean,
        ): Boolean = when (mode) {
            CrackMode.OVERLAY -> canDrawOverlays
            CrackMode.WALLPAPER -> wallpaperSet
            CrackMode.SWAP -> allFilesAccess && originalCaptured
        }
    }
}

/**
 * The Live wallpaper card's status. One UI keeps a wallpaper per panel and answers "which live wallpaper is set"
 * for the panel in use, while the crack only ever shows on the inner screen: so the answer is only worded as one
 * about the inner screen while unfolded; folded (or unknown) the card asks to unfold to check.
 */
enum class WallpaperStatus { INNER_SET, INNER_NOT_SET, UNFOLD_TO_CHECK;

    companion object {
        fun of(posture: Posture, wallpaperSet: Boolean): WallpaperStatus = when {
            posture != Posture.UNFOLDED -> UNFOLD_TO_CHECK
            wallpaperSet -> INNER_SET
            else -> INNER_NOT_SET
        }
    }
}

/** "Show crack now": only with the app switched on (nothing would draw otherwise) and while unfolded. */
enum class ShowNowAvailability { READY, NEEDS_SWITCH_ON, NEEDS_UNFOLD;

    companion object {
        fun of(enabled: Boolean, posture: Posture): ShowNowAvailability = when {
            !enabled -> NEEDS_SWITCH_ON
            posture != Posture.UNFOLDED -> NEEDS_UNFOLD
            else -> READY
        }
    }
}

/** A one-shot message for the snackbar. */
enum class HomeMessage {
    SAVE_FAILED, IMAGE_FAILED,
    CAPTURED, CAPTURE_LIVE_WALLPAPER, CAPTURE_NO_PERMISSION, CAPTURE_NOT_UNFOLDED, CAPTURE_ALREADY_OURS, CAPTURE_NO_SIZE,
    CAPTURE_FAILED, CAPTURE_BUILT_IN;

    companion object {
        /** What the snackbar says when "Use my current wallpaper" failed for [failure]. */
        fun forCapture(failure: SwapFailure?): HomeMessage = when (failure) {
            SwapFailure.LIVE_WALLPAPER -> CAPTURE_LIVE_WALLPAPER
            SwapFailure.NO_PERMISSION -> CAPTURE_NO_PERMISSION
            SwapFailure.NOT_UNFOLDED -> CAPTURE_NOT_UNFOLDED
            SwapFailure.ALREADY_OURS -> CAPTURE_ALREADY_OURS
            // Nothing was read yet: the size of the inner screen trails a rotation or a panel swap for a moment.
            SwapFailure.NO_PANEL_SIZE -> CAPTURE_NO_SIZE
            // The phone's own wallpaper has no file: retrying never helps, setting a still one does (SWAP-MODE §10.36).
            SwapFailure.BUILT_IN_WALLPAPER -> CAPTURE_BUILT_IN
            // The wallpaper moved under the read: nothing was kept; tapping again reads it afresh.
            SwapFailure.READ_FAILED, SwapFailure.SET_FAILED, SwapFailure.CHANGED_DURING_READ, null -> CAPTURE_FAILED
        }
    }
}

/**
 * The permissions the app asks for (docs/SWAP-MODE.md §8). Each is explained on its card and again in a
 * confirmation dialog before the app opens Settings or the system prompt.
 */
enum class PermissionKind { ALL_FILES, OVERLAY, NOTIFICATIONS }

/**
 * What the permission dialog says comes next (docs/SWAP-MODE.md §8): Android's own prompt, the app's own page in
 * Settings, or — for "Display over other apps" off Samsung — Settings' LIST of apps: AOSP (Android 11+) ignores the
 * app named in that request and opens the list, so the user has to find Hingegate in it (seen on the Pixel Fold
 * emulator; One UI opens Hingegate's own page, as worded since build 1). DESIGN §18.25.
 */
enum class PermissionNext { SYSTEM_PROMPT, SETTINGS_PAGE, SETTINGS_LIST;

    companion object {
        fun of(kind: PermissionKind, systemPrompt: Boolean, isSamsung: Boolean): PermissionNext = when {
            systemPrompt -> SYSTEM_PROMPT
            kind == PermissionKind.OVERLAY && !isSamsung -> SETTINGS_LIST
            else -> SETTINGS_PAGE
        }
    }
}

/**
 * Whether the Over everything card says how to hide Android's own "Hingegate is displaying over other apps"
 * notice, which AOSP posts in the shade for as long as the crack's window exists (DESIGN §18.24). Shown off Samsung
 * only: seen on the Pixel Fold emulator, not yet checked on One UI, whose card stays as it is.
 */
object OverlayNoticeTip {
    fun shows(isSamsung: Boolean): Boolean = !isSamsung
}

/** The Real wallpaper card's "Use my current wallpaper": possible only unfolded and with All files access. */
enum class CaptureAvailability { READY, NEEDS_PERMISSION, NEEDS_UNFOLD;

    companion object {
        fun of(allFilesAccess: Boolean, posture: Posture): CaptureAvailability = when {
            !allFilesAccess -> NEEDS_PERMISSION
            posture != Posture.UNFOLDED -> NEEDS_UNFOLD
            else -> READY
        }
    }
}

/** Log times: just the time for today's entries, the date as well for older ones. */
object LogTime {
    fun format(epochMs: Long, nowEpochMs: Long, zone: ZoneId, locale: Locale): String {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val formatter = if (time.toLocalDate() == today) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        }
        return formatter.withLocale(locale).format(time)
    }
}
