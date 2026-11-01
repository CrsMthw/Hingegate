package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.test.assertEquals

class HingeInferenceTest {

    private fun aspect(w: Int, h: Int) = maxOf(w, h).toFloat() / minOf(w, h)

    private fun assertHinge(expected: HingeOrientation, position: Float, actual: HingeLine) {
        assertEquals(expected, actual.orientation)
        assertEquals(position, actual.positionPx, 0.01f)
        assertEquals(0f, actual.thicknessPx)
        assertEquals(HingeSource.ESTIMATED, actual.source)
    }

    @Test
    fun `fold 8 with its cover`() =
        assertHinge(HingeOrientation.VERTICAL, 1224f, HingeInference.estimate(2448, 1848, aspect(1248, 1972)))

    @Test
    fun `fold 8 rotated with its cover`() =
        assertHinge(HingeOrientation.HORIZONTAL, 1224f, HingeInference.estimate(1848, 2448, aspect(1248, 1972)))

    @Test
    fun `fold 6 with its cover`() =
        assertHinge(HingeOrientation.VERTICAL, 928f, HingeInference.estimate(1856, 2160, aspect(968, 2376)))

    @Test
    fun `fold 6 rotated with its cover`() =
        assertHinge(HingeOrientation.HORIZONTAL, 928f, HingeInference.estimate(2160, 1856, aspect(968, 2376)))

    @Test
    fun `flip with its cover`() =
        assertHinge(HingeOrientation.HORIZONTAL, 1320f, HingeInference.estimate(1080, 2640, aspect(720, 748)))

    @Test
    fun `fold 8 without cover info`() =
        assertHinge(HingeOrientation.VERTICAL, 1224f, HingeInference.estimate(2448, 1848, null))

    @Test
    fun `fold 6 without cover info`() =
        assertHinge(HingeOrientation.VERTICAL, 928f, HingeInference.estimate(1856, 2160, null))

    @Test
    fun `flip without cover info`() =
        assertHinge(HingeOrientation.HORIZONTAL, 1320f, HingeInference.estimate(1080, 2640, null))

    @Test
    fun `flip held sideways without cover info crosses its long axis`() =
        assertHinge(HingeOrientation.VERTICAL, 1320f, HingeInference.estimate(2640, 1080, null))

    // ── On a rotated screen, estimated in the natural frame ─────────────────

    @Test
    fun `fold 6 without cover info at rotation 0`() =
        assertHinge(HingeOrientation.VERTICAL, 928f, HingeInference.estimateOnScreen(1856, 2160, 0, null))

    @Test
    fun `fold 6 without cover info at rotation 1 is horizontal on screen`() =
        assertHinge(HingeOrientation.HORIZONTAL, 928f, HingeInference.estimateOnScreen(2160, 1856, 1, null))

    @Test
    fun `fold 8 without cover info at rotation 1 is horizontal on screen`() =
        assertHinge(HingeOrientation.HORIZONTAL, 1224f, HingeInference.estimateOnScreen(1848, 2448, 1, null))

    @Test
    fun `fold 8 without cover info at rotation 3 is horizontal on screen`() =
        assertHinge(HingeOrientation.HORIZONTAL, 1224f, HingeInference.estimateOnScreen(1848, 2448, 3, null))

    @Test
    fun `with the cover known the natural-frame estimate agrees with the plain one`() {
        val cover = aspect(1248, 1972)
        assertHinge(HingeOrientation.VERTICAL, 1224f, HingeInference.estimateOnScreen(2448, 1848, 0, cover))
        assertHinge(HingeOrientation.HORIZONTAL, 1224f, HingeInference.estimateOnScreen(1848, 2448, 1, cover))
        val fold6Cover = aspect(968, 2376)
        assertHinge(HingeOrientation.HORIZONTAL, 928f, HingeInference.estimateOnScreen(2160, 1856, 1, fold6Cover))
    }

    @Test
    fun `a flip held sideways without cover info still crosses its long axis`() =
        assertHinge(HingeOrientation.VERTICAL, 1320f, HingeInference.estimateOnScreen(2640, 1080, 1, null))

    @Test
    fun `the fold 6's inner screen taken for its cover would pick the wrong axis`() {
        // Why the cover's shape is only used once two panels are known (PostureMemory.coverAspect).
        assertHinge(HingeOrientation.HORIZONTAL, 1080f, HingeInference.estimate(1856, 2160, aspect(1856, 2160)))
    }
}
