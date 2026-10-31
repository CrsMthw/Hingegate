package com.crsmthw.hingegate.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import com.crsmthw.hingegate.HingegateApplication
import com.crsmthw.hingegate.R
import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.data.SurfaceRules
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.CrackAppearance
import com.crsmthw.hingegate.domain.CrackCause
import com.crsmthw.hingegate.domain.CrackSpecGenerator
import com.crsmthw.hingegate.domain.ImageSlot
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.domain.WallpaperChoice
import com.crsmthw.hingegate.posture.PostureSnapshot
import com.crsmthw.hingegate.render.CrackRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The wallpaper mode (docs/DESIGN.md §12): Hingegate as the live wallpaper, drawing the user's image for the
 * current panel and, when a crack is active in this mode, the crack on top (under the launcher's icons).
 *
 * Runs in the app's one process, so every engine (Samsung binds one per panel, plus the picker's preview) shares
 * the posture monitor and the crack controller with the overlay service and the activity.
 */
class HingegateWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CrackEngine()

    private inner class CrackEngine : Engine() {

        private val container = (application as HingegateApplication).container
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var attachment: AutoCloseable? = null
        private var renderer: CrackRenderer? = null

        private var settings = Settings()
        private var active: ActiveCrack? = null
        private var snapshot = PostureSnapshot()
        private var versions: Map<ImageSlot, Long> = emptyMap()

        private var surfaceW = 0
        private var surfaceH = 0
        private var visible = false

        // At most one decoded bitmap per slot, keyed by what it was decoded for.
        private val bitmaps = HashMap<ImageSlot, Decoded>()
        private val loads = HashMap<ImageSlot, Pair<DecodeKey, Job>>()

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val src = Rect()
        private val dst = RectF()
        private val fallbackColor = getColor(R.color.wallpaper_fallback)
        private val frameCallback = Choreographer.FrameCallback { drawFrame() }

        /** What the picker's preview shows while no crack is active: always the same, so it never flickers. */
        private val sampleCrack by lazy {
            ActiveCrack(CrackSpecGenerator.generate(Random(PREVIEW_SEED)), 0L, CrackCause.MANUAL)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // The image is drawn to the surface's size; the launcher's page scrolling must not move it.
            setOffsetNotificationsEnabled(false)
            displayContext?.let { attachment = container.postureMonitor.attach(it) }
            scope.launch {
                combine(
                    container.settingsRepository.settings,
                    container.crackController.active,
                    container.postureMonitor.snapshot,
                    container.wallpaperImages.versions,
                ) { s, a, p, v -> Inputs(s, a, p, v) }
                    .collect { inputs ->
                        settings = inputs.settings
                        active = inputs.active
                        snapshot = inputs.snapshot
                        versions = inputs.versions
                        requestImage()
                        drawFrame()
                    }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
            // Display 0 swapping panels resizes this surface, possibly before the process configuration follows.
            container.postureMonitor.refresh()
            requestImage()
            drawFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (visible) {
                requestImage()
                drawFrame()
            } else {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            surfaceW = 0
            surfaceH = 0
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            scope.cancel()
            attachment?.close()
            attachment = null
            bitmaps.values.forEach { it.bitmap.recycle() }
            bitmaps.clear()
            loads.clear()
            super.onDestroy()
        }

        // ── Images ───────────────────────────────────────────────────────────

        private fun currentSlot(): ImageSlot? = WallpaperChoice.slotFor(
            posture = snapshot.posture,
            available = versions.keys,
            surfaceW = surfaceW,
            surfaceH = surfaceH,
            coverAspect = snapshot.coverAspect,
            innerAspect = innerAspect(),
        )

        private fun innerAspect(): Float? {
            val w = snapshot.innerWidth ?: return null
            val h = snapshot.innerHeight ?: return null
            if (w <= 0 || h <= 0) return null
            return maxOf(w, h).toFloat() / minOf(w, h)
        }

        /** Decodes the slot this frame needs, off the main thread, unless it is already decoded or loading. */
        private fun requestImage() {
            // A removed image is dropped right away.
            bitmaps.keys.filter { it !in versions }.forEach { slot -> bitmaps.remove(slot)?.bitmap?.recycle() }
            if (surfaceW <= 0 || surfaceH <= 0) return
            val slot = currentSlot() ?: return
            val version = versions[slot] ?: return
            val key = DecodeKey(slot, version, surfaceW, surfaceH)
            if (bitmaps[slot]?.key == key || loads[slot]?.first == key) return
            loads.remove(slot)?.second?.cancel()
            val job = scope.launch {
                val bitmap = container.wallpaperImages.decode(slot, key.width, key.height)
                loads.remove(slot)
                if (bitmap == null) return@launch
                bitmaps.put(slot, Decoded(key, bitmap))?.bitmap?.recycle()
                drawFrame()
            }
            loads[slot] = key to job
        }

        // ── Drawing ──────────────────────────────────────────────────────────

        private fun drawFrame() {
            if (!visible || surfaceW <= 0 || surfaceH <= 0) return
            val holder = surfaceHolder
            val canvas = lockCanvas(holder) ?: return
            var animating = false
            try {
                drawBackground(canvas)
                animating = drawCrack(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            // Finite: frames are requested only inside the appear flicker.
            if (animating) {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        private fun lockCanvas(holder: SurfaceHolder): Canvas? = try {
            holder.lockHardwareCanvas()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No hardware canvas, falling back", e)
            try {
                holder.lockCanvas()
            } catch (e2: IllegalStateException) {
                Log.w(TAG, "Surface not drawable", e2)
                null
            }
        }

        private fun drawBackground(canvas: Canvas) {
            val bitmap = currentSlot()?.let { bitmaps[it]?.bitmap }
            if (bitmap == null || bitmap.isRecycled) {
                canvas.drawColor(fallbackColor)
                return
            }
            val crop = WallpaperChoice.centerCrop(bitmap.width, bitmap.height, surfaceW, surfaceH)
            src.set(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt())
            dst.set(0f, 0f, surfaceW.toFloat(), surfaceH.toFloat())
            canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
        }

        /** Returns whether the crack is still inside its appear flicker. */
        private fun drawCrack(canvas: Canvas): Boolean {
            val crack = when {
                SurfaceRules.drawsCrack(CrackMode.WALLPAPER, settings, active, snapshot.posture) -> active
                isPreview && active == null && snapshot.posture == Posture.UNFOLDED -> sampleCrack
                else -> null
            } ?: return false
            val hinge = snapshot.hinge?.takeIf { snapshot.displayWidth == surfaceW && snapshot.displayHeight == surfaceH }
                ?: container.postureMonitor.hingeFor(surfaceW, surfaceH)
            val density = (displayContext ?: this@HingegateWallpaperService).resources.displayMetrics.density
            val now = System.currentTimeMillis()
            val draw = renderer ?: CrackRenderer(container.crackPalette).also { renderer = it }
            draw.draw(
                canvas, surfaceW, surfaceH, snapshot.rotation, hinge, crack.spec, density,
                CrackAppearance.alpha(now - crack.appearedAtEpochMs),
            )
            return CrackAppearance.isAnimating(crack.appearedAtEpochMs, now)
        }
    }

    private data class Inputs(
        val settings: Settings,
        val active: ActiveCrack?,
        val snapshot: PostureSnapshot,
        val versions: Map<ImageSlot, Long>,
    )

    private data class DecodeKey(val slot: ImageSlot, val version: Long, val width: Int, val height: Int)

    private class Decoded(val key: DecodeKey, val bitmap: Bitmap)

    private companion object {
        const val TAG = "HingegateWallpaper"
        const val PREVIEW_SEED = 20261031L
    }
}
