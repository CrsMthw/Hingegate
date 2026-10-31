package com.crsmthw.hingegate.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import androidx.core.graphics.withMatrix
import com.crsmthw.hingegate.domain.AlphaScale
import com.crsmthw.hingegate.domain.CrackGeometry
import com.crsmthw.hingegate.domain.CrackPrimitive
import com.crsmthw.hingegate.domain.CrackSpec
import com.crsmthw.hingegate.domain.DotPrim
import com.crsmthw.hingegate.domain.GlowPrim
import com.crsmthw.hingegate.domain.HingeLine
import com.crsmthw.hingegate.domain.PanelTransform
import com.crsmthw.hingegate.domain.PolygonPrim
import com.crsmthw.hingegate.domain.StrokePrim
import kotlin.math.roundToInt

/**
 * Draws a crack on any `android.graphics.Canvas` — the overlay view, the wallpaper surface and the UI preview all
 * use this one class (docs/DESIGN.md §8.2).
 *
 * The geometry is built in the panel's natural frame and drawn through the rotation's matrix, so the crack stays
 * on the same physical spot of the panel. It is rebuilt only when the spec, the frame, the hinge or the density
 * changes (a rotation or a resize); every other frame reuses the prepared paths and shaders, and the paints are
 * shared, so the flicker's frames allocate nothing.
 */
