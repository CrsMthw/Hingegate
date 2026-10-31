package com.crsmthw.hingegate.domain

/**
 * A 2-D affine map `x' = a·x + b·y + c`, `y' = d·x + e·y + f` — the top two rows of an `android.graphics.Matrix`,
 * kept here so the mapping is unit-testable without Android.
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {

    fun mapX(x: Float, y: Float): Float = a * x + b * y + c

    fun mapY(x: Float, y: Float): Float = d * x + e * y + f

    /** Row-major 3×3, the order `android.graphics.Matrix.setValues` expects. */
    fun toMatrixValues(): FloatArray = floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)

    /** This map first, then [next]: `(this then next)(p) == next(this(p))`. */
    infix fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.b * d,
        b = next.a * b + next.b * e,
        c = next.a * c + next.b * f + next.c,
        d = next.d * a + next.e * d,
        e = next.d * b + next.e * e,
        f = next.d * c + next.e * f + next.f,
    )

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)
    }
}

/**
 * Pins the crack to the PANEL (docs/DESIGN.md §6). The crack is built in the panel's natural frame (rotation 0,
 * size W × H) and drawn through [naturalToScreen] for the CURRENT rotation, so it sits on the same physical side
 * of the hinge however the phone is held, and turns with the hardware if the phone rotates while it shows.
 *
 * `r` is `Display.getRotation()`; the rotation is never stored with the crack, because it can be stale while
 * display 0 is swapping panels.
 */
object PanelTransform {

    fun naturalToScreen(rotation: Int, naturalW: Float, naturalH: Float): Affine = when (normalize(rotation)) {
        1 -> Affine(0f, 1f, 0f, -1f, 0f, naturalW)          // (y, W − x)
        2 -> Affine(-1f, 0f, naturalW, 0f, -1f, naturalH)   // (W − x, H − y)
        3 -> Affine(0f, -1f, naturalH, 1f, 0f, 0f)          // (H − y, x)
        else -> Affine.IDENTITY
    }

    fun screenToNatural(rotation: Int, naturalW: Float, naturalH: Float): Affine = when (normalize(rotation)) {
        1 -> Affine(0f, -1f, naturalW, 1f, 0f, 0f)          // inverse of (y, W − x)
        2 -> Affine(-1f, 0f, naturalW, 0f, -1f, naturalH)   // its own inverse
        3 -> Affine(0f, 1f, 0f, -1f, 0f, naturalH)          // inverse of (H − y, x)
        else -> Affine.IDENTITY
    }

    /** The natural (rotation 0) size of a screen currently [screenW] × [screenH]: swapped on odd rotations. */
    fun naturalSize(rotation: Int, screenW: Float, screenH: Float): Pair<Float, Float> =
        if (normalize(rotation) % 2 == 1) screenH to screenW else screenW to screenH

    /**
     * The hinge (a line in the current screen frame) as a line in the natural frame. A vertical screen hinge is
     * horizontal in the natural frame on odd rotations, and vice versa.
     */
    fun hingeToNatural(rotation: Int, screenW: Float, screenH: Float, hinge: HingeLine): HingeLine {
        val (naturalW, naturalH) = naturalSize(rotation, screenW, screenH)
        val map = screenToNatural(rotation, naturalW, naturalH)
        // Two points on the line, mapped: whichever coordinate they share is the line's position.
        val (x0, y0, x1, y1) = when (hinge.orientation) {
            HingeOrientation.VERTICAL -> listOf(hinge.positionPx, 0f, hinge.positionPx, screenH)
            HingeOrientation.HORIZONTAL -> listOf(0f, hinge.positionPx, screenW, hinge.positionPx)
        }
        val nx0 = map.mapX(x0, y0)
        val nx1 = map.mapX(x1, y1)
        val ny0 = map.mapY(x0, y0)
        return if (nx0 == nx1) {
            hinge.copy(orientation = HingeOrientation.VERTICAL, positionPx = nx0)
        } else {
            hinge.copy(orientation = HingeOrientation.HORIZONTAL, positionPx = ny0)
        }
    }

    private fun normalize(rotation: Int): Int = ((rotation % 4) + 4) % 4
}
