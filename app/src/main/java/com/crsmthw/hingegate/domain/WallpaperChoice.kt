package com.crsmthw.hingegate.domain

import kotlin.math.abs
import kotlin.math.ln

/** The two wallpaper images: one for the inner (main) screen, one for the cover screen. */
enum class ImageSlot { MAIN, COVER }

/** Source rectangle in image pixels (left, top, right, bottom). */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Pure decisions for the wallpaper mode (docs/DESIGN.md §12.2–12.3). */
object WallpaperChoice {

    /**
     * Which image to draw. UNFOLDED → MAIN, else COVER; FOLDED → the reverse preference. While UNKNOWN, the
     * surface's shape is compared with the two learned panels (log-aspect distance); with nothing learned, MAIN.
     * Null when no image is set at all.
     */
    fun slotFor(
        posture: Posture,
        available: Set<ImageSlot>,
        surfaceW: Int,
        surfaceH: Int,
        coverAspect: Float?,
        innerAspect: Float?,
    ): ImageSlot? {
        val preferred = when (posture) {
            Posture.UNFOLDED -> ImageSlot.MAIN
            Posture.FOLDED -> ImageSlot.COVER
            Posture.UNKNOWN -> guess(surfaceW, surfaceH, coverAspect, innerAspect)
        }
        val other = if (preferred == ImageSlot.MAIN) ImageSlot.COVER else ImageSlot.MAIN
        return when {
            preferred in available -> preferred
            other in available -> other
            else -> null
        }
    }

    private fun guess(w: Int, h: Int, coverAspect: Float?, innerAspect: Float?): ImageSlot {
        if (w <= 0 || h <= 0 || coverAspect == null || innerAspect == null) return ImageSlot.MAIN
        val surface = ln(maxOf(w, h).toFloat() / minOf(w, h))
        val toCover = abs(surface - ln(coverAspect))
        val toInner = abs(surface - ln(innerAspect))
        return if (toCover < toInner) ImageSlot.COVER else ImageSlot.MAIN
    }

    /** The centred part of a [srcW] × [srcH] image that fills [dstW] × [dstH] without distortion. */
    fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CropRect {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return CropRect(0f, 0f, srcW.toFloat(), srcH.toFloat())
        val srcAspect = srcW.toFloat() / srcH
        val dstAspect = dstW.toFloat() / dstH
        return if (srcAspect > dstAspect) {
            val cropW = srcH * dstAspect
            val left = (srcW - cropW) / 2f
            CropRect(left, 0f, left + cropW, srcH.toFloat())
        } else {
            val cropH = srcW / dstAspect
            val top = (srcH - cropH) / 2f
            CropRect(0f, top, srcW.toFloat(), top + cropH)
        }
    }

    /**
     * The decoder sample size for an image so that the decoded result is the smallest one still covering the
     * target on both axes (`ImageDecoder` reports the size with EXIF orientation already applied). Integer
     * division keeps `src / sample ≥ target` whether the decoder rounds the sampled size up or down.
     */
    fun sampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (targetW <= 0 || targetH <= 0 || srcW <= 0 || srcH <= 0) return 1
        return maxOf(1, minOf(srcW / targetW, srcH / targetH))
    }
}
