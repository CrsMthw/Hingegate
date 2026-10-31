package com.crsmthw.hingegate.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the renderer draws, in frame pixels. Each primitive carries a colour role and its own alpha. */
sealed interface CrackPrimitive

/** A straight line with BUTT caps, [widthPx] wide. */
data class StrokePrim(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val widthPx: Float, val color: CrackColor, val alpha: Float,
) : CrackPrimitive

/**
 * A soft halo along an axis-aligned line: [color] at the centre fading to transparent [halfWidthPx] away on both
 * sides (a gradient, never a blur filter — those are slow and not supported everywhere in hardware).
 */
data class GlowPrim(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val halfWidthPx: Float, val color: CrackColor, val alpha: Float,
) : CrackPrimitive

/** A closed polygon `[x0, y0, x1, y1, …]`; [strokeWidthPx] 0 fills it, more strokes its outline. */
data class PolygonPrim(
    val points: List<Float>,
    val color: CrackColor,
    val alpha: Float,
    val strokeWidthPx: Float = 0f,
) : CrackPrimitive

data class DotPrim(val cx: Float, val cy: Float, val radiusPx: Float, val color: CrackColor, val alpha: Float) : CrackPrimitive

/**
 * Lays a [CrackSpec] out in a frame (docs/DESIGN.md §8.1). The frame is the panel's NATURAL frame in pixels and
 * the hinge is given in that frame. Everything is built in "hinge coordinates" — `u` down the hinge (length L),
 * `v` across it toward the band side (extent D) — and mapped to the frame at the end, so the one layout serves
 * both hinge orientations.
 */
object CrackGeometry {

    /** Lines run this far past the frame's edges so anti-aliasing never leaves a faint end inside the screen. */
    const val OVERSHOOT_PX = 2f

    /** The core of a trail is this fraction of the trail's width. */
    const val TRAIL_CORE_FRACTION = 0.45f

    /** The rim is drawn under the fill, so only its outer half shows: a thin dark-blue sheen (photo 1). */
    const val RIM_ALPHA = 0.85f

    const val SPECKLE_ALPHA = 0.9f

    /** Spike bases sit this far inside the blob's outline so the triangles grow out of the fill seamlessly. */
    const val SPIKE_BASE_INSET = 0.8f

    /** How the glow's colour is picked: the colour of the band's first core. */
    private val CORE_COLORS = setOf(CrackColor.BAND_WHITE, CrackColor.BAND_ICE)

    fun build(spec: CrackSpec, frameW: Float, frameH: Float, hinge: HingeLine, density: Float): List<CrackPrimitive> {
        val map = HingeMap(hinge, frameW, frameH)
        val px = { dp: Float -> dp * density }
        val u0 = spec.originAlong * map.length
        val bandEnd = map.depth + OVERSHOOT_PX
        val out = ArrayList<CrackPrimitive>(spec.band.size + spec.blob.spikes.size + spec.blob.speckles.size + 8)

        // Glow behind the whole band.
        if (spec.glowDp > 0f && spec.band.isNotEmpty()) {
            val top = spec.band.minOf { it.offsetDp - it.widthDp / 2f }
            val bottom = spec.band.maxOf { it.offsetDp + it.widthDp / 2f }
            val centre = u0 + px((top + bottom) / 2f)
            val half = px((bottom - top) / 2f + spec.glowDp)
            val color = spec.band.firstOrNull { it.color in CORE_COLORS }?.color ?: CrackColor.BAND_WHITE
            out += map.glow(centre, 0f, bandEnd, half, color, spec.glowAlpha)
        }

        // Band sub-lines, from the blob to past the far edge.
        for (line in spec.band) {
            out += map.across(u0 + px(line.offsetDp), 0f, bandEnd, px(line.widthDp), line.color, line.alpha)
        }

        // Lower trail: from the origin down to the far end of the hinge (plus its whiter core).
        val lower = spec.lowerTrail
        val lowerV = px(lower.offsetDp)
        out += map.along(lowerV, u0, map.length + OVERSHOOT_PX, px(lower.widthDp), lower.color, lower.alpha)
        lower.coreColor?.let { core ->
            out += map.along(lowerV, u0, map.length + OVERSHOOT_PX, px(lower.widthDp) * TRAIL_CORE_FRACTION, core, lower.alpha)
        }

        // Upper trail: from the origin up to the near end.
        spec.upperTrail?.let { upper ->
            out += map.along(px(upper.offsetDp), u0, -OVERSHOOT_PX, px(upper.widthDp), upper.color, upper.alpha)
        }

        // Blob: rim under the fill, then the fill, spikes and speckles.
        val blob = spec.blob
        val outline = blobOutline(blob, u0, px, map)
        out += PolygonPrim(outline, CrackColor.BLOB_RIM, RIM_ALPHA, px(blob.rimWidthDp))
        out += PolygonPrim(outline, CrackColor.BLOB_BLACK, 1f)
        for (spike in blob.spikes) {
            out += PolygonPrim(spikeTriangle(blob, spike, u0, px, map), CrackColor.BLOB_BLACK, 1f)
        }
        for (speckle in blob.speckles) {
            out += DotPrim(
                cx = map.x(u0 + px(speckle.dyDp), px(speckle.dxDp)),
                cy = map.y(u0 + px(speckle.dyDp), px(speckle.dxDp)),
                radiusPx = px(speckle.radiusDp),
                color = speckle.color,
                alpha = SPECKLE_ALPHA,
            )
        }
        return out
    }

