package com.crsmthw.hingegate.domain

import com.crsmthw.hingegate.domain.CrackSpecGenerator as G
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CrackSpecGeneratorTest {

    private val eps = 1e-3f

    private fun assertIn(value: Float, min: Float, max: Float, what: String) =
        assertTrue(value >= min - eps && value <= max + eps, "$what = $value not in [$min, $max]")

    private val specs: List<CrackSpec> by lazy { (0L until 1_000L).map { G.generate(Random(it)) } }

    private fun thickness(s: CrackSpec): Float =
        s.band.maxOf { it.offsetDp + it.widthDp / 2f } - s.band.minOf { it.offsetDp - it.widthDp / 2f }

    /** Only the large splash has speckles (4–12); a small dot never does. */
    private fun isLarge(s: CrackSpec) = s.blob.speckles.isNotEmpty()

    /** The blot's width across the hinge (twice its across-radius) ÷ the band's thickness. */
    private fun acrossOfBand(s: CrackSpec) = 2f * s.blob.radiusAcrossDp / thickness(s)

    @Test
    fun `same seed gives the same crack`() {
        assertEquals(G.generate(Random(42)), G.generate(Random(42)))
        assertNotEquals(G.generate(Random(42)), G.generate(Random(43)))
    }

    @Test
    fun `generation is deterministic per seed`() = (0L until 500L).forEach { seed ->
        assertEquals(G.generate(Random(seed)), G.generate(Random(seed)), "seed $seed")
    }

    @Test
    fun `serialization round trip`() {
        val active = ActiveCrack(G.generate(Random(7)), 1_700_000_000_000L, CrackCause.UNFOLD)
        val json = Json.encodeToString(ActiveCrack.serializer(), active)
        assertEquals(active, Json.decodeFromString(ActiveCrack.serializer(), json))
    }

    @Test
    fun `origin and glow in range`() = specs.forEach { s ->
        assertIn(s.originAlong, G.ORIGIN_MIN, G.ORIGIN_MAX, "originAlong")
        assertIn(s.glowDp, 0f, G.GLOW_MAX_DP, "glowDp")
        assertIn(s.glowAlpha, G.GLOW_ALPHA_MIN, G.GLOW_ALPHA_MAX, "glowAlpha")
    }

    @Test
    fun `band thickness, line widths and colours in range`() = specs.forEach { s ->
        val top = s.band.minOf { it.offsetDp - it.widthDp / 2f }
        val bottom = s.band.maxOf { it.offsetDp + it.widthDp / 2f }
        assertIn(bottom - top, G.BAND_THICKNESS_MIN_DP, G.BAND_THICKNESS_MAX_DP, "band thickness")
        // Centred on the band's centre line.
        assertEquals(0f, (top + bottom) / 2f, 1e-2f)

        val cores = s.band.filter { it.color == CrackColor.BAND_WHITE || it.color == CrackColor.BAND_ICE }
        assertTrue(cores.size in 1..2, "cores = ${cores.size}")
        cores.forEach {
            assertIn(it.widthDp, G.CORE_WIDTH_MIN_DP, thickness(s), "core width")
            assertIn(it.alpha, G.CORE_ALPHA_MIN, 1f, "core alpha")
        }
        val separators = s.band.filter { it.color == CrackColor.BAND_VIOLET_DARK }
        assertTrue(separators.size <= 1)
        separators.forEach {
            assertIn(it.widthDp, G.SEPARATOR_WIDTH_MIN_DP, G.SEPARATOR_WIDTH_MAX_DP, "separator width")
            assertEquals(2, cores.size, "a separator needs two cores")
            val i = s.band.indexOf(it)
            assertTrue(s.band[i - 1] in cores && s.band[i + 1] in cores, "separator sits between the cores")
        }
        val fringes = s.band - cores.toSet() - separators.toSet()
        assertTrue(fringes.size in G.FRINGE_COUNT_MIN..G.FRINGE_COUNT_MAX, "fringes = ${fringes.size}")
        fringes.forEach {
            assertIn(it.widthDp, G.FRINGE_WIDTH_MIN_DP, G.FRINGE_WIDTH_MAX_DP, "fringe width")
            assertIn(it.alpha, G.FRINGE_ALPHA_MIN, 1f, "fringe alpha")
            assertTrue(
                it.color in setOf(CrackColor.BAND_PINK, CrackColor.BAND_GREEN, CrackColor.BAND_CYAN, CrackColor.BAND_LAVENDER),
            )
        }
        // Pink only ever on the top edge.
        s.band.drop(1).forEach { assertNotEquals(CrackColor.BAND_PINK, it.color) }
    }

    @Test
    fun `sub-lines are ordered and overlap by at most the limit`() = specs.forEach { s ->
        s.band.zipWithNext { a, b ->
            val aBottom = a.offsetDp + a.widthDp / 2f
            val bTop = b.offsetDp - b.widthDp / 2f
            assertTrue(bTop - aBottom >= -G.MAX_OVERLAP_DP - eps, "overlap ${aBottom - bTop} in seed ${s.seed}")
            assertTrue(b.offsetDp > a.offsetDp)
        }
    }

    @Test
    fun `pink tops the band about 70 percent of the time`() {
        val pinkTop = specs.count { it.band.first().color == CrackColor.BAND_PINK }
        assertTrue(pinkTop in 630..770, "pink on top in $pinkTop of 1000")
    }

    @Test
    fun `trails in range`() = specs.forEach { s ->
        val lower = s.lowerTrail
        assertIn(lower.widthDp, G.LOWER_WIDTH_MIN_DP, G.LOWER_WIDTH_MAX_DP, "lower width")
        assertTrue(lower.color == CrackColor.TRAIL_YELLOW || lower.color == CrackColor.TRAIL_WHITE)
        assertTrue(lower.coreColor == null || lower.coreColor == CrackColor.TRAIL_WHITE)
        assertIn(lower.offsetDp, 0f, G.LOWER_OFFSET_MAX_DP, "lower offset")
        assertIn(lower.alpha, G.LOWER_ALPHA_MIN, 1f, "lower alpha")
        s.upperTrail?.let { upper ->
            assertEquals(CrackColor.TRAIL_DARK, upper.color)
            assertIn(upper.widthDp, G.UPPER_WIDTH_MIN_DP, G.UPPER_WIDTH_MAX_DP, "upper width")
            assertIn(upper.alpha, G.UPPER_ALPHA_MIN, G.UPPER_ALPHA_MAX, "upper alpha")
        }
    }

    @Test
    fun `trail odds match the spec`() {
        val yellow = specs.count { it.lowerTrail.color == CrackColor.TRAIL_YELLOW }
        val upper = specs.count { it.upperTrail != null }
        assertTrue(yellow in 700..800, "yellow $yellow")
        assertTrue(upper in 750..850, "upper $upper")
    }

    @Test
    fun `blob in range`() = specs.forEach { s ->
        val b = s.blob
        assertEquals(G.RADIAL_COUNT, b.radial.size)
        b.radial.forEach { assertIn(it, G.RADIAL_MIN, G.RADIAL_MAX, "radial") }
        assertIn(b.rimWidthDp / thickness(s), G.RIM_WIDTH_MIN_OF_BAND, G.RIM_WIDTH_MAX_OF_BAND, "rim ÷ band")
        if (!isLarge(s)) {
            assertIn(b.radiusAlongDp / b.radiusAcrossDp, G.SMALL_ELONGATION_MIN, G.SMALL_ELONGATION_MAX, "small elongation")
            assertTrue(b.spikes.size <= G.SMALL_SPIKES_MAX)
        } else {
            assertIn(b.radiusAlongDp / b.radiusAcrossDp, G.LARGE_ELONGATION_MIN, G.LARGE_ELONGATION_MAX, "large elongation")
            assertTrue(b.spikes.size in G.LARGE_SPIKES_MIN..G.LARGE_SPIKES_MAX)
            assertTrue(b.speckles.size in G.SPECKLES_MIN..G.SPECKLES_MAX)
            b.speckles.forEach {
                assertIn(
                    it.radiusDp / b.radiusAcrossDp,
                    G.SPECKLE_RADIUS_MIN_FRACTION,
                    G.SPECKLE_RADIUS_MAX_FRACTION,
                    "speckle radius ÷ across",
                )
                assertTrue(it.color == CrackColor.SPECK_LIGHT || it.color == CrackColor.SPECK_BLUE)
                // Its centre within the placement ellipse, and the whole speck inside the blob's, at any size.
                val e = (it.dxDp / b.radiusAcrossDp).let { x -> x * x } + (it.dyDp / b.radiusAlongDp).let { y -> y * y }
                assertTrue(kotlin.math.sqrt(e) <= G.SPECKLE_PLACEMENT + eps, "speck centre outside in seed ${s.seed}")
                assertTrue(kotlin.math.sqrt(e) + it.radiusDp / b.radiusAcrossDp <= 1f, "speck pokes out in seed ${s.seed}")
            }
        }
        b.spikes.forEach {
            assertIn(it.angleDeg, 0f, 360f, "spike angle")
            assertIn(it.lengthDp / b.radiusAcrossDp, G.SPIKE_LENGTH_MIN, G.SPIKE_LENGTH_MAX, "spike length")
            assertIn(it.widthDp / b.radiusAcrossDp, G.SPIKE_WIDTH_MIN_FRACTION, G.SPIKE_WIDTH_MAX_FRACTION, "spike width")
        }
    }

    @Test
    fun `blob is elongated along the hinge`() = specs.forEach { s ->
        assertTrue(s.blob.radiusAlongDp >= s.blob.radiusAcrossDp - eps)
    }

    @Test
    fun `small blobs are about three in four`() {
        val small = specs.count { !isLarge(it) }
        assertTrue(small in 700..800, "small $small")
    }

    @Test
    fun `band thickness follows the blob size`() = specs.forEach { s ->
        if (!isLarge(s)) {
            assertIn(thickness(s), G.BAND_THICKNESS_MIN_DP, G.BAND_WITH_SMALL_BLOB_MAX_DP, "band with a small blob")
        } else {
            assertIn(thickness(s), G.BAND_WITH_LARGE_BLOB_MIN_DP, G.BAND_THICKNESS_MAX_DP, "band with a large blob")
        }
    }

    // ── Build 2: thicker bands, a blot that scales with them ──────────────────

    @Test
    fun `build 1's thickest band seen is now the thinnest`() {
        assertEquals(13f, G.BAND_THICKNESS_MIN_DP)
        assertEquals(30f, G.BAND_THICKNESS_MAX_DP)
        // Build 1's 6 dp minimum × the scale, and every sub-line size scaled by the same factor.
        assertEquals(G.BAND_THICKNESS_MIN_DP, 6f * G.BAND_SCALE, eps)
        assertEquals(2f * G.BAND_SCALE, G.CORE_WIDTH_MIN_DP, eps)
        assertEquals(6f * G.BAND_SCALE, G.GLOW_MAX_DP, eps)
        assertEquals(0.3f * G.BAND_SCALE, G.MAX_OVERLAP_DP, eps)
    }

    @Test
    fun `band thickness stays within 13 to 30 dp over 10 000 seeds`() {
        val thicknesses = manySpecs.map(::thickness)
        thicknesses.forEachIndexed { seed, t ->
            assertIn(t, G.BAND_THICKNESS_MIN_DP, G.BAND_THICKNESS_MAX_DP, "seed $seed: band thickness")
        }
        // The whole range is used, not just its middle.
        assertTrue(thicknesses.min() < G.BAND_THICKNESS_MIN_DP + 0.5f, "thinnest ${thicknesses.min()}")
        assertTrue(thicknesses.max() > G.BAND_THICKNESS_MAX_DP - 0.5f, "thickest ${thicknesses.max()}")
    }

    @Test
    fun `no band is thinner than the minimum`() {
        val thinnest = manySpecs.minOf(::thickness)
        assertTrue(thinnest >= G.BAND_THICKNESS_MIN_DP - eps, "thinnest band $thinnest dp")
    }

    @Test
    fun `a large splash always comes with a thick band`() {
        val large = manySpecs.filter(::isLarge)
        assertTrue(large.size in 2_300..2_700, "large ${large.size} of 10 000")
        large.forEach { s ->
            assertIn(thickness(s), G.BAND_WITH_LARGE_BLOB_MIN_DP, G.BAND_THICKNESS_MAX_DP, "seed ${s.seed}: band with a large splash")
        }
    }

    @Test
    fun `the blot is sized from the band's thickness`() = manySpecs.forEach { s ->
        val b = s.blob
        val across = acrossOfBand(s)
        val along = 2f * b.radiusAlongDp / thickness(s)
        if (isLarge(s)) {
            assertIn(across, G.LARGE_ACROSS_OF_BAND_MIN, G.LARGE_ACROSS_OF_BAND_MAX, "large across ÷ band")
            assertIn(along, G.LARGE_ACROSS_OF_BAND_MIN * G.LARGE_ELONGATION_MIN, G.LARGE_ACROSS_OF_BAND_MAX * G.LARGE_ELONGATION_MAX, "large along ÷ band")
        } else {
            assertIn(across, G.SMALL_ACROSS_OF_BAND_MIN, G.SMALL_ACROSS_OF_BAND_MAX, "small across ÷ band")
            assertIn(along, G.SMALL_ACROSS_OF_BAND_MIN * G.SMALL_ELONGATION_MIN, G.SMALL_ACROSS_OF_BAND_MAX * G.SMALL_ELONGATION_MAX, "small along ÷ band")
        }
    }

    @Test
    fun `a thicker band gets a bigger blot`() {
        // Same proportions at any thickness: the thickest quarter's small dots are much bigger than the thinnest's.
        val small = manySpecs.filterNot(::isLarge).sortedBy(::thickness)
        val quarter = small.size / 4
        val thin = small.take(quarter).map { it.blob.radiusAcrossDp }.average()
        val thick = small.takeLast(quarter).map { it.blob.radiusAcrossDp }.average()
        assertTrue(thick > thin * 1.6, "thin bands' dots $thin dp, thick bands' $thick dp")
    }

    @Test
    fun `the blot matches the photo and the screenshot`() {
        // Photo 1's splash, ≈ 1.6× the band across and ≈ 3.5× along: the average across, and well inside the range.
        val large = manySpecs.filter(::isLarge)
        assertEquals(1.6, large.map(::acrossOfBand).average(), 0.05)
        val likePhoto1 = large.count {
            acrossOfBand(it) in 1.5f..1.7f && 2f * it.blob.radiusAlongDp / thickness(it) in 3.3f..3.7f
        }
        assertTrue(likePhoto1 > large.size / 20, "photo-1-like splashes: $likePhoto1 of ${large.size}")
        // The small dot of Cris's build 1 screenshot: ≈ 0.85× the band across.
        assertEquals(0.85, manySpecs.filterNot(::isLarge).map(::acrossOfBand).average(), 0.05)
    }

    @Test
    fun `a spec saved by build 1 still loads`() {
        // Build 1's shape, values and all (a 6 dp band, a 3 dp small dot): fields were neither added nor renamed.
        val json = Json { ignoreUnknownKeys = true }
        val saved = """{"spec":{"seed":12,"originAlong":0.6,"band":[""" +
            """{"offsetDp":-2.5,"widthDp":1.0,"color":"BAND_PINK","alpha":0.8},""" +
            """{"offsetDp":0.0,"widthDp":3.0,"color":"BAND_WHITE","alpha":1.0},""" +
            """{"offsetDp":2.5,"widthDp":1.0,"color":"BAND_GREEN","alpha":0.9}],""" +
            """"glowDp":2.0,"glowAlpha":0.3,""" +
            """"lowerTrail":{"widthDp":2.0,"color":"TRAIL_YELLOW","alpha":0.9,"coreColor":"TRAIL_WHITE","offsetDp":0.5},""" +
            """"upperTrail":{"widthDp":0.8,"color":"TRAIL_DARK","alpha":0.7},""" +
            """"blob":{"radiusAcrossDp":3.0,"radiusAlongDp":4.0,"radial":[""" + List(48) { "1.0" }.joinToString(",") + """],""" +
            """"spikes":[{"angleDeg":10.0,"lengthDp":3.0,"widthDp":0.5}],"speckles":[],"rimWidthDp":1.0}},""" +
            """"appearedAtEpochMs":1700000000000,"cause":"UNFOLD"}"""
        val active = json.decodeFromString(ActiveCrack.serializer(), saved)
        assertEquals(3, active.spec.band.size)
        assertEquals(6f, thickness(active.spec), eps)
        assertEquals(0f, active.spec.upperTrail!!.offsetDp)
        assertEquals(3f, active.spec.blob.radiusAcrossDp)
        // And it still lays out.
        val hinge = HingeLine(HingeOrientation.VERTICAL, 1224f, 0f, HingeSource.ESTIMATED)
        assertTrue(CrackGeometry.build(active.spec, 2448f, 1848f, hinge, 2.625f).isNotEmpty())
    }

    // ── The band reads as one band at any thickness (build 2 review) ──────────

    /** The gaps between neighbouring sub-lines, top to bottom (negative: an overlap). */
    private fun gaps(s: CrackSpec): List<Float> =
        s.band.zipWithNext { a, b -> (b.offsetDp - b.widthDp / 2f) - (a.offsetDp + a.widthDp / 2f) }

    /**
     * The widest stretch of the band that reads as solid: neighbouring sub-lines (the separator included — photo 1's
     * split band reads as one band) joined across gaps no wider than [joinDp].
     */
    private fun widestRun(s: CrackSpec, joinDp: Float = JOIN_DP): Float {
        var best = 0f
        var start = s.band.first().offsetDp - s.band.first().widthDp / 2f
        var end = s.band.first().offsetDp + s.band.first().widthDp / 2f
        for (line in s.band.drop(1)) {
            val top = line.offsetDp - line.widthDp / 2f
            if (top - end > joinDp) {
                best = maxOf(best, end - start)
                start = top
            }
            end = maxOf(end, line.offsetDp + line.widthDp / 2f)
        }
        return maxOf(best, end - start)
    }

    @Test
    fun `no band has a hole wider than a third of it`() = manySpecs.forEachIndexed { seed, s ->
        val t = thickness(s)
        assertTrue(gaps(s).max() <= 0.35f * t + eps, "seed $seed: a ${gaps(s).max()} dp gap in a $t dp band")
    }

    @Test
    fun `every band reads as one solid band at least two thirds of its thickness`() = manySpecs.forEachIndexed { seed, s ->
        val t = thickness(s)
        // At least 0.65 T, so at least 13 dp from T = 20 dp up (and the whole band below that, gaps aside).
        assertTrue(widestRun(s) >= 0.65f * t - eps, "seed $seed: widest run ${widestRun(s)} dp of $t dp")
    }

    @Test
    fun `only the bottom fringe ever stands apart, now and then (photo 6)`() {
        manySpecs.forEachIndexed { seed, s ->
            gaps(s).dropLast(1).forEach { assertTrue(it <= JOIN_DP + eps, "seed $seed: a $it dp gap inside the band") }
        }
        val lone = manySpecs.count { gaps(it).last() > JOIN_DP }
        // LONE_FRINGE_CHANCE, less the thin bands where a third of the band leaves no room for more than a hairline.
        assertTrue(lone in 1_200..2_000, "a lone bottom fringe in $lone of 10 000")
    }

    @Test
    fun `the cores fill a thick band`() {
        // A single core is no longer capped at build 1's 6 dp × the scale: in a thick band it takes what is left.
        val single = manySpecs.filter { s -> thickness(s) >= 26f && s.band.count { it.color in CORE_COLORS } == 1 }
        val cores = single.map { s -> s.band.single { it.color in CORE_COLORS }.widthDp }
        assertTrue(cores.average() > 15.0, "average single core in a 26–30 dp band: ${cores.average()} dp")
        // And the band is light, not wallpaper: its lines cover at least 0.6 of it whatever the seed.
        manySpecs.forEachIndexed { seed, s ->
            val lit = s.band.sumOf { it.widthDp.toDouble() } / thickness(s)
            assertTrue(lit >= 0.6, "seed $seed: lines cover $lit of the band")
        }
    }

    @Test
    fun `seed 1162's thick band is no longer mostly a hole`() {
        // Build 2 before the review: a 29.2 dp band was a 1.8 dp pink line, a 17.1 dp hole and ≈ 10 dp of light.
        val s = G.generate(Random(1162))
        val t = thickness(s)
        assertEquals(29.16f, t, 0.01f)
        assertTrue(gaps(s).max() <= 0.35f * t, "gaps ${gaps(s)}")
        assertTrue(widestRun(s) >= 0.65f * t, "widest run ${widestRun(s)} of $t")
    }

    @Test
    fun `no spike points into the band`() = specs.flatMap { it.blob.spikes }.forEach {
        // Angles between the two along-hinge cones on the band side (sin > 0) never occur.
        val bandSide = (G.SPIKE_ALONG_HINGE_SPREAD_DEG + eps)..(180f - G.SPIKE_ALONG_HINGE_SPREAD_DEG - eps)
        assertTrue(it.angleDeg !in bandSide, "angle ${it.angleDeg}")
    }

    @Test
    fun `large blob spikes mostly run along the hinge`() {
        val spikes = specs.flatMap { it.blob.spikes }
        val alongHinge = spikes.count {
            val a = it.angleDeg
            a <= G.SPIKE_ALONG_HINGE_SPREAD_DEG || a >= 360f - G.SPIKE_ALONG_HINGE_SPREAD_DEG ||
                kotlin.math.abs(a - 180f) <= G.SPIKE_ALONG_HINGE_SPREAD_DEG
        }
        assertTrue(alongHinge.toFloat() / spikes.size > 0.7f)
    }

    private companion object {
        /** For the hard bounds: 10 000 seeds, generated once for the whole class. */
        val manySpecs: List<CrackSpec> by lazy { (0L until 10_000L).map { G.generate(Random(it)) } }

        val CORE_COLORS = setOf(CrackColor.BAND_WHITE, CrackColor.BAND_ICE)

        /** Gaps up to this wide read as hairlines inside the band, not as a hole in it. */
        const val JOIN_DP = G.LOOSE_GAP_MAX_DP
    }
}
