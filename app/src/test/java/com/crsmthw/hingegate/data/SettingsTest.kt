package com.crsmthw.hingegate.data

import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.CrackCause
import com.crsmthw.hingegate.domain.CrackSpecGenerator
import com.crsmthw.hingegate.domain.Posture
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {

    private val crack = ActiveCrack(CrackSpecGenerator.generate(Random(1)), 0L, CrackCause.UNFOLD)

    @Test
    fun `defaults`() {
        val s = Settings()
        assertTrue(s.enabled)
        assertEquals(CrackMode.OVERLAY, s.mode)
        assertEquals(35, s.chancePercent)
        assertEquals(4, s.guaranteeAfter)
        assertFalse(s.crackNextUnfold)
    }

    @Test
    fun `chance snaps to five percent steps inside its range`() {
        assertEquals(5, Settings.clampChance(0))
        assertEquals(5, Settings.clampChance(-20))
        assertEquals(35, Settings.clampChance(37))
        assertEquals(40, Settings.clampChance(38))
        assertEquals(100, Settings.clampChance(250))
    }

    @Test
    fun `guarantee is clamped to 0 to 10`() {
        assertEquals(0, Settings.clampGuarantee(-1))
        assertEquals(10, Settings.clampGuarantee(11))
        assertEquals(6, Settings.clampGuarantee(6))
    }

    @Test
    fun `the service runs in overlay mode with the overlay permission`() {
        assertTrue(SurfaceRules.serviceShouldRun(Settings(), canDrawOverlays = true, allFilesAccess = false))
        assertFalse(SurfaceRules.serviceShouldRun(Settings(), canDrawOverlays = false, allFilesAccess = true))
        assertFalse(SurfaceRules.serviceShouldRun(Settings(enabled = false), canDrawOverlays = true, allFilesAccess = true))
    }

    @Test
    fun `the service runs in the real wallpaper mode with all files access`() {
        val swap = Settings(mode = CrackMode.SWAP)
        assertTrue(SurfaceRules.serviceShouldRun(swap, canDrawOverlays = false, allFilesAccess = true))
        assertFalse(SurfaceRules.serviceShouldRun(swap, canDrawOverlays = true, allFilesAccess = false))
        assertFalse(SurfaceRules.serviceShouldRun(swap.copy(enabled = false), canDrawOverlays = true, allFilesAccess = true))
    }

    @Test
    fun `the live wallpaper mode never needs the service`() {
        val wallpaper = Settings(mode = CrackMode.WALLPAPER)
        assertFalse(SurfaceRules.serviceShouldRun(wallpaper, canDrawOverlays = true, allFilesAccess = true))
    }

    @Test
    fun `a surface draws the crack only for its own mode while unfolded`() {
        val s = Settings()
        assertTrue(SurfaceRules.drawsCrack(CrackMode.OVERLAY, s, crack, Posture.UNFOLDED))
        assertFalse(SurfaceRules.drawsCrack(CrackMode.WALLPAPER, s, crack, Posture.UNFOLDED))
        assertFalse(SurfaceRules.drawsCrack(CrackMode.OVERLAY, s.copy(mode = CrackMode.SWAP), crack, Posture.UNFOLDED))
        assertFalse(SurfaceRules.drawsCrack(CrackMode.OVERLAY, s, crack, Posture.FOLDED))
        assertFalse(SurfaceRules.drawsCrack(CrackMode.OVERLAY, s, crack, Posture.UNKNOWN))
        assertFalse(SurfaceRules.drawsCrack(CrackMode.OVERLAY, s, null, Posture.UNFOLDED))
        assertFalse(SurfaceRules.drawsCrack(CrackMode.OVERLAY, s.copy(enabled = false), crack, Posture.UNFOLDED))
    }
}
