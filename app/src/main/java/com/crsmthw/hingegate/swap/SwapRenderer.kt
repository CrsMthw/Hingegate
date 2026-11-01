package com.crsmthw.hingegate.swap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.crsmthw.hingegate.domain.CropAnchor
import com.crsmthw.hingegate.domain.CrackSpec
import com.crsmthw.hingegate.domain.SwapFailure
import com.crsmthw.hingegate.domain.SwapFrame
import com.crsmthw.hingegate.domain.SwapFraming
import com.crsmthw.hingegate.render.CrackPalette
import com.crsmthw.hingegate.render.CrackRenderer

/**
 * Draws the two variants of the user's wallpaper for the Real wallpaper mode (docs/SWAP-MODE.md §3, §9): the
 * captured original cropped to the inner panel's NATURAL (rotation 0) size — from the centre on Samsung, from the
 * part the server shows the inner screen elsewhere ([SwapFraming], §10.35) — and the same with the crack on
 * top, drawn by the shared [CrackRenderer] with rotation 0 and the hinge in natural coordinates. Display-sized, so
 * the system shows it 1:1; if it scales the image to its own wallpaper size, it scales about the centre, which
 * keeps the hinge on the hinge.
 *
 * The crack is baked for the panel's natural orientation: this mode does not follow rotation.
 *
 * Not thread-safe (its paint and its [CrackRenderer]'s paints, matrix and geometry cache are shared by every
 * render): each user holds its own instance and runs one render at a time — the swapper's fallback render under its
 * lock, [PreparedImages]' renders under their own mutex (a cancelled prepare's render runs on until its canvas work
 * ends, while the next prepare may already be starting).
 */
class SwapRenderer(private val source: CurrentWallpaperSource, private val palette: () -> CrackPalette) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var crackRenderer: CrackRenderer? = null

    /**
     * The variant for [frame]: clean when [spec] is null, cracked otherwise, the original's panel-sized part taken
     * at [anchor]. One full-panel bitmap (about 18 MB on a 2448 × 1848 panel); the caller recycles it after the set.
     * Fails with a [SwapException].
     */
    suspend fun render(frame: SwapFrame, spec: CrackSpec?, anchor: CropAnchor): Result<Bitmap> {
        if (frame.width <= 0 || frame.height <= 0) return Result.failure(SwapException(SwapFailure.NO_PANEL_SIZE))
        val original = source.decodeOriginal(frame.width, frame.height)
            ?: return Result.failure(SwapException(SwapFailure.READ_FAILED))
        return try {
            val output = createBitmap(frame.width, frame.height)
            val canvas = Canvas(output)
            val crop = SwapFraming.crop(original.width, original.height, frame.width, frame.height, anchor)
            canvas.drawBitmap(
                original,
                Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt()),
                RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat()),
                paint,
            )
            if (spec != null) {
                val renderer = crackRenderer ?: CrackRenderer(palette()).also { crackRenderer = it }
                renderer.draw(canvas, frame.width, frame.height, NATURAL_ROTATION, frame.hinge, spec, frame.density)
            }
            Result.success(output)
        } finally {
            original.recycle()
        }
    }

    private companion object {
        /** The frame is the panel's natural one, so the crack is drawn without any rotation. */
        const val NATURAL_ROTATION = 0
    }
}
