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
                // Lengths relative to the blob, so the tips clear its outline whatever size the seed gives it.
                spikes = listOf(Spike(0f, base.blob.radiusAlongDp, 3f), Spike(180f, base.blob.radiusAlongDp * 1.2f, 3f)),
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

    private fun bandTop(s: CrackSpec) = s.band.minOf { it.offsetDp - it.widthDp / 2f }

    private fun bandBottom(s: CrackSpec) = s.band.maxOf { it.offsetDp + it.widthDp / 2f }

    @Test
    fun `trails run from the band's edges to both ends of the hinge`() {
        val s = spec()
        val prims = CrackGeometry.build(s, frameW, frameH, vertical, density).filterIsInstance<StrokePrim>()
        val trails = prims.drop(s.band.size)
        val originY = s.originAlong * frameH
        val lower = trails[0]
        assertEquals(originY + bandBottom(s) * density, lower.y0, 1e-2f)
        assertTrue(lower.y1 > frameH)
        assertEquals(1224f + 0.5f * density, lower.x0, 1e-3f)
        val core = trails[1]
        assertEquals(CrackColor.TRAIL_WHITE, core.color)
        assertTrue(core.widthPx < lower.widthPx)
        assertEquals(lower.y0, core.y0, 1e-3f)
        val upper = trails[2]
        assertEquals(CrackColor.TRAIL_DARK, upper.color)
        assertEquals(originY + bandTop(s) * density, upper.y0, 1e-2f)
        assertTrue(upper.y1 < 0f)
    }

    @Test
    fun `no trail crosses the band`() {
        // A small blot inside a thick band: the trails must not cut across the band's sub-lines (crop-4-junction).
        repeat(200) { seed ->
            val s = CrackSpecGenerator.generate(Random(seed))
            val prims = CrackGeometry.build(s, frameW, frameH, vertical, density).filterIsInstance<StrokePrim>()
            val originY = s.originAlong * frameH
            val bandTopY = originY + bandTop(s) * density
            val bandBottomY = originY + bandBottom(s) * density
            prims.drop(s.band.size).forEach { trail ->
                val from = minOf(trail.y0, trail.y1)
                val to = maxOf(trail.y0, trail.y1)
                assertTrue(to <= bandTopY + 1e-2f || from >= bandBottomY - 1e-2f, "seed $seed: trail $from..$to crosses the band $bandTopY..$bandBottomY")
            }
        }
    }

    @Test
    fun `the glow is a halo outside the band, clear inside it`() {
        val s = spec()
        val glow = CrackGeometry.build(s, frameW, frameH, vertical, density).first() as GlowPrim
        val bandHalf = (bandBottom(s) - bandTop(s)) / 2f * density
        assertEquals(s.glowDp * density, glow.haloPx, 1e-3f)
        assertEquals(bandHalf + glow.haloPx, glow.halfWidthPx, 1e-3f)
        val stops = glow.stopPositions()
        val edge = glow.haloPx / (2f * glow.halfWidthPx)
        assertEquals(listOf(0f, edge, edge, 1f - edge, 1f - edge, 1f), stops.toList())
        // Monotonic, as a gradient needs.
        stops.toList().zipWithNext().forEach { (a, b) -> assertTrue(a <= b) }
    }

    @Test
    fun `glow stops stay inside the rectangle for any halo`() {
        val wide = GlowPrim(0f, 0f, 10f, 0f, halfWidthPx = 2f, color = CrackColor.BAND_WHITE, alpha = 1f, haloPx = 10f)
        assertEquals(listOf(0f, 0.5f, 0.5f, 0.5f, 0.5f, 1f), wide.stopPositions().toList())
        val none = GlowPrim(0f, 0f, 10f, 0f, halfWidthPx = 0f, color = CrackColor.BAND_WHITE, alpha = 1f, haloPx = 0f)
        assertEquals(listOf(0f, 0f, 0f, 1f, 1f, 1f), none.stopPositions().toList())
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

    /** Whether ([x], [y]) is inside the closed polygon [points] (even-odd ray casting). */
    private fun inside(points: List<Float>, x: Float, y: Float): Boolean {
        var result = false
        val n = points.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[2 * i]
            val yi = points[2 * i + 1]
            val xj = points[2 * j]
            val yj = points[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
            j = i
        }
        return result
    }

    /** Each spike's base midpoint (the middle of the triangle's first and last corners) and the blob's fill. */
    private fun spikeBases(s: CrackSpec, hinge: HingeLine = vertical): Pair<List<Pair<Float, Float>>, List<Float>> {
        val polygons = CrackGeometry.build(s, frameW, frameH, hinge, density).filterIsInstance<PolygonPrim>()
        val fill = polygons.single { it.color == CrackColor.BLOB_BLACK && it.strokeWidthPx == 0f && it.points.size > 6 }
        val bases = polygons.filter { it.points.size == 6 }.map { (it.points[0] + it.points[4]) / 2f to (it.points[1] + it.points[5]) / 2f }
        return bases to fill.points
    }

    @Test
    fun `every spike grows out of the fill, never floats beside it`() {
        // Build 2's bigger splash made the gap between a spike and a dent in the outline 1.5–2.7× wider in dp.
        val specs = (0L until 4_000L).map { CrackSpecGenerator.generate(Random(it)) }.filter { it.blob.spikes.isNotEmpty() }
        for (hinge in listOf(vertical, horizontal)) {
            specs.forEach { s ->
                val (bases, fill) = spikeBases(s, hinge)
                bases.forEachIndexed { i, (x, y) -> assertTrue(inside(fill, x, y), "seed ${s.seed}: spike $i's base is outside the blot") }
            }
        }
    }

    @Test
    fun `the outline radius is exact on the samples and on the edges between them`() {
        val radial = List(48) { i -> if (i % 2 == 0) 0.7f else 1.3f }
        val step = 2.0 * Math.PI / 48
        assertEquals(0.7, CrackGeometry.outlineRadius(radial, 0.0), 1e-6)
        assertEquals(1.3, CrackGeometry.outlineRadius(radial, step), 1e-6)
        assertEquals(0.7, CrackGeometry.outlineRadius(radial, 2.0 * Math.PI), 1e-6)
        assertEquals(1.3, CrackGeometry.outlineRadius(radial, -step), 1e-6)
        // Halfway along an edge of a regular polygon the outline is cos(step / 2) of the way out, not at the samples'.
        val round = List(48) { 1f }
        assertEquals(kotlin.math.cos(step / 2), CrackGeometry.outlineRadius(round, step / 2), 1e-6)
        // Between a dent and a bump the crossing is below the samples' average.
        assertTrue(CrackGeometry.outlineRadius(radial, step / 2) < 1.0)
    }

    @Test
    fun `seed 5781's spikes up the hinge start inside the splash`() {
        // Before: three spikes at 181°, 186° and 191° hovered 2.4–4.4 dp of wallpaper above a dent in the splash.
        val s = CrackSpecGenerator.generate(Random(5781))
        val (bases, fill) = spikeBases(s)
        assertEquals(s.blob.spikes.size, bases.size)
        bases.forEach { (x, y) -> assertTrue(inside(fill, x, y)) }
    }

    // ── Build 2: a 30 dp band and a large splash still fit ───────────────────

    /** A frame the crack is drawn in: a panel, or a window the preview stands in for (the preview is a uniform scale). */
    private class Frame(val name: String, val w: Float, val h: Float, val hinge: HingeLine, val density: Float)

    private val frames = listOf(
        Frame("Fold 8 inner", 2448f, 1848f, vertical, density),
        Frame("Fold 6 inner", 1856f, 2160f, HingeLine(HingeOrientation.VERTICAL, 928f, 0f, HingeSource.ESTIMATED), 2.25f),
        // The preview's fallback before the inner screen is known: the cover window itself, hinge estimated (§5).
        Frame("Fold 8 cover preview", 1248f, 1972f, HingeInference.estimate(1248, 1972, null), 2.625f),
        Frame("Fold 6 cover preview", 968f, 2376f, HingeInference.estimate(968, 2376, null), 2.625f),
    )

    /**
     * The biggest crack the generator can make: a 30 dp band, a splash at the widest across ratio and the longest
     * elongation with every radial multiplier at its maximum, full-length spikes straight along the hinge and out the
     * far side, speckles at the edge of their placement, the widest rim and glow.
     */
    private fun worstCase(originAlong: Float): CrackSpec {
        val g = CrackSpecGenerator
        val base = (0L until 1_000L).map { g.generate(Random(it)) }.first { it.blob.speckles.isNotEmpty() }
        val k = g.BAND_THICKNESS_MAX_DP / (bandBottom(base) - bandTop(base))
        val across = g.BAND_THICKNESS_MAX_DP * g.LARGE_ACROSS_OF_BAND_MAX / 2f
        val along = across * g.LARGE_ELONGATION_MAX
        val spike = { angle: Float -> Spike(angle, across * g.SPIKE_LENGTH_MAX, across * g.SPIKE_WIDTH_MAX_FRACTION) }
        val speckRadius = across * g.SPECKLE_RADIUS_MAX_FRACTION
        return base.copy(
            originAlong = originAlong,
            band = base.band.map { it.copy(offsetDp = it.offsetDp * k, widthDp = it.widthDp * k) },
            glowDp = g.GLOW_MAX_DP,
            blob = BlobSpec(
                radiusAcrossDp = across,
                radiusAlongDp = along,
                radial = List(g.RADIAL_COUNT) { g.RADIAL_MAX },
                spikes = listOf(0f, 180f, g.SPIKE_FAR_SIDE_MIN_DEG, 270f, g.SPIKE_FAR_SIDE_MAX_DEG).map(spike),
                speckles = listOf(
                    Speckle(0f, along * g.SPECKLE_PLACEMENT, speckRadius, CrackColor.SPECK_LIGHT),
                    Speckle(0f, -along * g.SPECKLE_PLACEMENT, speckRadius, CrackColor.SPECK_BLUE),
                    Speckle(across * g.SPECKLE_PLACEMENT, 0f, speckRadius, CrackColor.SPECK_LIGHT),
                ),
                rimWidthDp = g.BAND_THICKNESS_MAX_DP * g.RIM_WIDTH_MAX_OF_BAND,
            ),
        )
    }

    /**
     * Everything of [s] laid out in [f] must sit inside the frame across the band's length (the band and glow along
     * the hinge, the whole blob), the trails must start exactly at the band's edges and never cross it, and the
     * glow's stops must make a valid gradient.
     */
    private fun assertFits(s: CrackSpec, f: Frame, what: String) {
        val prims = CrackGeometry.build(s, f.w, f.h, f.hinge, f.density)
        val vertical = f.hinge.orientation == HingeOrientation.VERTICAL
        // Position along the hinge (u) of a frame point.
        fun u(x: Float, y: Float) = if (vertical) y else f.w - x
        val length = if (vertical) f.h else f.w
        val u0 = s.originAlong * length
        val topU = u0 + bandTop(s) * f.density
        val bottomU = u0 + bandBottom(s) * f.density
        val tol = 1e-2f
        fun inside(x: Float, y: Float, margin: Float = 0f) =
            x - margin >= -tol && x + margin <= f.w + tol && y - margin >= -tol && y + margin <= f.h + tol

        val strokes = prims.filterIsInstance<StrokePrim>()
        strokes.take(s.band.size).forEach { line ->
            val centre = u(line.x0, line.y0)
            assertTrue(centre - line.widthPx / 2f >= 0f && centre + line.widthPx / 2f <= length, "$what: band line off the panel")
        }
        prims.filterIsInstance<GlowPrim>().forEach { glow ->
            val centre = u(glow.x0, glow.y0)
            assertEquals((bandBottom(s) - bandTop(s)) / 2f * f.density + glow.haloPx, glow.halfWidthPx, 1e-2f)
            assertTrue(centre - glow.halfWidthPx >= 0f && centre + glow.halfWidthPx <= length, "$what: glow off the panel")
            val stops = glow.stopPositions().toList()
            assertTrue(stops.first() == 0f && stops.last() == 1f && stops[1] < 0.5f, "$what: stops $stops")
            stops.zipWithNext().forEach { (a, b) -> assertTrue(a <= b, "$what: stops $stops") }
        }
        // Trails: the lower from the band's lower edge, the upper from its upper edge; neither crosses the band.
        val trails = strokes.drop(s.band.size)
        trails.forEach { trail ->
            val start = u(trail.x0, trail.y0)
            val expected = if (trail.color == CrackColor.TRAIL_DARK) topU else bottomU
            assertEquals(expected, start, 1e-2f, "$what: trail start")
            val from = minOf(start, u(trail.x1, trail.y1))
            val to = maxOf(start, u(trail.x1, trail.y1))
            assertTrue(to <= topU + tol || from >= bottomU - tol, "$what: trail $from..$to crosses the band $topU..$bottomU")
        }
        prims.filterIsInstance<PolygonPrim>().forEach { poly ->
            val margin = poly.strokeWidthPx / 2f
            poly.points.chunked(2).forEach { (x, y) -> assertTrue(inside(x, y, margin), "$what: blob point ($x, $y) clipped") }
        }
        prims.filterIsInstance<DotPrim>().forEach { dot ->
            assertTrue(inside(dot.cx, dot.cy, dot.radiusPx), "$what: speckle clipped")
        }
    }

    @Test
    fun `the biggest crack fits every panel and preview`() {
        for (f in frames) {
            for (origin in listOf(CrackSpecGenerator.ORIGIN_MIN, CrackSpecGenerator.ORIGIN_MAX)) {
                val s = worstCase(origin)
                assertEquals(CrackSpecGenerator.BAND_THICKNESS_MAX_DP, bandBottom(s) - bandTop(s), 1e-3f)
                assertFits(s, f, "${f.name} at origin $origin")
            }
        }
    }

    @Test
    fun `generated cracks fit every panel and preview`() {
        val specs = (0L until 2_000L).map { CrackSpecGenerator.generate(Random(it)) }
        for (f in frames) specs.forEach { s -> assertFits(s, f, "${f.name}, seed ${s.seed}") }
    }

    @Test
    fun `a preview is the panel's layout scaled down`() {
        // The preview draws the panel's frame and density times the same factor, so what fits the panel fits it.
        val s = worstCase(CrackSpecGenerator.ORIGIN_MAX)
        val k = 900f / 1856f
        val panel = CrackGeometry.build(s, 1856f, 2160f, HingeLine(HingeOrientation.VERTICAL, 928f, 0f, HingeSource.ESTIMATED), 2.25f)
        val preview = CrackGeometry.build(s, 1856f * k, 2160f * k, HingeLine(HingeOrientation.VERTICAL, 928f * k, 0f, HingeSource.ESTIMATED), 2.25f * k)
        val a = panel.filterIsInstance<PolygonPrim>().flatMap { it.points }
        val b = preview.filterIsInstance<PolygonPrim>().flatMap { it.points }
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (p, q) -> assertEquals(p * k, q, 1e-2f) }
    }
}
