package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.test.assertEquals

class PanelTransformTest {

    private val w = 2448f
    private val h = 1848f

    private fun Affine.map(x: Float, y: Float) = mapX(x, y) to mapY(x, y)

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-3f)
        assertEquals(expected.second, actual.second, 1e-3f)
    }

    @Test
    fun `corners for every rotation`() {
        // Natural top-left, top-right, bottom-left, bottom-right.
        val corners = listOf(0f to 0f, w to 0f, 0f to h, w to h)
        val expected = mapOf(
            0 to listOf(0f to 0f, w to 0f, 0f to h, w to h),
            1 to listOf(0f to w, 0f to 0f, h to w, h to 0f),
            2 to listOf(w to h, 0f to h, w to 0f, 0f to 0f),
            3 to listOf(h to 0f, h to w, 0f to 0f, 0f to w),
        )
        for (r in 0..3) {
            val map = PanelTransform.naturalToScreen(r, w, h)
            corners.forEachIndexed { i, (x, y) -> assertPoint(expected.getValue(r)[i], map.map(x, y)) }
        }
    }

    @Test
    fun `round trips for every rotation`() {
        for (r in 0..3) {
            val there = PanelTransform.naturalToScreen(r, w, h)
            val back = PanelTransform.screenToNatural(r, w, h)
            for ((x, y) in listOf(0f to 0f, 100f to 200f, 1224f to 1100f, w to h)) {
                assertPoint(x to y, back.map(there.mapX(x, y), there.mapY(x, y)))
            }
            val identity = there then back
            assertPoint(37f to 91f, identity.map(37f, 91f))
        }
    }

    @Test
    fun `composition applies this first then next`() {
        val translate = Affine(1f, 0f, 10f, 0f, 1f, 20f)
        val scale = Affine(2f, 0f, 0f, 0f, 3f, 0f)
        assertPoint(22f to 63f, (translate then scale).map(1f, 1f))
        assertPoint(12f to 23f, (scale then translate).map(1f, 1f))
    }

    @Test
    fun `matrix values are row major with an affine last row`() {
        val values = Affine(1f, 2f, 3f, 4f, 5f, 6f).toMatrixValues()
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 0f, 0f, 1f), values.toList())
    }

    @Test
    fun `natural size swaps on odd rotations`() {
        assertEquals(w to h, PanelTransform.naturalSize(0, w, h))
        assertEquals(w to h, PanelTransform.naturalSize(1, h, w))
        assertEquals(w to h, PanelTransform.naturalSize(2, w, h))
        assertEquals(w to h, PanelTransform.naturalSize(3, h, w))
    }

    @Test
    fun `a hinge seen through every rotation lands on the same natural line`() {
        val natural = HingeLine(HingeOrientation.VERTICAL, 1000f, 12f, HingeSource.WINDOW_MANAGER)
        for (r in 0..3) {
            // Where the natural line x = 1000 appears on screen at rotation r.
            val toScreen = PanelTransform.naturalToScreen(r, w, h)
            val (sw, sh) = if (r % 2 == 1) h to w else w to h
            val sx0 = toScreen.mapX(1000f, 0f)
            val sx1 = toScreen.mapX(1000f, h)
            val screenHinge = if (sx0 == sx1) {
                natural.copy(orientation = HingeOrientation.VERTICAL, positionPx = sx0)
            } else {
                natural.copy(orientation = HingeOrientation.HORIZONTAL, positionPx = toScreen.mapY(1000f, 0f))
            }
            val back = PanelTransform.hingeToNatural(r, sw, sh, screenHinge)
            assertEquals(natural, back, "rotation $r")
        }
    }

    @Test
    fun `odd rotations turn a vertical screen hinge horizontal in the natural frame`() {
        val screen = HingeLine(HingeOrientation.VERTICAL, 924f, 0f, HingeSource.ESTIMATED)
        val natural = PanelTransform.hingeToNatural(1, h, w, screen)
        assertEquals(HingeOrientation.HORIZONTAL, natural.orientation)
        assertEquals(924f, natural.positionPx, 1e-3f)
        val natural3 = PanelTransform.hingeToNatural(3, h, w, screen)
        assertEquals(HingeOrientation.HORIZONTAL, natural3.orientation)
        assertEquals(h - 924f, natural3.positionPx, 1e-3f)
    }

    @Test
    fun `a hinge mapped to the screen and back is the same natural hinge`() {
        val natural = HingeLine(HingeOrientation.VERTICAL, 1000f, 4f, HingeSource.ESTIMATED)
        for (r in 0..3) {
            val (screenW, screenH) = if (r % 2 == 1) h to w else w to h
            val onScreen = PanelTransform.hingeToScreen(r, screenW, screenH, natural)
            assertEquals(if (r % 2 == 1) HingeOrientation.HORIZONTAL else HingeOrientation.VERTICAL, onScreen.orientation)
            assertEquals(natural, PanelTransform.hingeToNatural(r, screenW, screenH, onScreen))
        }
    }

    @Test
    fun `a natural vertical hinge lands where the rotation puts it`() {
        val natural = HingeLine(HingeOrientation.VERTICAL, 1000f, 0f, HingeSource.ESTIMATED)
        // r=1: (x, y) -> (y, W - x), so x = 1000 becomes the screen line y = 2448 - 1000.
        assertEquals(w - 1000f, PanelTransform.hingeToScreen(1, h, w, natural).positionPx, 1e-3f)
        // r=2: (W - x, H - y).
        assertEquals(w - 1000f, PanelTransform.hingeToScreen(2, w, h, natural).positionPx, 1e-3f)
        // r=3: (H - y, x), so x = 1000 stays y = 1000.
        assertEquals(1000f, PanelTransform.hingeToScreen(3, h, w, natural).positionPx, 1e-3f)
    }
}
