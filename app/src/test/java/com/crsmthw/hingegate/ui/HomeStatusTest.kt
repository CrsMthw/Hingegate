package com.crsmthw.hingegate.ui

import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.CrackCause
import com.crsmthw.hingegate.domain.CrackSpecGenerator
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.domain.SwapFailure
import com.crsmthw.hingegate.ui.home.CaptureAvailability
import com.crsmthw.hingegate.ui.home.HomeMessage
import com.crsmthw.hingegate.ui.home.HomeStatus
import com.crsmthw.hingegate.ui.home.LogTime
import com.crsmthw.hingegate.ui.home.OverlayNoticeTip
import com.crsmthw.hingegate.ui.home.PermissionKind
import com.crsmthw.hingegate.ui.home.PermissionNext
import com.crsmthw.hingegate.ui.home.ShowNowAvailability
import com.crsmthw.hingegate.ui.home.WallpaperStatus
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HomeStatusTest {

    private val crack = ActiveCrack(CrackSpecGenerator.generate(Random(1)), 0L, CrackCause.MANUAL)

    @Test
    fun `status for every state`() {
        assertEquals(HomeStatus.OFF, HomeStatus.of(Settings(enabled = false), crack, Posture.UNFOLDED, surfaceReady = true))
        assertEquals(HomeStatus.LEARNING, HomeStatus.of(Settings(), null, Posture.UNKNOWN, surfaceReady = true))
        assertEquals(HomeStatus.FOLDED, HomeStatus.of(Settings(), null, Posture.FOLDED, surfaceReady = true))
        assertEquals(HomeStatus.UNFOLDED_CRACKED, HomeStatus.of(Settings(), crack, Posture.UNFOLDED, surfaceReady = true))
        assertEquals(HomeStatus.UNFOLDED_CLEAN, HomeStatus.of(Settings(), null, Posture.UNFOLDED, surfaceReady = true))
    }

    @Test
    fun `a crack the mode cannot show is not called showing`() {
        assertEquals(HomeStatus.UNFOLDED_CRACK_HIDDEN, HomeStatus.of(Settings(), crack, Posture.UNFOLDED, surfaceReady = false))
        assertEquals(HomeStatus.UNFOLDED_CLEAN, HomeStatus.of(Settings(), null, Posture.UNFOLDED, surfaceReady = false))
        assertEquals(HomeStatus.FOLDED, HomeStatus.of(Settings(), null, Posture.FOLDED, surfaceReady = false))
    }

    @Test
    fun `each mode is ready only with its own setup done`() {
        fun ready(mode: CrackMode, overlays: Boolean = false, wallpaper: Boolean = false, files: Boolean = false, captured: Boolean = false) =
            HomeStatus.surfaceReady(mode, overlays, wallpaper, files, captured)
        assertFalse(ready(CrackMode.OVERLAY, wallpaper = true, files = true, captured = true))
        assertTrue(ready(CrackMode.OVERLAY, overlays = true))
        assertFalse(ready(CrackMode.WALLPAPER, overlays = true, files = true, captured = true))
        assertTrue(ready(CrackMode.WALLPAPER, wallpaper = true))
        assertFalse(ready(CrackMode.SWAP, overlays = true, wallpaper = true))
        assertFalse(ready(CrackMode.SWAP, files = true))
        assertFalse(ready(CrackMode.SWAP, captured = true))
        assertTrue(ready(CrackMode.SWAP, files = true, captured = true))
    }

    @Test
    fun `the live wallpaper status speaks only about the inner screen`() {
        assertEquals(WallpaperStatus.INNER_SET, WallpaperStatus.of(Posture.UNFOLDED, wallpaperSet = true))
        assertEquals(WallpaperStatus.INNER_NOT_SET, WallpaperStatus.of(Posture.UNFOLDED, wallpaperSet = false))
        // Folded, One UI answers for the cover screen: no claim either way.
        assertEquals(WallpaperStatus.UNFOLD_TO_CHECK, WallpaperStatus.of(Posture.FOLDED, wallpaperSet = true))
        assertEquals(WallpaperStatus.UNFOLD_TO_CHECK, WallpaperStatus.of(Posture.FOLDED, wallpaperSet = false))
        assertEquals(WallpaperStatus.UNFOLD_TO_CHECK, WallpaperStatus.of(Posture.UNKNOWN, wallpaperSet = true))
    }

    @Test
    fun `show crack now needs the app on and the phone unfolded`() {
        assertEquals(ShowNowAvailability.READY, ShowNowAvailability.of(enabled = true, posture = Posture.UNFOLDED))
        assertEquals(ShowNowAvailability.NEEDS_SWITCH_ON, ShowNowAvailability.of(enabled = false, posture = Posture.UNFOLDED))
        assertEquals(ShowNowAvailability.NEEDS_SWITCH_ON, ShowNowAvailability.of(enabled = false, posture = Posture.FOLDED))
        assertEquals(ShowNowAvailability.NEEDS_UNFOLD, ShowNowAvailability.of(enabled = true, posture = Posture.FOLDED))
        assertEquals(ShowNowAvailability.NEEDS_UNFOLD, ShowNowAvailability.of(enabled = true, posture = Posture.UNKNOWN))
    }

    @Test
    fun `every capture failure has its message`() {
        assertEquals(HomeMessage.CAPTURE_LIVE_WALLPAPER, HomeMessage.forCapture(SwapFailure.LIVE_WALLPAPER))
        assertEquals(HomeMessage.CAPTURE_NO_PERMISSION, HomeMessage.forCapture(SwapFailure.NO_PERMISSION))
        assertEquals(HomeMessage.CAPTURE_NOT_UNFOLDED, HomeMessage.forCapture(SwapFailure.NOT_UNFOLDED))
        assertEquals(HomeMessage.CAPTURE_ALREADY_OURS, HomeMessage.forCapture(SwapFailure.ALREADY_OURS))
        assertEquals(HomeMessage.CAPTURE_NO_SIZE, HomeMessage.forCapture(SwapFailure.NO_PANEL_SIZE))
        assertEquals(HomeMessage.CAPTURE_FAILED, HomeMessage.forCapture(SwapFailure.READ_FAILED))
        assertEquals(HomeMessage.CAPTURE_FAILED, HomeMessage.forCapture(SwapFailure.SET_FAILED))
        assertEquals(HomeMessage.CAPTURE_FAILED, HomeMessage.forCapture(SwapFailure.CHANGED_DURING_READ))
        assertEquals(HomeMessage.CAPTURE_FAILED, HomeMessage.forCapture(null))
        // The Pixel's shipped wallpaper (id 0, no file): what to do, not "could not read".
        assertEquals(HomeMessage.CAPTURE_BUILT_IN, HomeMessage.forCapture(SwapFailure.BUILT_IN_WALLPAPER))
        SwapFailure.entries.forEach { HomeMessage.forCapture(it) }
    }

    @Test
    fun `the permission dialog says what comes next`() {
        // Android's own prompt (notifications on 13+), whatever the phone.
        assertEquals(PermissionNext.SYSTEM_PROMPT, PermissionNext.of(PermissionKind.NOTIFICATIONS, systemPrompt = true, isSamsung = true))
        assertEquals(PermissionNext.SYSTEM_PROMPT, PermissionNext.of(PermissionKind.NOTIFICATIONS, systemPrompt = true, isSamsung = false))
        // Display over other apps: Hingegate's own page on Samsung, AOSP's list of apps elsewhere (lane A, A4).
        assertEquals(PermissionNext.SETTINGS_PAGE, PermissionNext.of(PermissionKind.OVERLAY, systemPrompt = false, isSamsung = true))
        assertEquals(PermissionNext.SETTINGS_LIST, PermissionNext.of(PermissionKind.OVERLAY, systemPrompt = false, isSamsung = false))
        // All files access opens Hingegate's own page on both (lane B, B5); so do notification settings.
        PermissionKind.entries.filter { it != PermissionKind.OVERLAY }.forEach { kind ->
            listOf(true, false).forEach { samsung ->
                assertEquals(PermissionNext.SETTINGS_PAGE, PermissionNext.of(kind, systemPrompt = false, isSamsung = samsung), "$kind $samsung")
            }
        }
    }

    @Test
    fun `the over everything card explains the system notice off samsung only`() {
        assertTrue(OverlayNoticeTip.shows(isSamsung = false))
        assertFalse(OverlayNoticeTip.shows(isSamsung = true))
    }

    @Test
    fun `the wallpaper can be captured only unfolded and with all files access`() {
        assertEquals(CaptureAvailability.READY, CaptureAvailability.of(allFilesAccess = true, posture = Posture.UNFOLDED))
        assertEquals(CaptureAvailability.NEEDS_UNFOLD, CaptureAvailability.of(allFilesAccess = true, posture = Posture.FOLDED))
        assertEquals(CaptureAvailability.NEEDS_UNFOLD, CaptureAvailability.of(allFilesAccess = true, posture = Posture.UNKNOWN))
        assertEquals(CaptureAvailability.NEEDS_PERMISSION, CaptureAvailability.of(allFilesAccess = false, posture = Posture.UNFOLDED))
    }

    @Test
    fun `log time shows the date only for older entries`() {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.of(2026, 10, 31, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val earlierToday = LocalDateTime.of(2026, 10, 31, 9, 5).atZone(zone).toInstant().toEpochMilli()
        val yesterday = LocalDateTime.of(2026, 10, 30, 9, 5).atZone(zone).toInstant().toEpochMilli()
        val today = LogTime.format(earlierToday, now, zone, Locale.UK)
        val older = LogTime.format(yesterday, now, zone, Locale.UK)
        assertEquals("09:05", today)
        assertNotEquals(today, older)
        assertEquals(true, older.contains("30"))
    }
}
