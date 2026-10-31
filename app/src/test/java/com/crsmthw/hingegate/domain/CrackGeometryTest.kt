package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrackGeometryTest {

    private val frameW = 2448f
    private val frameH = 1848f
    private val vertical = HingeLine(HingeOrientation.VERTICAL, 1224f, 0f, HingeSource.ESTIMATED)
    private val horizontal = HingeLine(HingeOrientation.HORIZONTAL, 1000f, 0f, HingeSource.ESTIMATED)
    private val density = 2.625f

    /** A deterministic spec with every optional part present. */
    private fun spec(): CrackSpec {
        val base = CrackSpecGenerator.generate(Random(3))
        return base.copy(
            glowDp = 4f,
            lowerTrail = base.lowerTrail.copy(coreColor = CrackColor.TRAIL_WHITE, offsetDp = 0.5f),
            upperTrail = TrailLine(0.8f, CrackColor.TRAIL_DARK, 0.7f),
            blob = base.blob.copy(
                spikes = listOf(Spike(0f, 10f, 3f), Spike(180f, 12f, 3f)),
                speckles = listOf(Speckle(1f, 2f, 0.5f, CrackColor.SPECK_LIGHT)),
            ),
        )
    }

    private fun bandStrokes(spec: CrackSpec, prims: List<CrackPrimitive>) =
        prims.filterIsInstance<StrokePrim>().take(spec.band.size)

    @Test
    fun `band starts at the hinge and passes the far edge`() {
        val s = spec()
        val prims = CrackGeometry.build(s, frameW, frameH, vertical, density)
        val band = bandStrokes(s, prims)
        assertEquals(s.band.size, band.size)
        band.forEachIndexed { i, line ->
            assertEquals(1224f, line.x0, 1e-3f)
            assertTrue(line.x1 > frameW)
            assertEquals(line.y0, line.y1)
            assertEquals(s.band[i].widthDp * density, line.widthPx, 1e-3f)
            assertEquals(s.originAlong * frameH + s.band[i].offsetDp * density, line.y0, 1e-2f)
        }
    }

    @Test
    fun `draw order is glow, band, trails, blob`() {
        val s = spec()
        val prims = CrackGeometry.build(s, frameW, frameH, vertical, density)
        assertTrue(prims.first() is GlowPrim)
        val firstPolygon = prims.indexOfFirst { it is PolygonPrim }
        val lastStroke = prims.indexOfLast { it is StrokePrim }
        assertTrue(lastStroke < firstPolygon)
        val rim = prims[firstPolygon] as PolygonPrim
        assertEquals(CrackColor.BLOB_RIM, rim.color)
        assertTrue(rim.strokeWidthPx > 0f)
        val fill = prims[firstPolygon + 1] as PolygonPrim
        assertEquals(CrackColor.BLOB_BLACK, fill.color)
        assertEquals(0f, fill.strokeWidthPx)
        assertTrue(prims.last() is DotPrim)
    }

    @Test
    fun `trails reach both ends of the hinge`() {
        val s = spec()
        val prims = CrackGeometry.build(s, frameW, frameH, vertical, density).filterIsInstance<StrokePrim>()
        val trails = prims.drop(s.band.size)
        val originY = s.originAlong * frameH
        val lower = trails[0]
        assertEquals(originY, lower.y0, 1e-2f)
        assertTrue(lower.y1 > frameH)
        assertEquals(1224f + 0.5f * density, lower.x0, 1e-3f)
        val core = trails[1]
        assertEquals(CrackColor.TRAIL_WHITE, core.color)
        assertTrue(core.widthPx < lower.widthPx)
        val upper = trails[2]
        assertEquals(CrackColor.TRAIL_DARK, upper.color)
        assertEquals(originY, upper.y0, 1e-2f)
        assertTrue(upper.y1 < 0f)
    }

    @Test
    fun `blob is centred on the origin`() {
        val s = spec()
        val prims = CrackGeometry.build(s, frameW, frameH, vertical, density)
        val fill = prims.filterIsInstance<PolygonPrim>().first { it.strokeWidthPx == 0f }
        val xs = fill.points.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = fill.points.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(CrackSpecGenerator.RADIAL_COUNT, xs.size)
        assertEquals(1224f, xs.average().toFloat(), s.blob.radiusAcrossDp * density * 0.35f)
        assertEquals(s.originAlong * frameH, ys.average().toFloat(), s.blob.radiusAlongDp * density * 0.35f)
        // Elongated along the hinge (y for a vertical hinge).
        assertTrue((ys.max() - ys.min()) >= (xs.max() - xs.min()) * 0.9f)
    }

    @Test
    fun `horizontal hinge is the vertical layout turned clockwise`() {
        val s = spec()
        val prims = CrackGeometry.build(s, frameW, frameH, horizontal, density)
        val band = bandStrokes(s, prims)
        band.forEachIndexed { i, line ->
            // Band runs DOWN from the hinge (y = 1000) past the bottom edge, at x = W − u.
            assertEquals(1000f, line.y0, 1e-3f)
            assertTrue(line.y1 > frameH)
            assertEquals(line.x0, line.x1)
            assertEquals(frameW - (s.originAlong * frameW + s.band[i].offsetDp * density), line.x0, 1e-2f)
        }
        val trails = prims.filterIsInstance<StrokePrim>().drop(s.band.size)
        // The lower trail runs toward x < 0 (u grows to the left), the upper toward x > W.
        assertTrue(trails[0].x1 < 0f)
        assertTrue(trails[2].x1 > frameW)
        assertEquals(1000f + 0.5f * density, trails[0].y0, 1e-3f)
    }

    @Test
    fun `pixel sizes scale with density`() {
        val s = spec()
        val one = CrackGeometry.build(s, frameW, frameH, vertical, 1f)
        val three = CrackGeometry.build(s, frameW, frameH, vertical, 3f)
        val w1 = one.filterIsInstance<StrokePrim>().map { it.widthPx }
        val w3 = three.filterIsInstance<StrokePrim>().map { it.widthPx }
        w1.zip(w3).forEach { (a, b) -> assertEquals(a * 3f, b, 1e-3f) }
        val g1 = one.first() as GlowPrim
        val g3 = three.first() as GlowPrim
        assertEquals(g1.halfWidthPx * 3f, g3.halfWidthPx, 1e-3f)
        val d1 = one.filterIsInstance<DotPrim>().single()
        val d3 = three.filterIsInstance<DotPrim>().single()
        assertEquals(d1.radiusPx * 3f, d3.radiusPx, 1e-3f)
    }

    @Test
    fun `no glow and no upper trail when the spec has none`() {
        val s = spec().copy(glowDp = 0f, upperTrail = null)
        val prims = CrackGeometry.build(s, frameW, frameH, vertical, density)
        assertTrue(prims.none { it is GlowPrim })
        assertTrue(prims.filterIsInstance<StrokePrim>().none { it.color == CrackColor.TRAIL_DARK })
    }

    @Test
    fun `spikes point along their angle`() {
        val s = spec()
        val prims = CrackGeometry.build(s, frameW, frameH, vertical, density)
        val spikes = prims.filterIsInstance<PolygonPrim>().filter { it.points.size == 6 }
        assertEquals(2, spikes.size)
        val originY = s.originAlong * frameH
        // Angle 0 = down the hinge: the tip (2nd point) is below the origin; 180 = up.
        assertTrue(spikes[0].points[3] > originY + s.blob.radiusAlongDp * density)
        assertTrue(spikes[1].points[3] < originY - s.blob.radiusAlongDp * density)
    }
}
