package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SwapSettleTest {

    private val unfolded = SettleKey(Posture.UNFOLDED, epoch = 4, display = Posture.UNFOLDED)

    @Test
    fun `the inner screen is in use only when the controller and the display agree`() {
        assertTrue(unfolded.innerInUse)
        assertFalse(unfolded.copy(controller = Posture.FOLDED).innerInUse)
        // The display swapped a moment before the controller acted on it, either way round.
        assertFalse(unfolded.copy(display = Posture.FOLDED).innerInUse)
        assertFalse(unfolded.copy(controller = Posture.UNKNOWN).innerInUse)
    }

    @Test
    fun `settled only after the settle period with no change`() {
        val mark = SwapSettle.observe(null, unfolded, 1_000)
        assertFalse(SwapSettle.settled(mark, 1_000))
        assertFalse(SwapSettle.settled(mark, 1_000 + SwapSettle.SETTLE_MS - 1))
        assertTrue(SwapSettle.settled(mark, 1_000 + SwapSettle.SETTLE_MS))
    }

    @Test
    fun `the same key keeps its mark, any change restarts it`() {
        val mark = SwapSettle.observe(null, unfolded, 1_000)
        assertSame(mark, SwapSettle.observe(mark, unfolded, 5_000))
        val display = SwapSettle.observe(mark, unfolded.copy(display = Posture.FOLDED), 5_000)
        assertEquals(5_000, display.sinceMs)
        // A fold and unfold in quick succession: both ends unfolded, but another epoch, so not the same posture.
        val again = SwapSettle.observe(mark, unfolded.copy(epoch = 6), 5_000)
        assertNotSame(mark, again)
        assertFalse(SwapSettle.settled(again, 5_000 + SwapSettle.SETTLE_MS - 1))
    }

    @Test
    fun `never settled while folded, however long`() {
        val folded = SwapSettle.observe(null, SettleKey(Posture.FOLDED, 3, Posture.FOLDED), 0)
        assertFalse(SwapSettle.settled(folded, 1_000_000))
        assertNull(SwapSettle.remainingMs(folded, 1_000_000))
        val half = SwapSettle.observe(null, unfolded.copy(controller = Posture.FOLDED), 0)
        assertFalse(SwapSettle.settled(half, 1_000_000))
        assertNull(SwapSettle.remainingMs(half, 1_000_000))
    }

    @Test
    fun `the remaining time counts down to zero`() {
        val mark = SwapSettle.observe(null, unfolded, 1_000)
        assertEquals(SwapSettle.SETTLE_MS, SwapSettle.remainingMs(mark, 1_000))
        assertEquals(SwapSettle.SETTLE_MS - 400, SwapSettle.remainingMs(mark, 1_400))
        assertEquals(0L, SwapSettle.remainingMs(mark, 1_000 + SwapSettle.SETTLE_MS))
        assertEquals(0L, SwapSettle.remainingMs(mark, 100_000))
    }

    @Test
    fun `the confirmation gap is a few hundred ms and shorter than the settle period`() {
        assertTrue(SwapSettle.CONFIRM_MS in 200L..900L)
        assertTrue(SwapSettle.CONFIRM_MS < SwapSettle.SETTLE_MS)
    }
}
