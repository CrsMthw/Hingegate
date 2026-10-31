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
}
