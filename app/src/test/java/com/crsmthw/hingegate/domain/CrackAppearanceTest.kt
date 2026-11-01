package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrackAppearanceTest {

    @Test
    fun `flicker keyframes`() {
        assertEquals(1f, CrackAppearance.alpha(0))
        assertEquals(1f, CrackAppearance.alpha(59))
        assertEquals(0.15f, CrackAppearance.alpha(60))
        assertEquals(0.15f, CrackAppearance.alpha(119))
        assertEquals(1f, CrackAppearance.alpha(120))
        assertEquals(1f, CrackAppearance.alpha(199))
        assertEquals(0.35f, CrackAppearance.alpha(200))
        assertEquals(0.35f, CrackAppearance.alpha(239))
        assertEquals(0.35f, CrackAppearance.alpha(240), 1e-6f)
        assertEquals(0.675f, CrackAppearance.alpha(380), 1e-3f)
        assertEquals(1f, CrackAppearance.alpha(CrackAppearance.DURATION_MS))
        assertEquals(1f, CrackAppearance.alpha(60_000))
    }

    @Test
    fun `fade is monotonic`() {
        var previous = CrackAppearance.alpha(240)
        for (t in 241L until 520L) {
            val a = CrackAppearance.alpha(t)
            assertTrue(a >= previous)
            previous = a
        }
    }

    @Test
    fun `animating only inside the duration`() {
        assertTrue(CrackAppearance.isAnimating(1_000, 1_000))
        assertTrue(CrackAppearance.isAnimating(1_000, 1_519))
        assertFalse(CrackAppearance.isAnimating(1_000, 1_520))
        assertFalse(CrackAppearance.isAnimating(1_000, 999))
    }

    @Test
    fun `the overlay's window alpha is compensated up to full opacity`() {
        val scale = AlphaScale.forWindow(0.8f)
        assertEquals(1.25f, scale, 1e-4f)
        // An element meant at 0.7 is drawn at 0.875, which the 0.8 window turns back into 0.7.
        assertEquals(0.7f, AlphaScale.apply(0.7f, scale) * 0.8f, 1e-4f)
        // Near-opaque elements stop at the window's ceiling.
        assertEquals(1f, AlphaScale.apply(0.95f, scale))
        // The flicker's dim phase keeps its proportion.
        assertEquals(0.15f, AlphaScale.apply(0.15f, scale) * 0.8f, 1e-4f)
    }

    @Test
    fun `an opaque or invalid window alpha needs no compensation`() {
        assertEquals(1f, AlphaScale.forWindow(1f))
        assertEquals(1f, AlphaScale.forWindow(0f))
        assertEquals(1f, AlphaScale.forWindow(-0.5f))
        assertEquals(0.5f, AlphaScale.apply(0.5f, 1f))
        assertEquals(0f, AlphaScale.apply(-1f, 1f))
    }
}
