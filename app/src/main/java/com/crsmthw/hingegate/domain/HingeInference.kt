package com.crsmthw.hingegate.domain

import kotlin.math.abs
import kotlin.math.ln

/**
 * Guesses where the hinge is from the inner screen's size when Window Manager does not say (docs/DESIGN.md §5).
 *
 * A book foldable's cover screen has roughly the shape of one half of the inner screen, so the split whose
 * halves look most like the cover wins. Without the cover's shape, only a flip phone's very tall inner screen
 * (long/short ≥ 1.9) is folded across its long axis; everything else is a book with a vertical hinge — the long
 * side cannot be used as a rule, because the Fold 8's inner screen is landscape and the Fold 6's is portrait.
 */
object HingeInference {

    /** A flip's inner screen is about 21:9 or taller; a book's is close to square. */
    const val FLIP_ASPECT = 1.9f

    fun estimate(width: Int, height: Int, coverAspect: Float?): HingeLine {
        val w = width.toFloat()
        val h = height.toFloat()
        val vertical = HingeLine(HingeOrientation.VERTICAL, w / 2f, 0f, HingeSource.ESTIMATED)
        val horizontal = HingeLine(HingeOrientation.HORIZONTAL, h / 2f, 0f, HingeSource.ESTIMATED)
        if (width <= 0 || height <= 0) return vertical

        if (coverAspect != null && coverAspect > 0f) {
            val target = ln(coverAspect)
            val verticalDistance = abs(ln(aspect(w / 2f, h)) - target)
            val horizontalDistance = abs(ln(aspect(w, h / 2f)) - target)
            return if (horizontalDistance < verticalDistance) horizontal else vertical
        }

        val longOverShort = maxOf(w, h) / minOf(w, h)
        return if (longOverShort >= FLIP_ASPECT && h > w) horizontal else vertical
    }

    /**
     * The estimate for a screen currently [screenW] × [screenH] at [rotation], made in the panel's NATURAL frame
     * and mapped back to the screen. Without the cover's shape the rule "a book's hinge is vertical" only holds in
     * the natural frame (a Fold 8 held at rotation 1 is 1848 × 2448 with a horizontal hinge on screen); with it the
     * answer is the same either way.
     */
    fun estimateOnScreen(screenW: Int, screenH: Int, rotation: Int, coverAspect: Float?): HingeLine {
        if (screenW <= 0 || screenH <= 0) return estimate(screenW, screenH, coverAspect)
        val (naturalW, naturalH) = PanelTransform.naturalSize(rotation, screenW.toFloat(), screenH.toFloat())
        val natural = estimate(naturalW.toInt(), naturalH.toInt(), coverAspect)
        return PanelTransform.hingeToScreen(rotation, screenW.toFloat(), screenH.toFloat(), natural)
    }

    private fun aspect(a: Float, b: Float): Float = maxOf(a, b) / minOf(a, b)
}