    private fun blobOutline(blob: BlobSpec, u0: Float, px: (Float) -> Float, map: HingeMap): List<Float> {
        val n = blob.radial.size
        val points = ArrayList<Float>(n * 2)
        for (i in 0 until n) {
            val theta = 2.0 * PI * i / n
            val r = blob.radial[i]
            val u = u0 + (cos(theta) * px(blob.radiusAlongDp) * r).toFloat()
            val v = (sin(theta) * px(blob.radiusAcrossDp) * r).toFloat()
            points += map.x(u, v)
            points += map.y(u, v)
        }
        return points
    }

    private fun spikeTriangle(blob: BlobSpec, spike: Spike, u0: Float, px: (Float) -> Float, map: HingeMap): List<Float> {
        val angle = spike.angleDeg * PI / 180.0
        val du = cos(angle).toFloat()
        val dv = sin(angle).toFloat()
        // Base centre: on the (unperturbed) ellipse in the spike's direction, pulled a little inside so the
        // triangle grows out of the fill; the length is measured from there.
        val baseU = u0 + du * px(blob.radiusAlongDp) * SPIKE_BASE_INSET
        val baseV = dv * px(blob.radiusAcrossDp) * SPIKE_BASE_INSET
        val tipU = baseU + du * px(spike.lengthDp)
        val tipV = baseV + dv * px(spike.lengthDp)
        val half = px(spike.widthDp) / 2f
        // Perpendicular to the spike's direction in (u, v).
        val pu = -dv * half
        val pv = du * half
        return listOf(
            map.x(baseU + pu, baseV + pv), map.y(baseU + pu, baseV + pv),
            map.x(tipU, tipV), map.y(tipU, tipV),
            map.x(baseU - pu, baseV - pv), map.y(baseU - pu, baseV - pv),
        )
    }

    /**
     * Hinge coordinates → frame. VERTICAL hinge at x = h: `x = h + v`, `y = u`. HORIZONTAL at y = h is the
     * vertical layout turned 90° clockwise: `x = W − u`, `y = h + v`.
     */
    private class HingeMap(hinge: HingeLine, private val frameW: Float, frameH: Float) {
        private val vertical = hinge.orientation == HingeOrientation.VERTICAL
        private val h = hinge.positionPx
        val length: Float = if (vertical) frameH else frameW
        val depth: Float = if (vertical) frameW - h else frameH - h

        fun x(u: Float, v: Float): Float = if (vertical) h + v else frameW - u
        fun y(u: Float, v: Float): Float = if (vertical) u else h + v

        /** A line across the hinge (the band direction) at `u`, from `v0` to `v1`. */
        fun across(u: Float, v0: Float, v1: Float, width: Float, color: CrackColor, alpha: Float) =
            StrokePrim(x(u, v0), y(u, v0), x(u, v1), y(u, v1), width, color, alpha)

        /** A line along the hinge at `v`, from `u0` to `u1`. */
        fun along(v: Float, u0: Float, u1: Float, width: Float, color: CrackColor, alpha: Float) =
            StrokePrim(x(u0, v), y(u0, v), x(u1, v), y(u1, v), width, color, alpha)

        fun glow(u: Float, v0: Float, v1: Float, half: Float, color: CrackColor, alpha: Float) =
            GlowPrim(x(u, v0), y(u, v0), x(u, v1), y(u, v1), half, color, alpha)
    }
}