class CrackRenderer(private val palette: CrackPalette) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val matrix = Matrix()

    private var cacheKey: CacheKey? = null
    private var prepared: List<Prepared> = emptyList()

    /**
     * [hingeInScreen] is in the canvas's (current screen) coordinates; [appearAlpha] multiplies every alpha (the
     * appear flicker, `CrackAppearance`); [alphaScale] too, clamped to opaque (`AlphaScale`: the overlay makes up
     * for its translucent window with it).
     */
    fun draw(
        canvas: Canvas,
        screenW: Int,
        screenH: Int,
        rotation: Int,
        hingeInScreen: HingeLine,
        crack: CrackSpec,
        density: Float,
        appearAlpha: Float = 1f,
        alphaScale: Float = 1f,
    ) {
        if (screenW <= 0 || screenH <= 0) return
        val (naturalW, naturalH) = PanelTransform.naturalSize(rotation, screenW.toFloat(), screenH.toFloat())
        val hinge = PanelTransform.hingeToNatural(rotation, screenW.toFloat(), screenH.toFloat(), hingeInScreen)
        val key = CacheKey(crack, naturalW, naturalH, hinge, density)
        if (key != cacheKey) {
            prepared = CrackGeometry.build(crack, naturalW, naturalH, hinge, density).map(::prepare)
            cacheKey = key
        }
        matrix.setValues(PanelTransform.naturalToScreen(rotation, naturalW, naturalH).toMatrixValues())
        val alpha = appearAlpha.coerceIn(0f, 1f)
        val scale = alphaScale.coerceAtLeast(0f)
        canvas.withMatrix(matrix) {
            for (item in prepared) drawItem(this, item, alpha, scale)
        }
    }

    private fun drawItem(canvas: Canvas, item: Prepared, appearAlpha: Float, scale: Float) {
        when (item) {
            is Prepared.Line -> {
                strokePaint.strokeWidth = item.width
                strokePaint.color = item.color
                strokePaint.alpha = alphaOf(item.color, AlphaScale.apply(item.alpha * appearAlpha, scale))
                canvas.drawLine(item.x0, item.y0, item.x1, item.y1, strokePaint)
            }
            is Prepared.Glow -> {
                glowPaint.shader = item.shader
                // With a shader set, the paint's alpha still scales the shader's output.
                glowPaint.alpha = (AlphaScale.apply(item.alpha * appearAlpha, scale) * OPAQUE).roundToInt()
                canvas.drawRect(item.rect, glowPaint)
                glowPaint.shader = null
            }
            is Prepared.Shape -> {
                val paint = if (item.strokeWidth > 0f) {
                    outlinePaint.also { it.strokeWidth = item.strokeWidth }
                } else {
                    fillPaint
                }
                paint.color = item.color
                paint.alpha = alphaOf(item.color, AlphaScale.apply(item.alpha * appearAlpha, scale))
                canvas.drawPath(item.path, paint)
            }
            is Prepared.Dot -> {
                fillPaint.color = item.color
                fillPaint.alpha = alphaOf(item.color, AlphaScale.apply(item.alpha * appearAlpha, scale))
                canvas.drawCircle(item.cx, item.cy, item.radius, fillPaint)
            }
        }
    }

    private fun prepare(primitive: CrackPrimitive): Prepared = when (primitive) {
        is StrokePrim -> Prepared.Line(
            primitive.x0, primitive.y0, primitive.x1, primitive.y1,
            primitive.widthPx, palette[primitive.color], primitive.alpha,
        )
        is GlowPrim -> prepareGlow(primitive)
        is PolygonPrim -> Prepared.Shape(pathOf(primitive.points), palette[primitive.color], primitive.alpha, primitive.strokeWidthPx)
        is DotPrim -> Prepared.Dot(primitive.cx, primitive.cy, primitive.radiusPx, palette[primitive.color], primitive.alpha)
    }

    /**
     * The glow is axis-aligned (the band is perpendicular to the hinge): a rectangle with a gradient across it,
     * clear over the band itself and fading from full colour at each band edge to transparent outward
     * ([GlowPrim.stopPositions]).
     */
    private fun prepareGlow(glow: GlowPrim): Prepared.Glow {
        val color = palette[glow.color]
        val clear = ColorUtils.setAlphaComponent(color, 0)
        val stops = intArrayOf(clear, color, clear, clear, color, clear)
        val positions = glow.stopPositions()
        val horizontal = glow.y0 == glow.y1
        val rect: RectF
        val shader: Shader
        if (horizontal) {
            rect = RectF(minOf(glow.x0, glow.x1), glow.y0 - glow.halfWidthPx, maxOf(glow.x0, glow.x1), glow.y0 + glow.halfWidthPx)
            shader = LinearGradient(0f, rect.top, 0f, rect.bottom, stops, positions, Shader.TileMode.CLAMP)
        } else {
            rect = RectF(glow.x0 - glow.halfWidthPx, minOf(glow.y0, glow.y1), glow.x0 + glow.halfWidthPx, maxOf(glow.y0, glow.y1))
            shader = LinearGradient(rect.left, 0f, rect.right, 0f, stops, positions, Shader.TileMode.CLAMP)
        }
        return Prepared.Glow(rect, shader, glow.alpha)
    }

    private fun pathOf(points: List<Float>): Path = Path().apply {
        if (points.size < 4) return@apply
        moveTo(points[0], points[1])
        var i = 2
        while (i + 1 < points.size) {
            lineTo(points[i], points[i + 1])
            i += 2
        }
        close()
    }

    private fun alphaOf(color: Int, alpha: Float): Int = (color.alpha * alpha.coerceIn(0f, 1f)).roundToInt()

    private data class CacheKey(
        val crack: CrackSpec,
        val naturalW: Float,
        val naturalH: Float,
        val hinge: HingeLine,
        val density: Float,
    )

    private sealed interface Prepared {
        class Line(
            val x0: Float, val y0: Float, val x1: Float, val y1: Float,
            val width: Float, val color: Int, val alpha: Float,
        ) : Prepared

        class Glow(val rect: RectF, val shader: Shader, val alpha: Float) : Prepared

        class Shape(val path: Path, val color: Int, val alpha: Float, val strokeWidth: Float) : Prepared

        class Dot(val cx: Float, val cy: Float, val radius: Float, val color: Int, val alpha: Float) : Prepared
    }

    private companion object {
        const val OPAQUE = 0xFF
    }
}
